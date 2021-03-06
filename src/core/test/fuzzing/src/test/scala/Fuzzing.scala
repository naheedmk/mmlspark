// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io.FileNotFoundException
import java.nio.file.Files

import com.microsoft.ml.spark.FileUtilities.File
import org.apache.commons.io.FileUtils
import org.apache.spark.ml.util.{MLReadable, MLWritable}
import org.apache.spark.ml._
import org.apache.spark.ml.linalg.DenseVector
import org.apache.spark.ml.param.ParamPair
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.scalactic.{Equality, TolerantNumerics}

case class TestObject[S <: PipelineStage](stage: S,
                                          fitDF: DataFrame,
                                          transDF: DataFrame,
                                          validateDF: Option[DataFrame]) {
  def this(stage: S, df: DataFrame) = {
    this(stage, df, df, None)
  }

  def this(stage: S, fitDF: DataFrame, transDF: DataFrame) = {
    this(stage, fitDF, transDF, None)
  }

}

trait FuzzingMethods extends TestBase {
  val epsilon = 1e-4
  implicit lazy val doubleEq: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)
  implicit lazy val dvEq: Equality[DenseVector] = new Equality[DenseVector]{
    def areEqual(a: DenseVector, b: Any): Boolean = b match {
      case bArr:DenseVector =>
        a.values.zip(bArr.values).forall {case (x, y) => doubleEq.areEqual(x, y)}
    }
  }

  implicit lazy val rowEq: Equality[Row] = new Equality[Row]{
    def areEqual(a: Row, bAny: Any): Boolean = bAny match {
      case b:Row =>
        if (a.length != b.length) { return false }
        (0 until a.length).forall(j =>{
          a(j) match {
            case lhs: DenseVector =>
              lhs === b(j)
            case lhs: Double if lhs.isNaN =>
              b(j).asInstanceOf[Double].isNaN
            case lhs: Double =>
              b(j).asInstanceOf[Double] === lhs
            case lhs =>
              lhs === b(j)
          }
        })
    }
  }

  implicit lazy val dfEq: Equality[DataFrame] = new Equality[DataFrame]{
    def areEqual(a: DataFrame, bAny: Any): Boolean = bAny match {
      case ds:Dataset[_] =>
        val b = ds.toDF()
        if(a.columns !== b.columns){
          return false
        }
        val aSort = a.sort().collect()
        val bSort = b.sort().collect()
        if (aSort.length != bSort.length){
          return false
        }
        aSort.zip(bSort).forall {case (rowA, rowB) =>
          rowA === rowB
        }
    }
  }

}

trait PyTestFuzzing[S <: PipelineStage] extends FuzzingMethods {

  def pyTestObjects(): Seq[TestObject[S]]

  val savedDatasetFolder: File = new File("???")
  // TODO make this Desired location + stage name

  def saveDatasets(): Unit = {
    // TODO implement this body
  }

  def pythonizeParam(p: ParamPair[_]): String = {
    p.param.name + "=" + p.value
    // TODO make this a valid scala to python setter converter.
    // TODO Maybe look at JsonEncode function

  }

  def pyTest(stage: S, fitPath: File, testPath: File): String = {
    val paramMap = stage.extractParamMap()
    stage match {
      case t: Transformer => ???
      //s"transformer = ${stage.getClass.getName.split(".").last}()" +
      //  s""
      //TODO fill this in along with estimator case
      // import stuff
      // load fitting and testing dfs from paths
      // instantiatie the python wrapper with parameters gotten from stage's param map
      // pyStage.transform
      // transformer test logic here
      case e: Estimator[_] => ??? // estimator test logic here
      case _ => throw new MatchError(s"Stage $stage should be a transformer or estimator")
    }
  }

  def getPyTests(): Seq[String] = {
    pyTestObjects().zipWithIndex.map { case (req, i) =>
      pyTest(req.stage,
        new File(new File(savedDatasetFolder, i.toString), "fit"),
        new File(new File(savedDatasetFolder, i.toString), "transform"))
    }
  }

}

