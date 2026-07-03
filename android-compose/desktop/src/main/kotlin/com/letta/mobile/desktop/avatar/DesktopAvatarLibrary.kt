package com.letta.mobile.desktop.avatar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.avatar.catalog.AvatarCatalog
import com.letta.mobile.avatar.catalog.AvatarCatalogCodec
import com.letta.mobile.avatar.catalog.JsonFileAvatarCatalogStore
import com.letta.mobile.avatar.core.AvatarLicense
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.pipeline.AvatarImportRequest
import com.letta.mobile.avatar.pipeline.AvatarImportResult
import com.letta.mobile.avatar.pipeline.AvatarImporter
import com.letta.mobile.avatar.pipeline.resolveCatalogUri
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopMaterialTheme
import com.letta.mobile.desktop.DesktopOutlinedButton
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default on-disk location of the desktop avatar catalog. */
fun defaultAvatarCatalogDir(): Path =
    Path.of(System.getProperty("user.home"), ".letta-mobile", "avatars")

/**
 * The local avatar library (spec MVP step 9): imported avatars with their
 * captured license/provenance on display, an importer entry point, and
 * per-avatar activation of the companion. Imports run through the full
 * pipeline — detection, license gate, structural inspection, manifest,
 * catalog registration — so everything shown here is already normalized.
 */
@Composable
fun DesktopAvatarLibraryWindow(
    catalogDir: Path,
    activeModelId: String?,
    onUseAvatar: (model: AvatarModel, assetPath: Path) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val catalog = remember(catalogDir) {
        AvatarCatalog(JsonFileAvatarCatalogStore(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME)))
    }
    val entries by catalog.entries.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(catalog) {
        runCatching { catalog.refresh() }
            .onFailure { statusMessage = it.message ?: "Could not read the avatar catalog" }
    }

    val importLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("vrm", "glb", "gltf")),
        mode = FileKitMode.Single,
        dialogSettings = FileKitDialogSettings(title = "Import avatar (.vrm / .glb)"),
    ) { file ->
        if (file != null) {
            importing = true
            statusMessage = null
            scope.launch {
                val result = runCatching {
                    val source = file.file.toPath()
                    AvatarImporter(catalogDir).import(
                        AvatarImportRequest(
                            sourceFileName = source.fileName.toString(),
                            bytes = withContext(Dispatchers.IO) { Files.readAllBytes(source) },
                            // License is unknown at drag-in time -> the policy
                            // keeps the asset local/private and records why.
                            license = AvatarLicense(sourceUrl = source.toUri().toString()),
                        ),
                    )
                }.getOrElse { AvatarImportResult.Rejected(it.message ?: "Import failed") }
                statusMessage = when (result) {
                    is AvatarImportResult.Imported ->
                        "Imported ${result.model.displayName}" +
                            (result.warnings.firstOrNull()?.let { " — $it" } ?: "")
                    is AvatarImportResult.Rejected -> "Rejected: ${result.reason}"
                }
                runCatching { catalog.refresh() }
                importing = false
            }
        }
    }

    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(560.dp, 480.dp)),
        title = "Avatar library",
        undecorated = true,
        resizable = false,
    ) {
        DesktopMaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Avatar library",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Open formats only — VRM / glTF. Author in VRoid Studio or Blender.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DesktopDefaultButton(onClick = { if (!importing) importLauncher.launch() }) {
                            DesktopControlText(if (importing) "Importing…" else "Import avatar")
                        }
                    }

                    statusMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    if (entries.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No avatars yet. Import a .vrm (e.g. a VRoid Studio export) to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries, key = { it.id }) { model ->
                                AvatarLibraryRow(
                                    model = model,
                                    isActive = model.id == activeModelId,
                                    onUse = {
                                        runCatching { resolveCatalogUri(catalogDir, model.uri) }
                                            .onSuccess { path -> onUseAvatar(model, path) }
                                            .onFailure { statusMessage = it.message }
                                    },
                                    onRemove = {
                                        scope.launch {
                                            runCatching { catalog.remove(model.id) }
                                            statusMessage = "Removed ${model.displayName} from the library (files kept)"
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DesktopOutlinedButton(onClick = onClose) { DesktopControlText("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarLibraryRow(
    model: AvatarModel,
    isActive: Boolean,
    onUse: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (isActive) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    text = licenseSummary(model.license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                model.sha256?.let {
                    Text(
                        text = "${model.format} · ${it.take(12)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DesktopOutlinedButton(onClick = onRemove) { DesktopControlText("Remove") }
            DesktopDefaultButton(onClick = onUse) { DesktopControlText(if (isActive) "Active" else "Use") }
        }
    }
}

/** License facts, honestly: unknown stays visibly unknown. */
private fun licenseSummary(license: AvatarLicense): String {
    val name = license.licenseName ?: "License unknown"
    val redistribution = when (license.allowRedistribution) {
        true -> "redistribution allowed"
        false -> "no redistribution"
        null -> "local/private only"
    }
    val creator = license.creator?.let { " · by $it" } ?: ""
    return "$name · $redistribution$creator"
}
