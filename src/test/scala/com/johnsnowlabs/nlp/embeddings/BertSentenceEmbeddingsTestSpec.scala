package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.Annotation
import com.johnsnowlabs.nlp.annotator.SentenceDetectorDLModel
import com.johnsnowlabs.nlp.annotators.sbd.pragmatic.SentenceDetector
import com.johnsnowlabs.nlp.base.DocumentAssembler
import com.johnsnowlabs.nlp.util.io.ResourceHelper
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.scalatest._

import scala.collection.mutable

class BertSentenceEmbeddingsTestSpec extends FlatSpec {

  "BertSentenceEmbeddings" should "produce consistent embeddings" in {

    val testData = ResourceHelper.spark.createDataFrame(Seq(
//      (1, "John loves apples."),
//      (2, "Mary loves oranges."),
//      (3, "John loves Mary.")

      (1, "John loves apples."),
      (2, "Mary loves oranges. John loves Mary.")

    )).toDF("id", "text")

    val document = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val sentence = SentenceDetectorDLModel
      .load("/home/i/cache_pretrained/sentence_detector_dl_healthcare_en_2.6.0_2.4_1600001082565")
      .setInputCols("document")
      .setOutputCol("sentence")

    val embeddings = BertSentenceEmbeddings
      .loadSavedModel("/models/sbert", ResourceHelper.spark)
      .setCaseSensitive(true)
      //.load("/models/sbiobert")
//      .load("/home/i/cache_pretrained/sent_bert_base_cased_en_2.6.0_2.4_1598346030732")
      .setIsSBert(true)
      .setInputCols(Array("sentence"))
      .setOutputCol("bert")
      .setCaseSensitive(false)
      .setMaxSentenceLength(64)


    val pipeline = new Pipeline().setStages(Array(document, sentence, embeddings))

    val model = pipeline.fit(testData)
    val results = model.transform(testData).select("id", "bert").collect()

    results.foreach(row => {
      val rowI = row.get(0)
      row.get(1).asInstanceOf[mutable.WrappedArray[GenericRowWithSchema]].zipWithIndex.foreach(t => {
        print("%1$-3s\t%2$-3s\t%3$-30s\t".format(rowI.toString, t._2.toString, t._1.getString(3)))
        println(t._1.get(5).asInstanceOf[mutable.WrappedArray[Float]].slice(0, 5).map("%1$-7.3f".format(_)).mkString(" "))
      })
    })

    model.stages(2).asInstanceOf[BertSentenceEmbeddings].write.overwrite().save("/models/sbiobert")
  }

  "BertSentenceEmbeddings" should "correctly work with empty tokens" ignore {

    val testData = ResourceHelper.spark.createDataFrame(Seq(
      (1, "This is my first sentence. This is my second."),
      (2, "This is my third sentence. This is my forth.")
    )).toDF("id", "text")

    val document = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val sentence = new SentenceDetector()
      .setInputCols("document")
      .setOutputCol("sentence")

    val embeddings = BertSentenceEmbeddings.pretrained("sent_small_bert_L2_128")
      .setInputCols("document")
      .setOutputCol("bert")
      .setCaseSensitive(false)
      .setMaxSentenceLength(64)


    val pipeline = new Pipeline().setStages(Array(document, sentence, embeddings))

    val pipelineDF = pipeline.fit(testData).transform(testData)

    pipelineDF.select("bert.embeddings").show()

  }

}
