package com.yet.bitmessage.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * iOS live QR scanning (AVCaptureSession + Vision) is not implemented yet — this stub renders a
 * placeholder instead of a viewfinder so the scan screen stays navigable. Follow-up alongside the
 * other iOS media actuals.
 */
@Composable
actual fun CameraScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "QR scanning is not available on iOS yet",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
