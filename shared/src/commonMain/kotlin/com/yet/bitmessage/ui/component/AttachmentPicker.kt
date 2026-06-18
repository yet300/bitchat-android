package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import com.app.domain.model.Attachment

/**
 * Remembers a system attachment picker; the returned lambda launches it. On selection the picked
 * content is materialised into a local file and reported via [onPicked]. Picking is platform-
 * inherent, so this is an `expect`/`actual` — the Android actual uses GetContent + a cache copy.
 */
@Composable
expect fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit): () -> Unit
