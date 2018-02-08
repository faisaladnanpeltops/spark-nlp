package com.johnsnowlabs.util

import scala.collection.JavaConversions.enumerationAsScalaIterator
import scala.io.BufferedSource
import scala.io.Codec
import java.io.{File, FileInputStream, FileOutputStream, IOException}
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}

/**
  * Copied from https://github.com/dhbikoff/Scala-Zip-Archive-Util
  */
object ZipArchiveUtil {

  private def listFiles(file: File, outputFilename: String): List[String] = {
    file match {
      case file if file.isFile => {
        if (file.getName != outputFilename)
          List(file.getAbsoluteFile.toString)
        else
          List()
      }
      case file if file.isDirectory => {
        val fList = file.list
        // Add all files in current dir to list and recur on subdirs
        fList.foldLeft(List[String]())((pList: List[String], path: String) =>
          pList ++ listFiles(new File(file, path), outputFilename))
      }
      case _ => throw new IOException("Bad path. No file or directory found.")
    }
  }

  private def addFileToZipEntry(filename: String, parentPath: String,
                        filePathsCount: Int): ZipEntry = {
    if (filePathsCount <= 1)
      new ZipEntry(new File(filename).getName)
    else {
      // use relative path to avoid adding absolute path directories
      val relative = new File(parentPath).toURI.
        relativize(new File(filename).toURI).getPath
      new ZipEntry(relative)
    }
  }

  private def createZip(filePaths: List[String], outputFilename: String, parentPath: String) = {
    try {
      val fileOutputStream = new FileOutputStream(outputFilename)
      val zipOutputStream = new ZipOutputStream(fileOutputStream)

      filePaths.foreach((name: String) => {
        val zipEntry = addFileToZipEntry(name, parentPath, filePaths.size)
        zipOutputStream.putNextEntry(zipEntry)
        val inputSrc = new BufferedSource(
          new FileInputStream(name))(Codec.ISO8859)
        inputSrc foreach { c: Char => zipOutputStream.write(c) }
        inputSrc.close
      })

      zipOutputStream.closeEntry
      zipOutputStream.close
      fileOutputStream.close

    } catch {
      case e: IOException => {
        e.printStackTrace
      }
    }
  }

  def zip(fileName: String, outputFileName: String): Unit = {
    val file = new File(fileName)
    val filePaths = listFiles(file, outputFileName)
    createZip(filePaths, outputFileName, fileName)
  }

  def unzip(file: File): String = {
    val basename = file.getName.substring(0, file.getName.lastIndexOf("."))
    val destDir = new File(file.getParentFile, basename)
    destDir.mkdirs

    val zip = new ZipFile(file)
    zip.entries foreach { entry =>
      val entryName = entry.getName
      val entryPath = {
        if (entryName.startsWith(basename))
          entryName.substring(basename.length)
        else
          entryName
      }

      // create output directory if it doesn't exist already
      val splitPath = entry.getName.split(File.separator).dropRight(1)
      if (splitPath.size >= 1) {
        // create intermediate directories if they don't exist
        val dirBuilder = new StringBuilder(destDir.getName)
        splitPath.foldLeft(dirBuilder)( (a: StringBuilder, b: String) => {
          val path = a.append(File.separator + b)
          val str = path.mkString
          if (!(new File(str).exists)) {
            new File(str).mkdir
          }
          path
        })
      }

      // write file to dest
      val inputSrc = new BufferedSource(
        zip.getInputStream(entry))(Codec.ISO8859)
      val ostream = new FileOutputStream(new File(destDir, entryPath))
      inputSrc foreach { c: Char => ostream.write(c) }
      inputSrc.close
      ostream.close

    }

    destDir.getPath
  }
}