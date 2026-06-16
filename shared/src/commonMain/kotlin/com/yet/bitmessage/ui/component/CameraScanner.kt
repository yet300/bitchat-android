package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Live camera QR scanner. [onScanned] is invoked with each decoded QR string (the caller
 * de-duplicates / stops scanning). Camera access is platform-inherent, so this is an
 * `expect`/`actual` — the Android actual uses CameraX + ML Kit. Render only after CAMERA is granted.
 */
@Composable
expect fun CameraScanner(onScanned: (String) -> Unit, modifier: Modifier)
