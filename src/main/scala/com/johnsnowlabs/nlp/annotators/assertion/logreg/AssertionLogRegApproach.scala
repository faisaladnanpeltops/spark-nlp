package com.johnsnowlabs.nlp.annotators.assertion.logreg

import com.johnsnowlabs.nlp.AnnotatorType._
import com.johnsnowlabs.nlp.annotators.pos.perceptron.PerceptronModel
import com.johnsnowlabs.nlp.embeddings.{ApproachWithWordEmbeddings, WordEmbeddings}
import com.johnsnowlabs.nlp.pretrained.ResourceDownloader
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.ml.param.{DoubleParam, IntParam, Param}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._

import scala.collection.mutable

/**
  * Created by jose on 22/11/17.
  */
class AssertionLogRegApproach(val uid: String)
  extends ApproachWithWordEmbeddings[AssertionLogRegApproach, AssertionLogRegModel] with Windowing {

  override val requiredAnnotatorTypes = Array(DOCUMENT)
  val description: String = "Clinical Text Status Assertion"
  override val tokenizer: Tokenizer = new SimpleTokenizer
  override def wordVectors(): Option[WordEmbeddings] = embeddings

  lazy override val (before, after) = (getOrDefault(beforeParam), getOrDefault(afterParam))

  override val annotatorType: AnnotatorType = ASSERTION
  def this() = this(Identifiable.randomUID("ASSERTION"))

  // example of possible values, 'Negated', 'Affirmed', 'Historical'
  val label = new Param[String](this, "label", "Column with one label per document")
  val maxIter = new IntParam(this, "maxIter", "Max number of iterations for algorithm")
  val regParam = new DoubleParam(this, "regParam", "Regularization parameter")
  val eNetParam = new DoubleParam(this, "eNetParam", "Elastic net parameter")
  val beforeParam = new IntParam(this, "beforeParam", "Length of the context before the target")
  val afterParam = new IntParam(this, "afterParam", "Length of the context after the target")

  val nerCol = new Param[String](this, "nerCol", "Column with NER type annotation output, use either nerCol or startCol and endCol")
  val startCol = new Param[String](this, "startCol", "Column that contains the token number for the start of the target")
  val endCol = new Param[String](this, "endCol", "Column that contains the token number for the end of the target")


  def setLabelCol(label: String): this.type = set(label, label)
  def setMaxIter(max: Int): this.type = set(maxIter, max)
  def setReg(lambda: Double): this.type = set(regParam, lambda)
  def setEnet(enet: Double): this.type = set(eNetParam, enet)
  def setBefore(b: Int): this.type = set(beforeParam, b)
  def setAfter(a: Int): this.type = set(afterParam, a)
  def setStartCol(start: String): this.type = set(startCol, start)
  def setEndCol(end: String): this.type = set(endCol, end)
  def setNerCol(col: String): this.type = set(nerCol, col)

  setDefault(label -> "label",
    maxIter -> 26,
    regParam -> 0.00192,
    eNetParam -> 0.9,
    beforeParam -> 10,
    afterParam -> 10
  )

  /* send this to common place */
  def extractTextUdf: UserDefinedFunction = udf { document:mutable.WrappedArray[GenericRowWithSchema] =>
    document.head.getString(3)
  }

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel] = None): AssertionLogRegModel = {
    import dataset.sparkSession.implicits._

    /* apply UDF to fix the length of each document */
    val textCol = $(inputCols).head
    val processed = dataset.toDF.
      withColumn(textCol, extractTextUdf(col(getInputCols.head))).
      withColumn("features", {
        if (get(nerCol).isDefined) {
          explode(applyWindowUdfNer(col(textCol), col($(nerCol))))
        } else if (get(startCol).isDefined & get(endCol).isDefined){
        applyWindowUdf(col(textCol),
          col($(startCol)),
          col($(endCol)))
        } else {
          throw new IllegalArgumentException("Either nerCol or startCol and endCol must be defined")
        }
      })

    val lr = new LogisticRegression()
      .setMaxIter(getOrDefault(maxIter))
      .setRegParam(getOrDefault(regParam))
      .setElasticNetParam(getOrDefault(eNetParam))
      .setPredictionCol("_prediction")

    val labelCol = getOrDefault(label)

    /* infer labels and assign a number to each */
    val labelMappings: Map[String, Double] = dataset.select(labelCol).distinct.collect
      .map(row => row.getAs[String](labelCol)).zipWithIndex
      .map{case (labelK, idx) => (labelK, idx.toDouble)}
      .toMap

    val processedWithLabel = processed.withColumn(labelCol, labelToNumber(labelMappings)(col(labelCol)))

    new AssertionLogRegModel()
      .setBefore(getOrDefault(beforeParam))
      .setAfter(getOrDefault(afterParam))
      .setInputCols(getOrDefault(inputCols))
      .setStartCol(getOrDefault(startCol))
      .setEndCol(getOrDefault(endCol))
      .setLabelMap(labelMappings)
      .setModel(lr.fit(processedWithLabel))
  }

  private def labelToNumber(mappings: Map[String, Double]) = udf { label:String  => mappings.get(label)}

}

trait PretrainedLogRegAssertionStatus {
  def pretrained(name: String = "as_fast_lg", language: Option[String] = Some("en"), folder: String = ResourceDownloader.publicFolder): AssertionLogRegModel =
    ResourceDownloader.downloadModel(AssertionLogRegModel, name, language, folder)
}


object AssertionLogRegApproach extends DefaultParamsReadable[AssertionLogRegApproach] with PretrainedLogRegAssertionStatus