trait ExperimentFuzzing[S <: PipelineStage] extends FuzzingMethods {

  def experimentTestObjects(): Seq[TestObject[S]]

  def runExperiment(s: S, fittingDF: DataFrame, transformingDF: DataFrame): DataFrame = {
    s match {
      case t: Transformer =>
        t.transform(transformingDF)
      case e: Estimator[_] =>
        e.fit(fittingDF).transform(transformingDF)
      case _ => throw new MatchError(s"$s is not a Transformer or Estimator")
    }
  }

  def testExperiments(): Unit = {
    experimentTestObjects().foreach { req =>
      val res = runExperiment(req.stage, req.fitDF, req.transDF)
      req.validateDF match {
        case Some(vdf) => assert(res === vdf)
        case None => ()
      }
    }
  }

  test("Experiment Fuzzing"){
    testExperiments()
  }

}

trait SerializationFuzzing[S <: PipelineStage with MLWritable] extends FuzzingMethods {
  def serializationTestObjects(): Seq[TestObject[S]]

  def reader: MLReadable[_]

  def modelReader: MLReadable[_]

  val savePath: String = Files.createTempDirectory("SavedModels-").toString

  val ignoreEstimators: Boolean = false

  private def testSerializationHelper(path: String,
                                  stage: PipelineStage with MLWritable,
                                  reader: MLReadable[_],
                                  fitDF: DataFrame, transDF: DataFrame): Unit = {
    try {
      stage.write.overwrite().save(path)
      assert(new File(path).exists())
      val loadedStage = reader.load(path)
      (stage, loadedStage) match {
        case (e1: Estimator[_], e2: Estimator[_]) =>
          assert(e1.fit(fitDF).transform(transDF) === e2.fit(fitDF).transform(transDF))
        case (t1: Transformer, t2: Transformer) =>
          assert(t1.transform(transDF) === t2.transform(transDF))
        case _ => throw new IllegalArgumentException(s"$stage and $loadedStage do not have proper types")
      }
      ()
    } finally {
      try {
        FileUtils.forceDelete(new File(path))
      } catch {
        case _: FileNotFoundException =>
      }
      ()
    }
  }

  def testSerialization(): Unit = {
    serializationTestObjects().foreach { req =>
      val fitStage = req.stage match {
        case stage: Estimator[_] =>
          if (!ignoreEstimators) {
            testSerializationHelper(savePath + "/stage", stage, reader, req.fitDF, req.transDF)
          }
          stage.fit(req.fitDF).asInstanceOf[PipelineStage with MLWritable]
        case stage: Transformer => stage
        case s => throw new IllegalArgumentException(s"$s does not have correct type")
      }
      testSerializationHelper(savePath + "/fitStage", fitStage, modelReader, req.transDF, req.transDF)

      val pipe = new Pipeline().setStages(Array(req.stage.asInstanceOf[PipelineStage]))
      if (!ignoreEstimators) {
        testSerializationHelper(savePath + "/pipe", pipe, Pipeline, req.fitDF, req.transDF)
      }
      val fitPipe = pipe.fit(req.fitDF)
      testSerializationHelper(savePath + "/fitPipe", fitPipe, PipelineModel, req.transDF, req.transDF)
    }
  }

  test("Serialization Fuzzing"){
    testSerialization()
  }

}

trait Fuzzing[S <: PipelineStage with MLWritable] extends PyTestFuzzing[S]
  with SerializationFuzzing[S] with ExperimentFuzzing[S] {

  def testObjects(): Seq[TestObject[S]]

  def pyTestObjects(): Seq[TestObject[S]] = testObjects()

  def serializationTestObjects(): Seq[TestObject[S]] = testObjects()

  def experimentTestObjects(): Seq[TestObject[S]] = testObjects()

}

trait TransformerFuzzing[S <: Transformer with MLWritable] extends Fuzzing[S] {

  override val ignoreEstimators: Boolean = true

  override def modelReader: MLReadable[_] = reader

}

trait EstimatorFuzzing[S <: Estimator[_] with MLWritable] extends Fuzzing[S]
