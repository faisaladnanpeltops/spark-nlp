/*
 * Copyright 2017-2021 John Snow Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.nlp.annotators.er

import com.johnsnowlabs.nlp.AnnotatorType.{CHUNK, DOCUMENT, TOKEN}
import com.johnsnowlabs.nlp.annotators.common.{TokenizedSentence, TokenizedWithSentence}
import com.johnsnowlabs.nlp.serialization.StructFeature
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, HasFeatures, HasPretrained, HasSimpleAnnotate}
import com.johnsnowlabs.storage.Database.{ENTITY_PATTERNS, ENTITY_REGEX_PATTERNS, Name}
import com.johnsnowlabs.storage._
import org.apache.spark.ml.param.{BooleanParam, Param, ParamMap, StringArrayParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

class EntityRulerModel(override val uid: String) extends AnnotatorModel[EntityRulerModel]
  with HasSimpleAnnotate[EntityRulerModel] with HasStorageModel {

  def this() = this(Identifiable.randomUID("ENTITY_RULER"))

  private val logger: Logger = LoggerFactory.getLogger("Credentials")

  private[er] val enablePatternRegex = new BooleanParam(this, "enablePatternRegex",
    "Enables regex pattern match")

  private[er] val useStorage = new BooleanParam(this, "useStorage", "Whether to use RocksDB storage to serialize patterns")

  private[er] val regexEntities = new StringArrayParam(this, "regexEntities", "entities defined in regex patterns")

  private[er] val entityRulerFeatures: StructFeature[EntityRulerFeatures] =
    new StructFeature[EntityRulerFeatures](this, "Structure to store data when RocksDB is not used")

  private[er] def setEnablePatternRegex(value: Boolean): this.type = set(enablePatternRegex, value)

  private[er] def setRegexEntities(value: Array[String]): this.type = set(regexEntities, value)

  private[er] def setEntityRulerFeatures(value: EntityRulerFeatures): this.type = set(entityRulerFeatures, value)

  private[er] def setUseStorage(value: Boolean): this.type = set(useStorage, value)

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  val inputAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN)
  val outputAnnotatorType: AnnotatorType = CHUNK

  /**
   * takes a document and annotations and produces new annotations of this annotator's annotation type
   *
   * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
   * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
   */
  def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    val tokenizedWithSentences = TokenizedWithSentence.unpack(annotations)
    var annotatedEntities: Seq[Annotation] = Seq()

    if ($(enablePatternRegex)) {
      val regexPatternsReader = if ($(useStorage))
        Some(getReader(Database.ENTITY_REGEX_PATTERNS).asInstanceOf[RegexPatternsReader]) else None
      annotatedEntities = annotateEntitiesFromRegexPatterns(tokenizedWithSentences, regexPatternsReader)
    } else {
      val patternsReader = if ($(useStorage)) Some(getReader(Database.ENTITY_PATTERNS).asInstanceOf[PatternsReader]) else None
      annotatedEntities = annotateEntitiesFromPatterns(tokenizedWithSentences, patternsReader)
    }

    annotatedEntities
  }

  private def annotateEntitiesFromRegexPatterns(tokenizedWithSentences: Seq[TokenizedSentence],
                                                regexPatternsReader: Option[RegexPatternsReader]): Seq[Annotation] = {
    val annotatedEntities = tokenizedWithSentences.flatMap { tokenizedWithSentence =>
      tokenizedWithSentence.indexedTokens.flatMap { indexedToken =>
        val entity = getMatchedEntity(indexedToken.token, regexPatternsReader)
        if (entity.isDefined) {
          val entityMetadata = getEntityMetadata(entity)
          Some(Annotation(CHUNK, indexedToken.begin, indexedToken.end, indexedToken.token,
            entityMetadata ++ Map("sentence" -> tokenizedWithSentence.sentenceIndex.toString)))
        } else None
      }
    }

    annotatedEntities
  }

  private def getMatchedEntity(token: String, regexPatternsReader: Option[RegexPatternsReader]): Option[String] = {

    val matchesByEntity = $(regexEntities).flatMap{ regexEntity =>
      val regexPatterns: Option[Seq[String]] = regexPatternsReader match {
        case Some(rpr) => rpr.lookup(regexEntity)
        case None => $$(entityRulerFeatures).regexPatterns.get(regexEntity)
      }
      if (regexPatterns.isDefined) {
        val matches = regexPatterns.get.flatMap(regexPattern => regexPattern.r.findFirstIn(token))
        if (matches.nonEmpty) Some(regexEntity) else None
      } else None
    }.toSeq

    if (matchesByEntity.size > 1 ) {
      logger.warn("More than one entity found. Sending the first element of the array")
    }

    matchesByEntity.headOption
  }

  private def annotateEntitiesFromPatterns(tokenizedWithSentences: Seq[TokenizedSentence],
                                           patternsReader: Option[PatternsReader]): Seq[Annotation] = {
    val annotatedEntities = tokenizedWithSentences.flatMap{ tokenizedWithSentence =>
      tokenizedWithSentence.indexedTokens.flatMap{ indexedToken =>
        val labelData: Option[String] = patternsReader match {
          case Some(pr) => pr.lookup(indexedToken.token)
          case None => $$(entityRulerFeatures).patterns.get(indexedToken.token)
        }
        val annotation = if (labelData.isDefined) {
          val entityMetadata = getEntityMetadata(labelData)
          Some(Annotation(CHUNK, indexedToken.begin, indexedToken.end, indexedToken.token,
            entityMetadata ++ Map("sentence" -> tokenizedWithSentence.sentenceIndex.toString)))
        } else None
        annotation
      }
    }

    annotatedEntities
  }

  private def getEntityMetadata(labelData: Option[String]): Map[String, String] = {

    val entityMetadata = labelData.get.split(",").zipWithIndex.flatMap{ case(metadata, index) =>
      if (index == 0) {
        Map("entity"-> metadata)
      } else Map("id"-> metadata)
    }.toMap

    entityMetadata
  }

  override def deserializeStorage(path: String, spark: SparkSession): Unit = {
    if ($(useStorage)) {
      super.deserializeStorage(path: String, spark: SparkSession)
    }
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    if ($(useStorage)) {
      super.onWrite(path, spark)
    }
  }

  protected val databases: Array[Name] = EntityRulerModel.databases

  protected def createReader(database: Name, connection: RocksDBConnection): StorageReader[_] = {
    database match {
      case Database.ENTITY_PATTERNS => new PatternsReader(connection)
      case Database.ENTITY_REGEX_PATTERNS => new RegexPatternsReader(connection)
    }
  }
}

trait ReadablePretrainedEntityRuler extends StorageReadable[EntityRulerModel] with HasPretrained[EntityRulerModel] {

  override val databases: Array[Name] = Array(ENTITY_PATTERNS, ENTITY_REGEX_PATTERNS)

  override val defaultModelName: Option[String] = None

  override def pretrained(): EntityRulerModel = super.pretrained()

  override def pretrained(name: String): EntityRulerModel = super.pretrained(name)

  override def pretrained(name: String, lang: String): EntityRulerModel = super.pretrained(name, lang)

  override def pretrained(name: String, lang: String, remoteLoc: String): EntityRulerModel = super.pretrained(name, lang, remoteLoc)

}

object EntityRulerModel extends ReadablePretrainedEntityRuler