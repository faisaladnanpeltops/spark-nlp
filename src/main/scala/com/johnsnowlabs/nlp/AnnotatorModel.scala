package com.johnsnowlabs.nlp

import org.apache.spark.ml.Model
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.sql.types._
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.MetadataBuilder

/**
  * Created by jose on 21/01/18.
  */
abstract class AnnotatorModel[M <: Model[M]]
  extends Model[M]
    with ParamsAndFeaturesWritable
    with HasAnnotatorType
    with HasInputAnnotationCols
    with HasOutputAnnotationCol
    with TransformModelSchema {

  /**
    * internal types to show Rows as a relevant StructType
    * Should be deleted once Spark releases UserDefinedTypes to @developerAPI
    */
  private type AnnotationContent = Seq[Row]

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  protected def annotate(annotations: Seq[Annotation]): Seq[Annotation]

  /**
    * Wraps annotate to happen inside SparkSQL user defined functions in order to act with [[org.apache.spark.sql.Column]]
    * @return udf function to be applied to [[inputCols]] using this annotator's annotate function as part of ML transformation
    */
  private def dfAnnotate: UserDefinedFunction = udf {
    annotatorProperties: Seq[AnnotationContent] =>
      annotate(annotatorProperties.flatMap(_.map(Annotation(_))))
  }


  /**
    * Given requirements are met, this applies ML transformation within a Pipeline or stand-alone
    * Output annotation will be generated as a new column, previous annotations are still available separately
    * metadata is built at schema level to record annotations structural information outside its content
    *
    * @param dataset [[Dataset[Row]]]
    * @return
    */
  override final def transform(dataset: Dataset[_]): DataFrame = {
    require(validate(dataset.schema), s"Missing annotators in pipeline. Make sure the following are present: " +
      s"${requiredAnnotatorTypes.mkString(", ")}")
    val metadataBuilder: MetadataBuilder = new MetadataBuilder()
    metadataBuilder.putString("annotatorType", annotatorType)
    dataset.withColumn(
      getOutputCol,
      dfAnnotate(
        array(getInputCols.map(c => dataset.col(c)):_*)
      ).as(getOutputCol, metadataBuilder.build)
    )
  }

  /** requirement for annotators copies */
  override def copy(extra: ParamMap): M = defaultCopy(extra)

}
