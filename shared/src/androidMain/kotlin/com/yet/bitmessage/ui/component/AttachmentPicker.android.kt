package com.yet.bitmessage.ui.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPickedState = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) { materialize(context, uri) } ?: return@launch
            onPickedState.value(attachment)
        }
    }
    return { launcher.launch("*/*") }
}

/** Copy the picked content into the app cache and describe it as a domain [Attachment]. */
private fun materialize(context: Context, uri: Uri): Attachment? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    var name = "attachment"
    var size: Long? = null
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
        }
    }
    val dest = File(context.cacheDir, "send_${System.currentTimeMillis()}_${name.substringAfterLast('/')}")
    return runCatching {
        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        Attachment(
            kind = kindFor(mime),
            ref = dest.absolutePath,
            mime = mime,
            sizeBytes = size ?: dest.length(),
        )
    }.getOrNull()
}

private fun kindFor(mime: String?): AttachmentKind = when {
    mime?.startsWith("image/") == true -> AttachmentKind.IMAGE
    mime?.startsWith("audio/") == true -> AttachmentKind.AUDIO
    else -> AttachmentKind.FILE
}
