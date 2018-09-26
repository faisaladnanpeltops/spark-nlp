package com.johnsnowlabs.nlp.annotators.spell.ocr

import com.johnsnowlabs.ml.tensorflow.{TensorflowSpell, TensorflowWrapper}
import com.johnsnowlabs.nlp.annotators.assertion.dl.ReadTensorflowModel
import com.johnsnowlabs.nlp.annotators.ner.Verbose
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, AnnotatorType}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession

class OcrSpellCheckModel(override val uid: String) extends AnnotatorModel[OcrSpellCheckModel] with ReadTensorflowModel {

  override val tfFile: String = "spell_model"

  private var tensorflow:TensorflowWrapper = null

  def readModel(path: String, spark: SparkSession, suffix: String): this.type = {
    tensorflow = readTensorflowModel(path, spark, suffix)
    this
  }

  @transient
  private var _model: TensorflowSpell = null

  def model: TensorflowSpell = {
    if (_model == null) {
      require(tensorflow != null, "Tensorflow must be set before usage. Use method setTensorflow() for it.")

      _model = new TensorflowSpell(
        tensorflow,
        Verbose.Silent)
    }
    _model
  }

  /*
  def setTensorflow(tf: TensorflowWrapper): OcrSpellCheckModel = {
    tensorflow = tf
    this
  }*/

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val tokens = annotations.map(_.result)
    model
    _model.predict(Array(Array.empty[String]), Array.empty, Array.empty).foreach(println)
    _model.predict(Array(Array.empty[String]), Array.empty, Array.empty).foreach(println)
    
    Seq.empty
  }

  def this() = this(Identifiable.randomUID("SPELL"))

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val requiredAnnotatorTypes: Array[String] = Array(AnnotatorType.TOKEN)
  override val annotatorType: AnnotatorType = AnnotatorType.TOKEN

}
