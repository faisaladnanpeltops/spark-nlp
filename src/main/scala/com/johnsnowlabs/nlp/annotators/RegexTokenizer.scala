/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.nlp.annotators

import com.johnsnowlabs.nlp.annotators.common.{IndexedToken, Sentence, SentenceSplit, TokenizedSentence, TokenizedWithSentence}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, WithAnnotate}
import org.apache.spark.ml.param.{BooleanParam, IntParam, Param, ParamValidators}
import org.apache.spark.ml.util.Identifiable

/**
  * A tokenizer that splits text by regex pattern.
  *
  * @see [[RegexTokenizer]]
  */
class RegexTokenizer(override val uid: String) extends AnnotatorModel[RegexTokenizer] with WithAnnotate[RegexTokenizer] {

  import com.johnsnowlabs.nlp.AnnotatorType._


  /** Output annotator type: TOKEN
    *
    * @group anno
    **/
  override val outputAnnotatorType: AnnotatorType = TOKEN

  /** Input annotator type: TOKEN
    *
    * @group anno
    **/
  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT)

  def this() = this(Identifiable.randomUID("RegexTokenizer"))


  /**
    * Regex pattern used to match delimiters
    * Default: `"\\s+"`
    * @group param
    */
  val pattern: Param[String] = new Param(this, "pattern", "regex pattern used for tokenizing")

  /** @group setParam */
  def setPattern(value: String): this.type = set(pattern, value)

  /** @group getParam */
  def getPattern: String = $(pattern)

  /**
    * Indicates whether to convert all characters to lowercase before tokenizing.
    * Default: true
    * @group param
    **/
  val toLowercase: BooleanParam = new BooleanParam(this, "toLowercase",
    "Indicates whether to convert all characters to lowercase before tokenizing.\n")

  /** @group setParam */
  def setToLowercase(value: Boolean): this.type = set(toLowercase, value)

  /** @group getParam */
  def getToLowercase: Boolean = $(toLowercase)

  /**
    * Minimum token length, greater than or equal to 0.
    * Default: 1, to avoid returning empty strings
    * @group param
    */
  val minLength: IntParam = new IntParam(this, "minLength", "minimum token length (>= 0)",
    ParamValidators.gtEq(0))

  /** @group setParam */
  def setMinLength(value: Int): this.type = set(minLength, value)

  /** @group getParam */
  def getMinLength: Int = $(minLength)

  /**
    * Maximum token length, greater than or equal to 1.
    * @group param
    */
  val maxLength: IntParam = new IntParam(this, "maxLength", "maximum token length (>= 1)",
    ParamValidators.gtEq(1))

  /** @group setParam */
  def setMaxLength(value: Int): this.type = set(maxLength, value)

  /** @group getParam */
  def getMaxLength: Int = $(maxLength)

  setDefault(
    inputCols -> Array(DOCUMENT),
    outputCol -> "regexToken",
    toLowercase -> false,
    minLength -> 1,
    pattern -> "\\s+"
  )

  /**
    * This func generates a Seq of TokenizedSentences from a Seq of Sentences.
    *
    * @param sentences to tag
    * @return Seq of TokenizedSentence objects
    */
  def tag(sentences: Seq[Sentence]): Seq[TokenizedSentence] = {
    sentences.map { text =>
      var curPos = 0

      val re = $(pattern).r
      val str = if ($(toLowercase)) text.content.toLowerCase() else text.content
      val tokens = re.split(str).map{token =>
        val indexedTokens = IndexedToken(
          token,
          text.start + curPos,
          text.start + curPos + token.length - 1
        )
        curPos += token.length + 1
        indexedTokens
      }.filter(t => t.token.nonEmpty && t.token.length >= $(minLength) && get(maxLength).forall(m => t.token.length <= m))
      TokenizedSentence(tokens, text.index)

    }
  }
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    val sentences = SentenceSplit.unpack(annotations)
    val tokenized = tag(sentences)
    TokenizedWithSentence.pack(tokenized)

  }

}

