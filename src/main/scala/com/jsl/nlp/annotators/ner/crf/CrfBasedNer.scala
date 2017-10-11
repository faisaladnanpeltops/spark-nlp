package com.jsl.nlp.annotators.ner.crf

import com.jsl.ml.crf.{CrfParams, LinearChainCrf, Verbose}
import com.jsl.nlp.AnnotatorApproach
import com.jsl.nlp.AnnotatorType.{DOCUMENT, NAMED_ENTITY, POS, TOKEN}
import org.apache.spark.ml.param.{DoubleParam, IntParam, Param, StringArrayParam}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.Dataset

/*
  Algorithm for training Named Entity Recognition Model.
   */
class CrfBasedNer(override val uid: String) extends AnnotatorApproach[CrfBasedNerModel]{
  def this() = this(Identifiable.randomUID("NER"))

  override val description: String = "CRF based Named Entity Recognition tagger"
  override val requiredAnnotatorTypes = Array(DOCUMENT, TOKEN, POS)
  override val annotatorType = NAMED_ENTITY

  val labelColumn = new Param[String](this, "labelColumn", "Column with label per each token")
  val entities = new StringArrayParam(this, "entities", "Entities to recognize")

  val minEpochs = new IntParam(this, "minEpochs", "Minimum number of epochs to train")
  val maxEpochs = new IntParam(this, "maxEpochs", "Maximum number of epochs to train")
  val l2 = new DoubleParam(this, "l2", "L2 regularization coefficient")
  val c0 = new IntParam(this, "c0", "c0 params defining decay speed for gradient")
  val lossEps = new DoubleParam(this, "lossEps", "If Epoch relative improvement less than eps then training is stopped")
  val minW = new DoubleParam(this, "minW", "Features with less weights then this param value will be filtered")

  val verbose = new IntParam(this, "verbose", "Level of verbosity during training")
  val randomSeed = new IntParam(this, "randomSeed", "Random seed")

  def setLabelColumn(column: String) = set(labelColumn, column)
  def setEntities(tags: Array[String]) = set(entities, tags)

  def setMinEpochs(epochs: Int) = set(minEpochs, epochs)
  def setMaxEpochs(epochs: Int) = set(maxEpochs, epochs)
  def setL2(l2: Double) = set(this.l2, l2)
  def setC0(c0: Int) = set(this.c0, c0)
  def setLossEps(eps: Double) = set(this.lossEps, eps)
  def setMinW(w: Double) = set(this.minW, w)

  def setVerbose(verbose: Int) = set(this.verbose, verbose)
  def setVerbose(verbose: Verbose.Level) = set(this.verbose, verbose.id)
  def setRandomSeed(seed: Int) = set(randomSeed, seed)

  setDefault(
    minEpochs -> 0,
    maxEpochs -> 1000,
    l2 -> 1f,
    c0 -> 2250000,
    lossEps -> 1e-3f,
    verbose -> Verbose.Silent.id
  )

  override def train(dataset: Dataset[_]): CrfBasedNerModel = {

    val rows = dataset.toDF()

    val trainDataset = NerTagged.collectTrainingInstances(rows, getInputCols, $(labelColumn))
    val crfDataset = FeatureGenerator.generateDataset(trainDataset.toIterator)

    val params = CrfParams(
      minEpochs = getOrDefault(minEpochs),
      maxEpochs = getOrDefault(maxEpochs),

      l2 = getOrDefault(l2).toFloat,
      c0 = getOrDefault(c0),
      lossEps = getOrDefault(lossEps).toFloat,

      verbose = Verbose.Epochs,
      randomSeed = get(randomSeed)
    )

    val crf = new LinearChainCrf(params)
    val crfModel = crf.trainSGD(crfDataset)

    var model = new CrfBasedNerModel()
      .setModel(crfModel)

    if (isDefined(entities))
      model.setEntities($(entities))

    if (isDefined(minW))
      model = model.shrink($(minW).toFloat)

    model
  }
}

object CrfBasedNer extends DefaultParamsReadable[CrfBasedNer]