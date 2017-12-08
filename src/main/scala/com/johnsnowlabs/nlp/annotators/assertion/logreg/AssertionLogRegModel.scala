package com.johnsnowlabs.nlp.annotators.assertion.logreg

import com.johnsnowlabs.nlp.AnnotatorType.{ASSERTION, DOCUMENT, POS}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, AnnotatorType}
import com.johnsnowlabs.nlp.embeddings.ModelWithWordEmbeddings
import org.apache.spark.ml.classification.{LogisticRegression, LogisticRegressionModel}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, Dataset}

/**
  * Created by jose on 22/11/17.
  */

class AssertionLogRegModel(model:LogisticRegressionModel, tag2Vec: Map[String, Array[Double]], override val uid: String = Identifiable.randomUID("ASSERTIOM"))
  extends AnnotatorModel[AssertionLogRegModel] with ModelWithWordEmbeddings with Windowing {

  // TODO this should come as a parameter
  override val (before, after) = (10, 14)

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override protected def annotate(annotations: Seq[Annotation]): Seq[Annotation] = annotations
  //def this() = this(Identifiable.randomUID("ASSERTION"))


  override val annotatorType: AnnotatorType = AnnotatorType.ASSERTION
  override val requiredAnnotatorTypes = Array(DOCUMENT, POS)
  override final def transform(dataset: Dataset[_]): DataFrame = {
    import dataset.sqlContext.implicits._

    /* apply UDF to fix the length of each document */
    val processed = dataset.toDF.
      withColumn("features", applyWindowUdf(embeddings.get)($"text", $"target")).cache() //, $"pos", $"start", $"end"
      //.select($"features", $"label")

    super.transform(model.transform(processed))
  }
}

object AssertionLogRegModel {
  def apply(model: LogisticRegressionModel, tag2Vec: Map[String, Array[Double]]): AssertionLogRegModel = new AssertionLogRegModel(model, tag2Vec)
}
