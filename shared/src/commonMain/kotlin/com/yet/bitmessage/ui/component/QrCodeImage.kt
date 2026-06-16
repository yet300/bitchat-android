package com.yet.bitmessage.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Renders [payload] as a QR code via qrose — a Compose Multiplatform encoder, so no platform
 * QR/bitmap dependency crosses into commonMain.
 */
@Composable
fun QrCodeImage(
    payload: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 240.dp,
    contentDescription: String? = null,
) {
    Image(
        painter = rememberQrCodePainter(payload),
        contentDescription = contentDescription,
        modifier = modifier.size(sizeDp),
    )
}
