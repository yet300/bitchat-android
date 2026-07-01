package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.app.common.ioDispatcher
import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write

/**
 * Remembers a system attachment picker; the returned lambda launches it. On selection the picked
 * content is materialised into a local cache file and reported via [onPicked].
 *
 * Platform-free now (commonMain): FileKit provides the picker and the byte read across platforms;
 * the picked bytes are copied into a real file under the temp dir so [Attachment.ref] stays a path
 * the mesh layer can read via kotlinx-io. (FileKit self-initialises on Android via AndroidX App
 * Startup and ships its own FileProvider through manifest merge — no app-side wiring needed.)
 */
@Composable
fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val onPickedState = rememberUpdatedState(onPicked)
    val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val attachment = materialize(file) ?: return@launch
            onPickedState.value(attachment)
        }
    }
    return { launcher.launch() }
}

/** Copy the picked content into a cache file and describe it as a domain [Attachment]. */
private suspend fun materialize(file: PlatformFile): Attachment? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val mime = file.mimeType()
    return withContext(ioDispatcher) {
        runCatching {
            val dir = Path(SystemTemporaryDirectory, "bitchat_send")
            SystemFileSystem.createDirectories(dir)
            val dest = Path(dir, "send_${Clock.System.now().toEpochMilliseconds()}_${file.name}")
            SystemFileSystem.sink(dest).buffered().use { it.write(bytes) }
            Attachment(
                kind = kindFor(mime?.primaryType),
                ref = dest.toString(),
                mime = mime?.toString(),
                sizeBytes = file.size().takeIf { it >= 0 } ?: bytes.size.toLong(),
            )
        }.getOrNull()
    }
}

private fun kindFor(primaryType: String?): AttachmentKind = when (primaryType?.lowercase()) {
    "image" -> AttachmentKind.IMAGE
    "audio" -> AttachmentKind.AUDIO
    else -> AttachmentKind.FILE
}
