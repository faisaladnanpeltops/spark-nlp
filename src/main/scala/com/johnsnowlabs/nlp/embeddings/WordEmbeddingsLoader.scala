package com.johnsnowlabs.nlp.embeddings

import java.io.{BufferedInputStream, ByteArrayOutputStream, DataInputStream, FileInputStream}

import com.johnsnowlabs.storage.{RocksDBConnection, StorageFormat, StorageLoader}
import org.slf4j.LoggerFactory

import scala.io.Source

object WordEmbeddingsLoader extends StorageLoader {

  override val formats: StorageFormat = EmbeddingsFormat

  override protected def index(filePath: String, format: formats.Value, connection: RocksDBConnection): Unit = {
    if (format == EmbeddingsFormat.TEXT) {
      WordEmbeddingsTextIndexer.index(filePath, connection)
    }
    else if (format == EmbeddingsFormat.BINARY) {
      WordEmbeddingsBinaryIndexer.index(filePath, connection)
    }
  }
}

object WordEmbeddingsTextIndexer {

  def index(
             source: Iterator[String],
             connection: RocksDBConnection,
             autoFlushAfter: Option[Integer]
           ): Unit = {
    val indexer = new WordEmbeddingsWriter(connection,autoFlushAfter)

    try {
      for (line <- source) {
        val items = line.split(" ")
        val word = items(0)
        val embeddings = items.drop(1).map(i => i.toFloat)
        indexer.add(word, embeddings)
      }
    } finally {
      indexer.close()
    }
  }

  def index(
             source: String,
             connection: RocksDBConnection,
             autoFlashAfter: Option[Integer] = Some(1000)
           ): Unit = {
    val sourceFile = Source.fromFile(source)("UTF-8")
    val lines = sourceFile.getLines()
    index(lines, connection, autoFlashAfter)
    sourceFile.close()
  }
}


object WordEmbeddingsBinaryIndexer {

  private val logger = LoggerFactory.getLogger("WordEmbeddings")

  def index(
             source: DataInputStream,
             connection: RocksDBConnection,
             autoFlashAfter: Option[Integer],
             lruCacheSize: Int): Unit = {
    val indexer = new WordEmbeddingsWriter(connection, autoFlashAfter)
    val reader = new WordEmbeddingsReader(connection, caseSensitiveIndex=true, lruCacheSize)

    try {
      // File Header
      val numWords = Integer.parseInt(readString(source))
      val vecSize = Integer.parseInt(readString(source))

      // File Body
      for (i <- 0 until numWords) {
        val word = readString(source)

        // Unit Vector
        val vector = readFloatVector(source, vecSize, reader)
        indexer.add(word, vector)
      }

      logger.info(s"Loaded $numWords words, vector size $vecSize")
    } finally {
      indexer.close()
    }
  }

  def index(
             source: String,
             connection: RocksDBConnection,
             autoFlashAfter: Option[Integer] = Some(1000),
             lruCacheSize: Int = 100000): Unit = {

    val ds = new DataInputStream(new BufferedInputStream(new FileInputStream(source), 1 << 15))

    try {
      index(ds, connection, autoFlashAfter, lruCacheSize)
    } finally {
      ds.close()
    }
  }

  /**
    * Read a string from the binary model (System default should be UTF-8):
    */
  private def readString(ds: DataInputStream): String = {
    val byteBuffer = new ByteArrayOutputStream()

    var isEnd = false
    while (!isEnd) {
      val byteValue = ds.readByte()
      if ((byteValue != 32) && (byteValue != 10)) {
        byteBuffer.write(byteValue)
      } else if (byteBuffer.size() > 0) {
        isEnd = true
      }
    }

    val word = byteBuffer.toString()
    byteBuffer.close()
    word
  }

  /**
    * Read a Vector - Array of Floats from the binary model:
    */
  private def readFloatVector(ds: DataInputStream, vectorSize: Int, indexer: WordEmbeddingsReader): Array[Float] = {
    // Read Bytes
    val vectorBuffer = Array.fill[Byte](4 * vectorSize)(0)
    ds.read(vectorBuffer)

    // Convert Bytes to Floats
    indexer.fromBytes(vectorBuffer)
  }
}
