package com.johnsnowlabs.nlp.annotators.sbd.pragmatic

import com.johnsnowlabs.nlp.annotators.common.{Sentence, SentenceSplit}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import org.apache.spark.ml.param.{BooleanParam, StringArrayParam}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.{DataFrame, Dataset}

/**
  * Annotator that detects sentence boundaries using any provided approach
  * @param uid internal constructor requirement for serialization of params
  * @@ model: Model to use for boundaries detection
  */
class SentenceDetector(override val uid: String) extends AnnotatorModel[SentenceDetector] {

  import com.johnsnowlabs.nlp.AnnotatorType._

  val useAbbrevations = new BooleanParam(this, "useAbbreviations", "whether to apply abbreviations at sentence detection")

  val useCustomBoundsOnly = new BooleanParam(this, "useCustomBoundsOnly", "whether to only utilize custom bounds for sentence detection")

  val explodeSentences = new BooleanParam(this, "explodeSentences", "whether to explode each sentence into a different row, for better parallelization. Defaults to false.")

  val customBounds: StringArrayParam = new StringArrayParam(
    this,
    "customBounds",
    "characters used to explicitly mark sentence bounds"
  )

  def this() = this(Identifiable.randomUID("SENTENCE"))

  def setCustomBounds(value: Array[String]): this.type = set(customBounds, value)

  def setUseCustomBoundsOnly(value: Boolean): this.type = set(useCustomBoundsOnly, value)

  def setUseAbbreviations(value: Boolean): this.type = set(useAbbrevations, value)

  def setExplodeSentences(value: Boolean): this.type = set(explodeSentences, value)

  override val annotatorType: AnnotatorType = DOCUMENT

  override val requiredAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT)

  setDefault(
    inputCols -> Array(DOCUMENT),
    useAbbrevations -> true,
    useCustomBoundsOnly -> false,
    explodeSentences -> false,
    customBounds -> Array.empty[String]
  )

  lazy val model: PragmaticMethod =
    if ($(customBounds).nonEmpty && $(useCustomBoundsOnly))
      new CustomPragmaticMethod($(customBounds))
    else if ($(customBounds).nonEmpty)
      new MixedPragmaticMethod($(useAbbrevations), $(customBounds))
    else
      new DefaultPragmaticMethod($(useAbbrevations))

  def tag(document: String): Array[Sentence] = {
    model.extractBounds(
      document
    )
  }

  override def beforeAnnotate(dataset: Dataset[_]): Dataset[_] = {
    /** Preload model */
    model

    dataset
  }

  /**
    * Uses the model interface to prepare the context and extract the boundaries
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return One to many annotation relationship depending on how many sentences there are in the document
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val docs = annotations.map(_.result)
    val sentences = docs.flatMap(doc => tag(doc))
    SentenceSplit.pack(sentences)
  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    import org.apache.spark.sql.functions.{col, explode, array}
    if ($(explodeSentences)) {
      dataset
        .select(dataset.columns.filterNot(_ == getOutputCol).map(col) :+ explode(col(getOutputCol)).as("_tmp"):_*)
        .withColumn(getOutputCol, array(col("_tmp")).as(getOutputCol, dataset.schema.fields.find(_.name == getOutputCol).get.metadata))
        .drop("_tmp")
    }
    else dataset
  }

}

object SentenceDetector extends DefaultParamsReadable[SentenceDetector]