package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, TOKEN, WORD_EMBEDDINGS}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, ParamsAndFeaturesWritable}
import com.johnsnowlabs.nlp.annotators.common.{TokenPieceEmbeddings, TokenizedWithSentence, WordpieceEmbeddingsSentence}
import com.johnsnowlabs.nlp.pretrained.ResourceDownloader
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SparkSession}


class WordEmbeddingsModel(override val uid: String)
  extends AnnotatorModel[WordEmbeddingsModel]
    with HasWordEmbeddings
    with AutoCloseable
    with ParamsAndFeaturesWritable {

  def this() = this(Identifiable.randomUID("WORD_EMBEDDINGS_MODEL"))

  override val outputAnnotatorType: AnnotatorType = WORD_EMBEDDINGS
  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN)

  private def getEmbeddingsSerializedPath(path: String): Path =
    Path.mergePaths(new Path(path), new Path("/embeddings"))

  private[embeddings] def deserializeEmbeddings(path: String, spark: SparkSession): Unit = {
    val src = getEmbeddingsSerializedPath(path)

    EmbeddingsHelper.load(
      src.toUri.toString,
      spark,
      WordEmbeddingsFormat.SPARKNLP.toString,
      $(dimension),
      $(caseSensitive),
      $(embeddingsRef)
    )
  }

  private[embeddings] def serializeEmbeddings(path: String, spark: SparkSession): Unit = {
    val index = new Path(EmbeddingsHelper.getLocalEmbeddingsPath(getClusterEmbeddings.fileName))

    val uri = new java.net.URI(path)
    val fs = FileSystem.get(uri, spark.sparkContext.hadoopConfiguration)
    val dst = getEmbeddingsSerializedPath(path)

    EmbeddingsHelper.save(fs, index, dst)
  }

  override protected def onWrite(path: String, spark: SparkSession): Unit = {
    /** Param only useful for runtime execution */
    if ($(includeEmbeddings))
      serializeEmbeddings(path, spark)
  }

  override protected def close(): Unit = {
    get(embeddingsRef)
      .flatMap(_ => preloadedEmbeddings)
      .foreach(_.getLocalRetriever.close())
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = TokenizedWithSentence.unpack(annotations)
    val withEmbeddings = sentences.zipWithIndex.map{case (s, idx) =>
      val tokens = s.indexedTokens.map {token =>
        val vector = this.getEmbeddings.getEmbeddingsVector(token.token)
        new TokenPieceEmbeddings(token.token, token.token, -1, true, vector, token.begin, token.end)
      }
      WordpieceEmbeddingsSentence(tokens, idx)
    }

    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    getClusterEmbeddings.getLocalRetriever.close()

    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension), Some(getEmbeddingsRef)))
  }

}

object WordEmbeddingsModel extends EmbeddingsReadable[WordEmbeddingsModel] with PretrainedWordEmbeddings

trait PretrainedWordEmbeddings {
  def pretrained(name: String = "glove_100d", language: Option[String] = None, remoteLoc: String = ResourceDownloader.publicLoc): WordEmbeddingsModel =
    ResourceDownloader.downloadModel(WordEmbeddingsModel, name, language, remoteLoc)
}