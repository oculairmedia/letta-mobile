package com.letta.mobile.data.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderAgentsListParams
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderFileUploadParams
import com.letta.mobile.data.model.FolderFilesListParams
import com.letta.mobile.data.model.FolderListParams
import com.letta.mobile.data.model.FolderPassagesListParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class FolderApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : com.letta.mobile.data.repository.api.FolderRemoteSource {
    open override suspend fun countFolders(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun retrieveFolder(folderId: String): Folder {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/$folderId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun retrieveFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/metadata") {
            parameter("include_detailed_per_source_metadata", includeDetailedPerSourceMetadata)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listFolders(params: FolderListParams): List<Folder> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/") {
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("limit", params.limit)
            parameter("order", params.order)
            parameter("name", params.name)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun createFolder(params: FolderCreateParams): Folder {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/folders/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/folders/$folderId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deleteFolder(folderId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/folders/$folderId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun uploadFileToFolder(params: FolderFileUploadParams): FileMetadata {
        val (client, baseUrl) = apiClient.session()

        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/v1/folders/${params.folderId.value}/upload",
            formData = formData {
                append("file", params.fileBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${params.fileName}\"")
                    append(HttpHeaders.ContentType, params.contentType.toString())
                })
            },
        ) {
            parameter("duplicate_handling", params.duplicateHandling)
            parameter("name", params.customName)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listAgentsForFolder(params: FolderAgentsListParams): List<String> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/${params.folderId.value}/agents") {
            parameter("limit", params.limit)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("order", params.order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listFolderPassages(params: FolderPassagesListParams): List<Passage> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/${params.folderId.value}/passages") {
            parameter("limit", params.limit)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("order", params.order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listFolderFiles(params: FolderFilesListParams): List<FileMetadata> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/${params.folderId.value}/files") {
            parameter("limit", params.limit)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("order", params.order)
            parameter("include_content", params.includeContent)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deleteFileFromFolder(folderId: String, fileId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/folders/$folderId/$fileId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
