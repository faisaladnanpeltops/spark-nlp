package com.johnsnowlabs.nlp.annotators.classifier.dl

import com.johnsnowlabs.ml.tensorflow.{ClassifierDatasetEncoder, ClassifierDatasetEncoderParams, ReadTensorflowModel, TensorflowSentiment, TensorflowWrapper, WriteTensorflowModel}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, AnnotatorType, HasPretrained, ParamsAndFeaturesReadable, ParamsAndFeaturesWritable}
import com.johnsnowlabs.nlp.AnnotatorType.{CATEGORY, SENTENCE_EMBEDDINGS}
import com.johnsnowlabs.nlp.annotators.ner.Verbose
import com.johnsnowlabs.nlp.pretrained.ResourceDownloader
import com.johnsnowlabs.nlp.serialization.StructFeature
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{FloatParam, IntArrayParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{Dataset, SparkSession}

class SentimentDLModel(override val uid: String)
  extends AnnotatorModel[SentimentDLModel]
    with WriteTensorflowModel
    with HasStorageRef
    with ParamsAndFeaturesWritable {
  def this() = this(Identifiable.randomUID("SentimentDLModel"))

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(SENTENCE_EMBEDDINGS)
  override val outputAnnotatorType: String = CATEGORY

  val threshold = new FloatParam(this, "threshold", "The minimum threshold for the final result otheriwse it will be neutral")
  def setThreshold(threshold: Float): SentimentDLModel.this.type = set(this.threshold, threshold)
  def getThreshold: Float = $(this.threshold)

  val configProtoBytes = new IntArrayParam(
    this,
    "configProtoBytes",
    "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()"
  )

  def setConfigProtoBytes(
                           bytes: Array[Int]
                         ): SentimentDLModel.this.type = set(this.configProtoBytes, bytes)

  def getConfigProtoBytes: Option[Array[Byte]] =
    get(this.configProtoBytes).map(_.map(_.toByte))

  val datasetParams = new StructFeature[ClassifierDatasetEncoderParams](this, "datasetParams")
  def setDatasetParams(params: ClassifierDatasetEncoderParams): SentimentDLModel.this.type =
    set(this.datasetParams, params)

  private var _model: Option[Broadcast[TensorflowSentiment]] = None
  def setModelIfNotSet(spark: SparkSession, tf: TensorflowWrapper): this.type = {
    if (_model.isEmpty) {

      require(datasetParams.isSet, "datasetParams must be set before usage")

      val encoder = new ClassifierDatasetEncoder(datasetParams.get.get)

      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowSentiment(
            tf,
            encoder,
            Verbose.Silent
          )
        )
      )
    }
    this
  }
  def getModelIfNotSet: TensorflowSentiment = _model.get.value

  setDefault(
    threshold -> 0.6f
  )

  override protected def beforeAnnotate(dataset: Dataset[_]): Dataset[_] = {
    validateStorageRef(dataset, $(inputCols), AnnotatorType.SENTENCE_EMBEDDINGS)
    dataset
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = annotations
      .filter(_.annotatorType == SENTENCE_EMBEDDINGS)
      .groupBy(_.metadata.getOrElse[String]("sentence", "0").toInt)
      .toSeq
      .sortBy(_._1)

    getModelIfNotSet.predict(sentences, getConfigProtoBytes, $(threshold))
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(
      path,
      spark,
      getModelIfNotSet.tensorflow,
      "_sentimentdl",
      SentimentDLModel.tfFile,
      configProtoBytes = getConfigProtoBytes
    )

  }
}

trait ReadablePretrainedSentimentDL
  extends ParamsAndFeaturesReadable[SentimentDLModel]
    with HasPretrained[SentimentDLModel] {
  override val defaultModelName: Some[String] = Some("sentimentdl_use_imdb")

  override def pretrained(name: String, lang: String, remoteLoc: String): SentimentDLModel = {
    ResourceDownloader.downloadModel(SentimentDLModel, name, Option(lang), remoteLoc)
  }

  /** Java compliant-overrides */
  override def pretrained(): SentimentDLModel = pretrained(defaultModelName.get, defaultLang, defaultLoc)
  override def pretrained(name: String): SentimentDLModel = pretrained(name, defaultLang, defaultLoc)
  override def pretrained(name: String, lang: String): SentimentDLModel = pretrained(name, lang, defaultLoc)
}

trait ReadSentimentDLTensorflowModel extends ReadTensorflowModel {
  this: ParamsAndFeaturesReadable[SentimentDLModel] =>

  override val tfFile: String = "sentimentdl_tensorflow"

  def readTensorflow(instance: SentimentDLModel, path: String, spark: SparkSession): Unit = {

    val tf = readTensorflowChkPoints(path, spark, "_sentimentdl_tf", initAllTables = true)
    instance.setModelIfNotSet(spark, tf)
  }

  addReader(readTensorflow)
}

object SentimentDLModel extends ReadablePretrainedSentimentDL with ReadSentimentDLTensorflowModel
