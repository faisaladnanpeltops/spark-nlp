package com.johnsnowlabs.client.azure

import com.johnsnowlabs.client.{CloudClient, CloudStorage}
import com.johnsnowlabs.util.{ConfigHelper, ConfigLoader}

class AzureClient(parameters: Map[String, String] = Map.empty) extends CloudClient {

  private lazy val azureStorageConnection = cloudConnect()

  override protected def cloudConnect(): CloudStorage = {
    val azureStorageAccountName = parameters.getOrElse(
      "storageAccountName",
      ConfigLoader.getConfigStringValue(ConfigHelper.azureStorageAccountName))
    new AzureGateway(azureStorageAccountName)
  }

  override def doesBucketPathExist(bucketName: String, filePath: String): Boolean = {
    azureStorageConnection.doesBucketPathExist(bucketName, filePath)
  }

  override def copyInputStreamToBucket(bucketName: String, filePath: String, sourceFilePath: String): Unit = {
    azureStorageConnection.copyInputStreamToBucket(bucketName, filePath, sourceFilePath)
  }

  override def downloadFilesFromBucketToDirectory(bucketName: String, filePath: String, directoryPath: String, isIndex: Boolean): Unit = {
    azureStorageConnection.downloadFilesFromBucketToDirectory(bucketName, filePath, directoryPath, isIndex)
  }

}
