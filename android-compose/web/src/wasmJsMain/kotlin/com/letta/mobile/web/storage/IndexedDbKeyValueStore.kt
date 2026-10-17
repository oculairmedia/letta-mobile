@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.storage

import com.letta.mobile.data.storage.KeyValueStore
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The web's [KeyValueStore]: one IndexedDB object store of text values. The database opens on
 * first use; where IndexedDB is missing or refused (some private windows) every call throws, and
 * the shared stores over it report that as a storage fault and keep working from memory.
 */
class IndexedDbKeyValueStore(
    private val databaseName: String = DEFAULT_DATABASE,
    private val storeName: String = DEFAULT_STORE,
) : KeyValueStore {
    private val opening = Mutex()
    private var database: IdbDatabase? = null

    private suspend fun database(): IdbDatabase = opening.withLock {
        database ?: open().also { database = it }
    }

    private suspend fun open(): IdbDatabase {
        val factory = checkNotNull(browserIndexedDb()) { "IndexedDB is not available" }
        val request = factory.open(databaseName, SCHEMA_VERSION)
        request.onupgradeneeded = {
            val created = request.result?.unsafeCast<IdbDatabase>()
            if (created != null && !created.objectStoreNames.contains(storeName)) created.createObjectStore(storeName)
        }
        return checkNotNull(request.completion().await<JsAny?>()) { "IndexedDB opened no database" }.unsafeCast()
    }

    private suspend fun objectStore(mode: String): IdbObjectStore = database().transaction(storeName, mode).objectStore(storeName)

    override suspend fun get(key: String): String? =
        objectStore(READ_ONLY).get(key).completion().await<JsAny?>()?.unsafeCast<JsString>()?.toString()

    override suspend fun put(key: String, value: String) {
        objectStore(READ_WRITE).put(value.toJsString(), key).completion().await<JsAny?>()
    }

    override suspend fun remove(key: String) {
        objectStore(READ_WRITE).delete(key).completion().await<JsAny?>()
    }

    override suspend fun keys(prefix: String): List<String> {
        val stored = objectStore(READ_ONLY).getAllKeys().completion().await<JsAny?>() ?: return emptyList()
        val all = stored.unsafeCast<JsArray<JsString>>()
        return List(all.length) { index -> all[index].toString() }.filter { it.startsWith(prefix) }
    }

    companion object {
        const val DEFAULT_DATABASE: String = "letta-web"
        const val DEFAULT_STORE: String = "documents"
        private const val SCHEMA_VERSION = 1
        private const val READ_ONLY = "readonly"
        private const val READ_WRITE = "readwrite"
    }
}

/** The request's result once it succeeds; its error (or a generic one) when it fails. */
private fun IdbRequest.completion(): Promise<JsAny?> = Promise { resolve, reject ->
    onsuccess = { resolve(result) }
    onerror = { reject(error ?: "IndexedDB request failed".toJsString()) }
}

private fun browserIndexedDb(): IdbFactory? = js("typeof indexedDB === 'undefined' ? null : indexedDB")

private external interface IdbFactory : JsAny {
    fun open(name: String, version: Int): IdbOpenRequest
}

private external interface IdbRequest : JsAny {
    val result: JsAny?
    val error: JsAny?
    var onsuccess: ((JsAny) -> Unit)?
    var onerror: ((JsAny) -> Unit)?
}

private external interface IdbOpenRequest : IdbRequest {
    var onupgradeneeded: ((JsAny) -> Unit)?
}

private external interface IdbStringList : JsAny {
    fun contains(name: String): Boolean
}

private external interface IdbDatabase : JsAny {
    val objectStoreNames: IdbStringList

    fun createObjectStore(name: String): JsAny

    fun transaction(storeName: String, mode: String): IdbTransaction
}

private external interface IdbTransaction : JsAny {
    fun objectStore(name: String): IdbObjectStore
}

private external interface IdbObjectStore : JsAny {
    fun get(key: String): IdbRequest

    fun put(value: JsString, key: String): IdbRequest

    fun delete(key: String): IdbRequest

    fun getAllKeys(): IdbRequest
}
