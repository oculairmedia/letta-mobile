package com.letta.mobile.data.api

import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
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
) {
    open suspend fun countFolders(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveFolder(folderId: String): Folder {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/$folderId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveFolderMetadata(includeDetailedPerSourceMetadata: Boolean = false): OrganizationSourcesStats {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/metadata") {
            parameter("include_detailed_per_source_metadata", includeDetailedPerSourceMetadata)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listFolders(
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        name: String? = null,
    ): List<Folder> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
            parameter("name", name)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createFolder(params: FolderCreateParams): Folder {
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

    open suspend fun updateFolder(folderId: String, params: FolderUpdateParams): Folder {
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

    open suspend fun deleteFolder(folderId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/folders/$folderId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun uploadFileToFolder(
        folderId: String,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String? = null,
        customName: String? = null,
        contentType: ContentType = ContentType.Application.OctetStream,
    ): FileMetadata {
        val (client, baseUrl) = apiClient.session()

        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/v1/folders/$folderId/upload",
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType.toString())
                })
            },
        ) {
            parameter("duplicate_handling", duplicateHandling)
            parameter("name", customName)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listAgentsForFolder(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<String> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/$folderId/agents") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listFolderPassages(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Passage> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/$folderId/passages") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listFolderFiles(
        folderId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
        includeContent: Boolean? = null,
    ): List<FileMetadata> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/folders/$folderId/files") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
            parameter("include_content", includeContent)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteFileFromFolder(folderId: String, fileId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/folders/$folderId/$fileId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
