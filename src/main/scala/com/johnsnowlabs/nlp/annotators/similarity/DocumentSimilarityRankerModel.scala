package com.johnsnowlabs.nlp.annotators.similarity

import com.johnsnowlabs.nlp.AnnotatorType.{DOC_SIMILARITY_RANKINGS, SENTENCE_EMBEDDINGS}
import com.johnsnowlabs.nlp.embeddings.HasEmbeddingsProperties
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp._
import org.apache.spark.ml.util.Identifiable

import scala.util.hashing.MurmurHash3

/** Instantiated model of the [[DocumentSimilarityRankerApproach]]. For usage and examples see the
  * documentation of the main class.
  *
  * @param uid
  *   internally renquired UID to make it writable
  */
class DocumentSimilarityRankerModel(override val uid: String)
    extends AnnotatorModel[DocumentSimilarityRankerModel]
    with HasSimpleAnnotate[DocumentSimilarityRankerModel]
    with HasEmbeddingsProperties
    with ParamsAndFeaturesWritable {

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(SENTENCE_EMBEDDINGS)

  override val outputAnnotatorType: AnnotatorType = DOC_SIMILARITY_RANKINGS

  def this() = this(Identifiable.randomUID("DOC_SIMILARITY_RANKER"))

  /** Dictionary of words with their vectors
    *
    * @group param
    */
  val similarityMappings: MapFeature[String, Map[Int, NeighborAnnotation]] =
    new MapFeature(this, "similarityMappings")

  /** @group setParam */
  def setSimilarityMappings(value: Map[String, Map[Int, NeighborAnnotation]]): this.type =
    set(similarityMappings, value)

  def getSimilarityMappings: Map[Int, NeighborAnnotation] =
    $$(similarityMappings).getOrElse("similarityMappings", Map.empty)

  setDefault(inputCols -> Array(SENTENCE_EMBEDDINGS), outputCol -> DOC_SIMILARITY_RANKINGS)

  /** takes a document and annotations and produces new annotations of this annotator's annotation
    * type
    *
    * @param annotations
    *   Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return
    *   any number of annotations processed for every input annotation. Not necessary one to one
    *   relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] =
    annotations.map(annotation => {
      val inputResult = annotation.result
      val targetIndex = MurmurHash3.stringHash(inputResult, MurmurHash3.stringSeed)
      val neighborsAnnotation: NeighborAnnotation =
        getSimilarityMappings.getOrElse(targetIndex, IndexedNeighbors(Array.empty)) // index NA

      Annotation(
        annotatorType = outputAnnotatorType,
        begin = annotation.begin,
        end = annotation.end,
        result = annotation.result,
        metadata = annotation.metadata
          + ("lshId" -> targetIndex.toString)
          + ("lshNeighbors" -> neighborsAnnotation.neighbors.mkString("[", ",", "]")),
        embeddings = annotation.embeddings)
    })
}

trait ReadableDocumentSimilarityRanker
    extends ParamsAndFeaturesReadable[DocumentSimilarityRankerModel]

object DocumentSimilarityRankerModel extends ReadableDocumentSimilarityRanker
