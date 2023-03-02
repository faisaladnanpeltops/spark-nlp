package com.johnsnowlabs.nlp.annotators.similarity

import com.johnsnowlabs.nlp.AnnotatorType.{DOC_SIMILARITY_RANKINGS, SENTENCE_EMBEDDINGS}
import com.johnsnowlabs.nlp.{AnnotatorApproach, HasEnableCachingProperties}
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.feature.{BucketedRandomProjectionLSH, VectorAssembler}
import org.apache.spark.ml.functions.array_to_vector
import org.apache.spark.ml.param.Param
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions.{col, expr, flatten}

class DocumentSimilarityRankerApproach(override val uid: String)
  extends AnnotatorApproach[DocumentSimilarityRankerModel]
    with HasStorageRef
    with HasEnableCachingProperties {

  override val description: AnnotatorType = "LSH based document similarity annotator"

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator
   * type
   */
  def this() = this(Identifiable.randomUID("DocumentSimilarityRankerApproach"))

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(SENTENCE_EMBEDDINGS)
  override val outputAnnotatorType: AnnotatorType = DOC_SIMILARITY_RANKINGS

  /** The similarity method used to calculate the neighbours.
   * (Default: `"brp"`, Bucketed Random Projection for Euclidean Distance)
   *
   * @group param
   */
  val similarityMethod = new Param[String](
    this,
    "similarityMethod",
    """The similarity method used to calculate the neighbours.
      |(Default: `"brp"`, Bucketed Random Projection for Euclidean Distance)
      |""".stripMargin)

  def setSimilarityMethod(value: String): this.type = set(similarityMethod, value)

  def getSimilarityMethod: String = $(similarityMethod)

  /** The number of neighbours the model will return (Default:`"10"`).
   *
   * @group param
   */
  val numberOfNeighbours = new Param[Int](
    this,
    "numberOfNeighbours",
    """The number of neighbours the model will return (Default:`"10"`)""")

  def setNumberOfNeighbours(value: Int): this.type = set(numberOfNeighbours, value)

  def getNumberOfNeighbours: Int = $(numberOfNeighbours)

  val bucketLength = new Param[Double](
    this,
    "bucketLength",
    """The bucket length that controls the average size of hash buckets.
      |A larger bucket length (i.e., fewer buckets) increases the probability of features being hashed
      |to the same bucket (increasing the numbers of true and false positives)
      |""".stripMargin)

  def setBucketLength(value: Double): this.type = set(bucketLength, value)

  def getBucketLength: Double = $(bucketLength)

  val numHashTables = new Param[Int](
    this,
    "numHashTables",
    """number of hash tables, where increasing number of hash tables lowers the false negative rate,
      |and decreasing it improves the running performance.
      |""".stripMargin)

  def setNumHashTables(value: Int): this.type = set(numHashTables, value)

  def getNumHashTables: Int = $(numHashTables)

  setDefault(
    inputCols -> Array(SENTENCE_EMBEDDINGS),
    outputCol -> DOC_SIMILARITY_RANKINGS,
    similarityMethod -> "brp",
    numberOfNeighbours -> 10,
    bucketLength -> 2.0,
    numHashTables -> 3
  )

  val LSH_INPUT_COL_NAME = "features"

  val LSH_OUTPUT_COL_NAME = "hashes"

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): DocumentSimilarityRankerModel = {
    val lsh = $(similarityMethod) match {
      case "brp" => new BucketedRandomProjectionLSH()
        .setBucketLength($(bucketLength))
        .setNumHashTables($(numHashTables))
        .setInputCol(LSH_INPUT_COL_NAME)
        .setOutputCol(LSH_OUTPUT_COL_NAME)
      case _ => throw new IllegalArgumentException(s"${$(similarityMethod)} is not a valid value.")
    }

    val embeddingsDataset = dataset.withColumn(LSH_INPUT_COL_NAME, col("sentence_embeddings.embeddings"))
    embeddingsDataset.select(LSH_INPUT_COL_NAME).show(false)

    val lshDataset = embeddingsDataset
      .withColumn(s"$LSH_INPUT_COL_NAME", flatten(col(s"$LSH_INPUT_COL_NAME")))
      .withColumn(s"$LSH_INPUT_COL_NAME", array_to_vector(col(s"$LSH_INPUT_COL_NAME")))
    // .select(expr(s"transform($LSH_INPUT_COL_NAME, x -> x[0])").as(s"$LSH_INPUT_COL_NAME"))
    lshDataset.show(false)

    val model = lsh.fit(lshDataset)

    val datasetMf = Map("lshDataset" -> lshDataset)
    val modelMf = Map("similarityModel" -> model)

    new DocumentSimilarityRankerModel()
      .setLshInputColName(LSH_INPUT_COL_NAME)
      .setLshBucketLength($(bucketLength))
      .setLshNumHashTables($(numHashTables))
      .setLshNumberOfNeighbours($(numberOfNeighbours))
      .setSimilarityModel(modelMf)
      .setDataset(datasetMf)
  }
}
