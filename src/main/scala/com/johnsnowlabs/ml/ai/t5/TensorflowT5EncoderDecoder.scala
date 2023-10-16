package com.johnsnowlabs.ml.ai.t5

import com.johnsnowlabs.ml.tensorflow.{TensorResources, TensorflowWrapper}
import com.johnsnowlabs.ml.tensorflow.sentencepiece.SentencePieceWrapper
import com.johnsnowlabs.ml.tensorflow.sign.{ModelSignatureConstants, ModelSignatureManager}
import org.tensorflow.{Session, Tensor}

import scala.collection.JavaConverters._

private[johnsnowlabs] class TensorflowT5EncoderDecoder(
                                                               val tensorflow: TensorflowWrapper,
                                                               override val spp: SentencePieceWrapper,
                                                               override val additionalTokens: Map[Int, String] = Map(),
                                                               val configProtoBytes: Option[Array[Byte]] = None,
                                                               val signatures: Option[Map[String, String]] = None,
                                                               val useCache: Boolean = false)
  extends T5EncoderDecoder(spp, additionalTokens) {

  private val _tfT5Signatures: Map[String, String] =
    signatures.getOrElse(ModelSignatureManager.apply())

  private val decoderInitInputIdsKey = "decoder_init_decoder_input_ids:0"
  private val decoderInitEncoderAttentionMaskKey = "decoder_init_encoder_attention_mask:0"
  private val decoderInitEncoderStateKey = "decoder_init_encoder_state:0"

  private val decoderInitOutputLogitsKey = if (useCache) "StatefulPartitionedCall_1:2" else "StatefulPartitionedCall:0"
  private val decoderInitOutputCache1Key = "StatefulPartitionedCall_1:0"
  private val decoderInitOutputCache2Key = "StatefulPartitionedCall_1:1"

  private val decoderCachedInputIdsKey = "decoder_cached_decoder_input_ids:0"
  private val decoderCachedEncoderAttentionMaskKey = "decoder_cached_encoder_attention_mask:0"
  private val decoderCachedEncoderStateKey = "decoder_cached_encoder_state:0"
  private val decoderCachedCache1Key = "decoder_cached_cache1:0"
  private val decoderCachedCache2Key = "decoder_cached_cache2:0"

  private val decoderCachedOutputLogitsKey = "StatefulPartitionedCall:2"
  private val decoderCachedOutputCache1Key = "StatefulPartitionedCall:0"
  private val decoderCachedOutputCache2Key = "StatefulPartitionedCall:1"

  sessionWarmup()

  override def tag(
                    batch: Seq[Array[Int]],
                    maxNewTokens: Int,
                    maxTextLength: Int,
                    doSample: Boolean,
                    topK: Int,
                    topP: Double,
                    temperature: Double,
                    noRepeatNgramSize: Int,
                    repetitionPenalty: Double,
                    randomSeed: Option[Long],
                    ignoreTokenIds: Array[Int] = Array(),
                    stopAtEos: Boolean): Array[Array[Int]] = {

    /* Actual size of each sentence to skip padding in the TF model */
    val sequencesLength = batch.map(x => x.length).toArray
    val maxSentenceLength = sequencesLength.max // - curLen

    val numReturn_sequences = 1
    // from config

    // Run encoder
    val tensorEncoder = new TensorResources()
    val inputDim = batch.length * maxSentenceLength

    val encoderInputBuffers = tensorEncoder.createIntBuffer(inputDim)
    val encoderAttentionMaskBuffers = tensorEncoder.createIntBuffer(inputDim)

    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.zipWithIndex.foreach { case (tokenIds, idx) =>
      val offset = idx * maxSentenceLength
      val diff = maxSentenceLength - tokenIds.length

      val s = tokenIds.take(maxSentenceLength) ++ Array.fill[Int](diff)(this.paddingTokenId)
      encoderInputBuffers.offset(offset).write(s)
      val mask = s.map(x => if (x != this.paddingTokenId) 1 else 0)
      encoderAttentionMaskBuffers.offset(offset).write(mask)
    }

    val encoderInputTensors = tensorEncoder.createIntBufferTensor(shape, encoderInputBuffers)
    val encoderAttentionMaskTensors =
      tensorEncoder.createIntBufferTensor(shape, encoderAttentionMaskBuffers)

    val session = tensorflow.getTFSessionWithSignature(
      configProtoBytes = configProtoBytes,
      initAllTables = false,
      savedSignatures = signatures)

    val runner = session.runner

    runner
      .feed(
        _tfT5Signatures.getOrElse(
          ModelSignatureConstants.EncoderInputIds.key,
          "missing_encoder_input_ids"),
        encoderInputTensors)
      .feed(
        _tfT5Signatures.getOrElse(
          ModelSignatureConstants.EncoderAttentionMask.key,
          "missing_encoder_attention_mask"),
        encoderAttentionMaskTensors)
      .fetch(_tfT5Signatures
        .getOrElse(ModelSignatureConstants.EncoderOutput.key, "missing_last_hidden_state"))

    val encoderOuts = runner.run().asScala
    val encoderOutsFloats = TensorResources.extractFloats(encoderOuts.head)
    val dim = encoderOutsFloats.length / inputDim
    val encoderOutsBatch =
      encoderOutsFloats.grouped(dim).toArray.grouped(maxSentenceLength).toArray

    encoderOuts.foreach(_.close())

    // Run decoder
    val decoderEncoderStateTensorResources = new TensorResources()
    val decoderEncoderStateBuffers =
      decoderEncoderStateTensorResources.createFloatBuffer(batch.length * maxSentenceLength * dim)
    batch.zipWithIndex.foreach { case (_, index) =>
      var offset = index * maxSentenceLength * dim
      encoderOutsBatch(index).foreach(encoderOutput => {
        decoderEncoderStateBuffers.offset(offset).write(encoderOutput)
        offset += dim
      })
    }

    val decoderEncoderStateTensors = tensorEncoder.createFloatBufferTensor(
      Array(batch.length.toLong, maxSentenceLength, dim),
      decoderEncoderStateBuffers)

    val modelOutputs = generateNoBeamSearch(
      batch,
      decoderEncoderStateTensors,
      encoderAttentionMaskTensors,
      maxNewTokens=maxNewTokens,
      maxTextLength=maxTextLength,
      doSample=doSample,
      topK=topK,
      topP=topP,
      temperature=temperature,
      vocabSize=vocabSize,
      randomSeed=randomSeed,
      session=session,
      ignoreTokenIds=ignoreTokenIds,
      stopAtEos=stopAtEos,
      noRepeatNgramSize=noRepeatNgramSize,
      repetitionPenalty=repetitionPenalty)

    tensorEncoder.clearTensors()
    tensorEncoder.clearSession(encoderOuts)
    modelOutputs

  }

  def generateNoBeamSearch(
                            inputIds: Seq[Array[Int]],
                            decoderEncoderStateTensors: Tensor,
                            encoderAttentionMaskTensors: Tensor,
                            maxNewTokens: Int,
                            maxTextLength: Int,
                            doSample: Boolean,
                            topK: Int,
                            topP: Double,
                            temperature: Double,
                            vocabSize: Int,
                            randomSeed: Option[Long],
                            session: Session,
                            ignoreTokenIds: Array[Int] = Array(),
                            stopAtEos: Boolean,
                            noRepeatNgramSize: Int,
                            repetitionPenalty: Double): Array[Array[Int]] = {

    /** Generate sequences for each example without beam search (numBeams == 1). All returned
      * sequence are generated independently.
      */
    var decoderInputs = inputIds.map(_ => Array(this.paddingTokenId)).toArray
    val batchSize = decoderInputs.length
    var nextStateTensor1: Option[org.tensorflow.Tensor] = None
    var nextStateTensor2: Option[org.tensorflow.Tensor] = None
    val tensorDecoder = new TensorResources()
    val stopTokens = if (stopAtEos) Array(this.eosTokenId) else Array[Int]()

    val decoderProcessor = new DecoderProcessor(
      batchSize = batchSize,
      maxTextLength = maxTextLength,
      sequenceLength = decoderInputs(0).length,
      doSample = doSample,
      topK = topK,
      topP = topP,
      temperature = temperature,
      vocabSize = vocabSize,
      noRepeatNgramSize = noRepeatNgramSize,
      randomSeed = randomSeed,
      stopTokens = stopTokens,
      ignoreTokenIds = ignoreTokenIds,
      maxNewTokens = maxNewTokens,
      repetitionPenalty = repetitionPenalty
    )

    while (!decoderProcessor.stopDecoding(decoderInputs)) {
      val decoderInputLength = decoderInputs.head.length
      val useLastIdOnly = useCache && (decoderProcessor.nPredictedTokens > 0)
      val sequenceLength = if (useLastIdOnly) 1 else decoderInputLength

      val decoderInputBuffers =
        tensorDecoder.createIntBuffer(decoderInputs.length * decoderInputLength)

      decoderInputs.zipWithIndex.foreach { case (pieceIds, idx) =>
        val offset = idx * sequenceLength
        decoderInputBuffers.offset(offset).write(if (useLastIdOnly) pieceIds.takeRight(1) else pieceIds)
      }

      val decoderInputTensors = tensorDecoder.createIntBufferTensor(
        Array(decoderInputs.length.toLong, sequenceLength),
        decoderInputBuffers)

      val runner = if (nextStateTensor1.isEmpty || nextStateTensor2.isEmpty){
//        val r = session.runner
//          .feed(decoderInitInputIdsKey, decoderInputTensors)
//          .feed(decoderInitEncoderStateKey, decoderEncoderStateTensors)
//          .feed(decoderInitEncoderAttentionMaskKey, encoderAttentionMaskTensors)
//          .fetch(decoderInitOutputLogitsKey)
        val r = session.runner
          .feed(
            _tfT5Signatures.getOrElse(
              ModelSignatureConstants.DecoderInputIds.key,
              "missing_decoder_input_ids"),
            decoderInputTensors)
          .feed(
            _tfT5Signatures.getOrElse(
              ModelSignatureConstants.DecoderAttentionMask.key,
              "missing_encoder_attention_mask"),
            encoderAttentionMaskTensors)
          .feed(
            _tfT5Signatures.getOrElse(
              ModelSignatureConstants.DecoderEncoderInputIds.key,
              "missing_encoder_state"),
            decoderEncoderStateTensors)
          .feed(
            _tfT5Signatures.getOrElse(
              ModelSignatureConstants.DecoderEncoderAttentionMask.key,
              "missing_decoder_encoder_attention_mask"),
            encoderAttentionMaskTensors)
          .fetch(_tfT5Signatures
            .getOrElse(ModelSignatureConstants.DecoderOutput.key, "missing_output_0"))

        if (!useCache)
          r else r
          .fetch(decoderInitOutputCache1Key)
          .fetch(decoderInitOutputCache2Key)
      } else {
        session.runner
          .feed(decoderCachedInputIdsKey, decoderInputTensors)
          .feed(decoderCachedEncoderStateKey, decoderEncoderStateTensors)
          .feed(decoderCachedEncoderAttentionMaskKey, encoderAttentionMaskTensors)
          .feed(decoderCachedCache1Key, nextStateTensor1.get)
          .feed(decoderCachedCache2Key, nextStateTensor2.get)
          .fetch(decoderCachedOutputLogitsKey)
          .fetch(decoderCachedOutputCache1Key)
          .fetch(decoderCachedOutputCache2Key)
      }

      val decoderOuts = runner.run().asScala

      val logitsRaw = TensorResources.extractFloats(decoderOuts.head)
      decoderOuts.head.close()

      if (useCache) {
        if (nextStateTensor1.isDefined){
          nextStateTensor1.get.close()
        }
        if (nextStateTensor2.isDefined){
          nextStateTensor2.get.close()
        }
        nextStateTensor1 = Some(decoderOuts(1).asRawTensor())
        nextStateTensor2 = Some(decoderOuts(2).asRawTensor())
      }

      val decoderOutputs  = (0 until batchSize).map(i => {
        logitsRaw
          .slice(
            i * sequenceLength * vocabSize + (sequenceLength - 1) * vocabSize,
            i * sequenceLength * vocabSize + sequenceLength * vocabSize)
      })

      decoderInputs = decoderProcessor.processLogits(
        batchLogits = decoderOutputs.toArray, decoderInputIds = decoderInputs)

      decoderInputTensors.close()

      if (!useCache) {
        tensorDecoder.clearSession(decoderOuts)
        tensorDecoder.clearTensors()
      }
    }

    tensorDecoder.clearTensors()

    decoderInputs
  }

}