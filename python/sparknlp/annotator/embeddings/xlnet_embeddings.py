#  Copyright 2017-2022 John Snow Labs
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
"""Contains classes for XlnetEmbeddings."""

from sparknlp.common import *


class XlnetEmbeddings(AnnotatorModel,
                      HasEmbeddingsProperties,
                      HasCaseSensitiveProperties,
                      HasStorageRef,
                      HasBatchedAnnotate,
                      HasEngine):
    """XlnetEmbeddings (XLNet): Generalized Autoregressive Pretraining for
    Language Understanding

    XLNet is a new unsupervised language representation learning method based on
    a novel generalized permutation language modeling objective. Additionally,
    XLNet employs Transformer-XL as the backbone model, exhibiting excellent
    performance for language tasks involving long context. Overall, XLNet
    achieves state-of-the-art (SOTA) results on various downstream language
    tasks including question answering, natural language inference, sentiment
    analysis, and document ranking.

    These word embeddings represent the outputs generated by the XLNet models.

    - ``"xlnet_large_cased"`` (`XLNet-Large
      <https://storage.googleapis.com/xlnet/released_models/cased_L-24_H-1024_A-16.zip>`__):
      24-layer, 1024-hidden, 16-heads

    - ``"xlnet_base_cased"`` (`XLNet-Base
      <https://storage.googleapis.com/xlnet/released_models/cased_L-12_H-768_A-12.zip>`__):
      12-layer, 768-hidden, 12-heads. This model is trained on full data
      (different from the one in the paper).

    Pretrained models can be loaded with :meth:`.pretrained` of the companion
    object:

    >>> embeddings = XlnetEmbeddings.pretrained() \\
    ...     .setInputCols(["sentence", "token"]) \\
    ...     .setOutputCol("embeddings")

    The default model is ``"xlnet_base_cased"``, if no name is provided.

    For extended examples of usage, see the `Examples
    <https://github.com/JohnSnowLabs/spark-nlp/blob/master/example/python/training/english/dl-ner/ner_xlnet.ipynb>`__.
    To see which models are compatible and how to import them see
    `Import Transformers into Spark NLP 🚀
    <https://github.com/JohnSnowLabs/spark-nlp/discussions/5669>`_.

    ====================== ======================
    Input Annotation types Output Annotation type
    ====================== ======================
    ``DOCUMENT, TOKEN``    ``WORD_EMBEDDINGS``
    ====================== ======================

    Parameters
    ----------
    batchSize
        Size of every batch, by default 8
    dimension
        Number of embedding dimensions, by default 768
    caseSensitive
        Whether to ignore case in tokens for embeddings matching, by default
        True
    configProtoBytes
        ConfigProto from tensorflow, serialized into byte array.
    maxSentenceLength
        Max sentence length to process, by default 128

    Notes
    -----
    This is a very computationally expensive module compared to word embedding
    modules that only perform embedding lookups. The use of an accelerator is
    recommended.

    References
    ----------
    `XLNet: Generalized Autoregressive Pretraining for Language Understanding
    <https://arxiv.org/abs/1906.08237>`__

    https://github.com/zihangdai/xlnet

    **Paper abstract:**

    *With the capability of modeling bidirectional contexts, denoising
    autoencoding based pretraining like BERT achieves better performance than
    pretraining approaches based on autoregressive language modeling. However,
    relying on corrupting the input with masks, BERT neglects dependency between
    the masked positions and suffers from a pretrain-finetune discrepancy. In
    light of these pros and cons, we propose XLNet, a generalized autoregressive
    pretraining method that (1) enables learning bidirectional contexts by
    maximizing the expected likelihood over all permutations of the
    factorization order and (2) overcomes the limitations of BERT thanks to its
    autoregressive formulation. Furthermore, XLNet integrates ideas from
    Transformer-XL, the state-of-the-art autoregressive model, into pretraining.
    Empirically, under comparable experiment settings, XLNet outperforms BERT on
    20 tasks, often by a large margin, including question answering, natural
    language inference, sentiment analysis, and document ranking.*

    Examples
    --------
    >>> import sparknlp
    >>> from sparknlp.base import *
    >>> from sparknlp.annotator import *
    >>> from pyspark.ml import Pipeline
    >>> documentAssembler = DocumentAssembler() \\
    ...     .setInputCol("text") \\
    ...     .setOutputCol("document")
    >>> tokenizer = Tokenizer() \\
    ...     .setInputCols(["document"]) \\
    ...     .setOutputCol("token")
    >>> embeddings = XlnetEmbeddings.pretrained() \\
    ...     .setInputCols(["token", "document"]) \\
    ...     .setOutputCol("embeddings")
    >>> embeddingsFinisher = EmbeddingsFinisher() \\
    ...     .setInputCols(["embeddings"]) \\
    ...     .setOutputCols("finished_embeddings") \\
    ...     .setOutputAsVector(True) \\
    ...     .setCleanAnnotations(False)
    >>> pipeline = Pipeline().setStages([
    ...     documentAssembler,
    ...     tokenizer,
    ...     embeddings,
    ...     embeddingsFinisher
    ... ])
    >>> data = spark.createDataFrame([["This is a sentence."]]).toDF("text")
    >>> result = pipeline.fit(data).transform(data)
    >>> result.selectExpr("explode(finished_embeddings) as result").show(5, 80)
    +--------------------------------------------------------------------------------+
    |                                                                          result|
    +--------------------------------------------------------------------------------+
    |[-0.6287205219268799,-0.4865287244319916,-0.186111718416214,0.234187275171279...|
    |[-1.1967450380325317,0.2746637463569641,0.9481253027915955,0.3431355059146881...|
    |[-1.0777631998062134,-2.092679977416992,-1.5331977605819702,-1.11190271377563...|
    |[-0.8349916934967041,-0.45627787709236145,-0.7890847325325012,-1.028069257736...|
    |[-0.134845569729805,-0.11672890186309814,0.4945235550403595,-0.66587203741073...|
    +--------------------------------------------------------------------------------+
    """

    name = "XlnetEmbeddings"

    inputAnnotatorTypes = [AnnotatorType.DOCUMENT, AnnotatorType.TOKEN]

    outputAnnotatorType = AnnotatorType.WORD_EMBEDDINGS

    configProtoBytes = Param(Params._dummy(),
                             "configProtoBytes",
                             "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()",
                             TypeConverters.toListInt)

    maxSentenceLength = Param(Params._dummy(),
                              "maxSentenceLength",
                              "Max sentence length to process",
                              typeConverter=TypeConverters.toInt)

    def setConfigProtoBytes(self, b):
        """Sets configProto from tensorflow, serialized into byte array.

        Parameters
        ----------
        b : List[int]
            ConfigProto from tensorflow, serialized into byte array
        """
        return self._set(configProtoBytes=b)

    def setMaxSentenceLength(self, value):
        """Sets max sentence length to process.

        Parameters
        ----------
        value : int
            Max sentence length to process
        """
        return self._set(maxSentenceLength=value)

    @keyword_only
    def __init__(self, classname="com.johnsnowlabs.nlp.embeddings.XlnetEmbeddings", java_model=None):
        super(XlnetEmbeddings, self).__init__(
            classname=classname,
            java_model=java_model
        )
        self._setDefault(
            batchSize=8,
            dimension=768,
            maxSentenceLength=128,
            caseSensitive=True
        )

    @staticmethod
    def loadSavedModel(folder, spark_session):
        """Loads a locally saved model.

        Parameters
        ----------
        folder : str
            Folder of the saved model
        spark_session : pyspark.sql.SparkSession
            The current SparkSession

        Returns
        -------
        XlnetEmbeddings
            The restored model
        """
        from sparknlp.internal import _XlnetLoader
        jModel = _XlnetLoader(folder, spark_session._jsparkSession)._java_obj
        return XlnetEmbeddings(java_model=jModel)

    @staticmethod
    def pretrained(name="xlnet_base_cased", lang="en", remote_loc=None):
        """Downloads and loads a pretrained model.

        Parameters
        ----------
        name : str, optional
            Name of the pretrained model, by default "xlnet_base_cased"
        lang : str, optional
            Language of the pretrained model, by default "en"
        remote_loc : str, optional
            Optional remote address of the resource, by default None. Will use
            Spark NLPs repositories otherwise.

        Returns
        -------
        XlnetEmbeddings
            The restored model
        """
        from sparknlp.pretrained import ResourceDownloader
        return ResourceDownloader.downloadModel(XlnetEmbeddings, name, lang, remote_loc)
