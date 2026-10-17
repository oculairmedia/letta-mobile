package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.runtime.local.modelcatalog.EMBEDDED_MODEL_CATALOG_ASSET
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-by4vm.121: what the loaded on-device model can take, as the model catalog says.
 * [supportsImages] is null for a model the catalog does not list (an imported file): unknown.
 */
data class OnDeviceModelCapability(val supportsImages: Boolean?)

fun interface OnDeviceModelCapabilities {
    fun forSelection(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceModelCapability

    companion object {
        /** No catalog: nothing is known about the model. */
        val Unknown = OnDeviceModelCapabilities { OnDeviceModelCapability(supportsImages = null) }
    }
}

/** Reads the bundled catalog (model_allowlist.json) once. */
@Singleton
class CatalogOnDeviceModelCapabilities @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OnDeviceModelCapabilities {
    private val entries: List<EmbeddedModelCatalogEntry> by lazy {
        runCatching {
            context.assets.open(EMBEDDED_MODEL_CATALOG_ASSET).bufferedReader().use {
                EmbeddedModelCatalogParser().parse(it.readText())
            }
        }.getOrDefault(emptyList())
    }

    override fun forSelection(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceModelCapability =
        catalogCapability(entries, modelSelection)
}

/** The catalog entry of [modelSelection], by its handle or its model file's name. */
internal fun catalogCapability(
    entries: List<EmbeddedModelCatalogEntry>,
    modelSelection: EmbeddedLettaCodeModelSelection,
): OnDeviceModelCapability {
    val fileName = modelSelection.modelPath?.trim()?.takeIf { it.isNotBlank() }?.let { File(it).name }
    val entry = entries.firstOrNull { it.modelId.equals(modelSelection.modelHandle.trim(), ignoreCase = true) }
        ?: entries.firstOrNull { fileName != null && it.modelFile.equals(fileName, ignoreCase = true) }
    return OnDeviceModelCapability(supportsImages = entry?.llmSupportImage)
}
