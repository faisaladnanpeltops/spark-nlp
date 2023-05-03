/*
 * Copyright 2017-2022 John Snow Labs
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
package com.johnsnowlabs.client

import com.amazonaws.services.s3.model.S3Object
import com.johnsnowlabs.client.aws.{AWSClient, AWSGateway}
import com.johnsnowlabs.client.gcp.GCPClient
import com.johnsnowlabs.client.util.CloudHelper
import com.johnsnowlabs.util.{ConfigHelper, ConfigLoader}
import org.apache.commons.io.IOUtils

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.Paths
import java.util.zip.ZipInputStream

object CloudResources {

  def downloadFromCloud(
      awsGateway: AWSGateway,
      cachePath: String,
      modelName: String,
      sourceS3URI: String): Option[String] = {

    val (sourceBucketName, sourceKey) = CloudHelper.parseS3URI(sourceS3URI)
    val zippedModel = awsGateway.getS3Object(sourceBucketName, sourceKey)

    val cloudManager = new CloudManager()
    val clientInstance = cloudManager.getClientInstance(cachePath)

    clientInstance match {
      case awsClient: AWSClient => {
        val destinationS3URI = cachePath.replace("s3:", "s3a:")
        val modelExists =
          doesModelExistInExternalCloudStorage(modelName, destinationS3URI, awsClient)

        if (!modelExists) {
          val destinationKey =
            unzipInExternalCloudStorage(sourceKey, destinationS3URI, awsClient, zippedModel)
          Option(destinationKey)
        } else {
          Option(destinationS3URI + "/" + modelName)
        }
      }
      case gcpClient: GCPClient => {

        val modelExists =
          doesModelExistInExternalCloudStorage(modelName, cachePath, gcpClient)

        if (!modelExists) {
          val destination =
            unzipInExternalCloudStorage(sourceS3URI, cachePath, gcpClient, zippedModel)
          Option(destination)
        } else {
          Option(cachePath + "/" + modelName)
        }
      }
    }

  }

  private def doesModelExistInExternalCloudStorage(
      modelName: String,
      destinationURI: String,
      cloudClient: CloudClient): Boolean = {

    cloudClient match {
      case awsDestinationClient: AWSClient => {
        val (destinationBucketName, destinationKey) = CloudHelper.parseS3URI(destinationURI)

        val modelPath = destinationKey + "/" + modelName

        awsDestinationClient.doesBucketPathExist(destinationBucketName, modelPath)
      }
      case gcpClient: GCPClient => {
        val (destinationBucketName, destinationStoragePath) =
          CloudHelper.parseGCPStorageURI(destinationURI)
        val modelPath = destinationStoragePath + "/" + modelName

        gcpClient.doesBucketPathExist(destinationBucketName, modelPath)
      }
    }

  }

  private def unzipInExternalCloudStorage(
      sourceKey: String,
      destinationStorageURI: String,
      cloudClient: CloudClient,
      zippedModel: S3Object): String = {

    val zipInputStream = new ZipInputStream(zippedModel.getObjectContent)
    var zipEntry = zipInputStream.getNextEntry

    val zipFile = sourceKey.split("/").last
    val modelName = zipFile.substring(0, zipFile.indexOf(".zip"))

    println(s"Uploading model $modelName to external Cloud Storage URI: $destinationStorageURI")
    while (zipEntry != null) {
      if (!zipEntry.isDirectory) {
        val outputStream = new ByteArrayOutputStream()
        IOUtils.copy(zipInputStream, outputStream)
        val inputStream = new ByteArrayInputStream(outputStream.toByteArray)

        cloudClient match {
          case awsClient: AWSClient => {
            val (awsGatewayDestination, destinationBucketName, destinationKey) = getS3Config(
              destinationStorageURI)
            val fileName = s"$modelName/${zipEntry.getName}"
            val destinationS3Path = destinationKey + "/" + fileName

            awsGatewayDestination.copyFileToBucket(
              destinationBucketName,
              destinationS3Path,
              inputStream)
          }
          case gcpClient: GCPClient => {
            val (destinationBucketName, destinationStoragePath) =
              CloudHelper.parseGCPStorageURI(destinationStorageURI)

            val destinationGCPStoragePath =
              s"$destinationStoragePath/$modelName/${zipEntry.getName}"

            gcpClient.copyFileToBucket(
              destinationBucketName,
              destinationGCPStoragePath,
              inputStream)
          }
        }

      }
      zipEntry = zipInputStream.getNextEntry
    }
    destinationStorageURI + "/" + modelName
  }

  private def getS3Config(destinationS3URI: String): (AWSClient, String, String) = {
    val cloudManager = new CloudManager()
    val clientInstance = cloudManager.getClientInstance(destinationS3URI)

    val (destinationBucketName, destinationKey) = CloudHelper.parseS3URI(destinationS3URI)
    (clientInstance.asInstanceOf[AWSClient], destinationBucketName, destinationKey)
  }

  def storeLogFileInCloudStorage(outputLogsPath: String, targetPath: String): Unit = {
    val parameters = Map("credentialsType" -> "proprietary")
    val cloudManager = new CloudManager(parameters)
    val logsPath = if (outputLogsPath.nonEmpty) outputLogsPath else getLogsFolder
    val clientInstance = cloudManager.getClientInstance(logsPath)

    clientInstance match {
      case awsClient: AWSClient => storeLogFileInS3(outputLogsPath, targetPath, awsClient)
      case gcpClient: GCPClient => storeLogFileInGCPStorage(outputLogsPath, targetPath, gcpClient)
    }
  }

  private def storeLogFileInS3(
      outputLogsPath: String,
      targetPath: String,
      awsClient: AWSClient): Unit = {

    def parseConfigS3Path(): (String, String) = {
      val s3Bucket = ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalS3BucketKey)
      val s3Path = ConfigLoader.getConfigStringValue(ConfigHelper.annotatorLogFolder) + "/"
      (s3Bucket, s3Path)
    }

    val logsPathSuffix = outputLogsPath.takeWhile(_ != ':')
    val (s3Bucket, s3Path) = logsPathSuffix match {
      case "s3" | "s3a" => CloudHelper.parseS3URI(outputLogsPath, includePrefixInKey = true)
      case _ if getLogsFolder.startsWith("s3") || getLogsFolder.startsWith("s3a") =>
        parseConfigS3Path()
      case _ => throw new IllegalArgumentException("Unsupported outputLogsPath")
    }

    val s3FilePath = s"""${s3Path.substring("s3://".length)}/${targetPath.split("/").last}"""
    awsClient.copyInputStreamToBucket(s3Bucket, s3FilePath, targetPath)
  }

  private def getLogsFolder: String =
    ConfigLoader.getConfigStringValue(ConfigHelper.annotatorLogFolder)

  private def storeLogFileInGCPStorage(
      outputLogsPath: String,
      targetPath: String,
      gcpClient: GCPClient): Unit = {
    val (gcpBucket, storagePath) = CloudHelper.parseGCPStorageURI(outputLogsPath)
    val fileName = Paths.get(targetPath).getFileName.toString
    val destinationPath = s"$storagePath/$fileName"
    gcpClient.copyInputStreamToBucket(gcpBucket, destinationPath, targetPath)
  }

}
