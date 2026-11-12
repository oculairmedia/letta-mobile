package com.letta.mobile.data.voice.wake.assets

import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline

/** The id of one engine asset, for example a model file's whitelisted name. */
@JvmInline
value class WakeAssetId(
    /** The id. */
    val value: String,
)

/** A pinned SHA-256 digest, as 64 lowercase hexadecimal characters. */
@JvmInline
value class Sha256Digest(
    /** The digest. */
    val hex: String,
) {
    init {
        require(hex.length == HEX_LENGTH && hex.all { it in HEX_DIGITS }) { "Not a lowercase SHA-256 digest" }
    }

    private companion object {
        const val HEX_LENGTH = 64
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

/** One file an engine needs: its id, exact size and pinned digest. */
data class WakeWordAsset(
    /** The asset's id. */
    val id: WakeAssetId,
    /** Its exact size in bytes. */
    val byteSize: Long,
    /** Its pinned digest, verified before use. */
    val sha256: Sha256Digest,
)

/** Read access to one verified asset. The engine reads bytes through it and never opens files itself. */
interface WakeAssetHandle {
    /** The asset this handle reads. */
    val asset: WakeWordAsset

    /** The verified bytes. */
    suspend fun bytes(): ByteArray
}

/** Verified assets ready for an engine to open. */
class ReadyWakeWordAssets(
    /** The handles, by asset id. */
    val handles: Map<WakeAssetId, WakeAssetHandle> = emptyMap(),
) {
    /** The handle for [id], or null when it is not provisioned. */
    fun handle(id: WakeAssetId): WakeAssetHandle? = handles[id]

    /** True when every one of [required] is provisioned with the same size and digest. */
    fun covers(required: List<WakeWordAsset>): Boolean = required.all { handles[it.id]?.asset == it }

    override fun toString(): String = "ReadyWakeWordAssets(count=${handles.size})"
}

/** Download progress from 0 to 1. */
@JvmInline
value class AssetProgress(
    /** The fraction done. */
    val fraction: Float,
)

/** Why provisioning failed. */
enum class WakeAssetFailure {
    /** The wake word is not enabled; nothing may be downloaded. */
    NOT_ENABLED,

    /** A requested asset is not on the whitelist. */
    NOT_WHITELISTED,

    /** The download failed. */
    DOWNLOAD_FAILED,

    /** The downloaded bytes did not match the pinned size or digest; they were discarded. */
    DIGEST_MISMATCH,

    /** The assets could not be stored. */
    STORAGE_FAILED,
}

/** Where provisioning stands. */
sealed interface WakeWordAssetState {
    /** Nothing is provisioned. */
    data object Missing : WakeWordAssetState

    /** A download is under way. */
    data class Downloading(
        /** How far along. */
        val progress: AssetProgress,
    ) : WakeWordAssetState

    /** Every requested asset is verified and ready. */
    data class Ready(
        /** The verified assets. */
        val assets: ReadyWakeWordAssets,
    ) : WakeWordAssetState

    /** Provisioning failed. */
    data class Failed(
        /** Why. */
        val failure: WakeAssetFailure,
    ) : WakeWordAssetState
}

/**
 * Provisions an engine's model files, separately from the engine.
 *
 * Contract:
 * - Assets are never bundled with the app. Nothing is downloaded until the person has enabled the
 *   wake word; before that [ensure] reports [WakeAssetFailure.NOT_ENABLED].
 * - Only whitelisted asset ids are fetched; anything else is [WakeAssetFailure.NOT_WHITELISTED].
 * - Every file is written to a temporary name, checked against its pinned size and SHA-256, and
 *   renamed atomically into place; a mismatch is discarded and reported as
 *   [WakeAssetFailure.DIGEST_MISMATCH]. Nothing unverified is ever handed to an engine.
 * - No audio, phrase or account data is sent while provisioning.
 */
interface WakeWordAssets {
    /** Where provisioning stands. */
    val state: StateFlow<WakeWordAssetState>

    /** Provisions [required] (downloading when allowed) and returns the resulting state. */
    suspend fun ensure(required: List<WakeWordAsset>): WakeWordAssetState

    /** Deletes every provisioned asset. */
    suspend fun remove()
}
