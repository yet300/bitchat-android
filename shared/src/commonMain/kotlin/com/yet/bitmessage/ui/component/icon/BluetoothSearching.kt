package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BluetoothSearching: ImageVector
    get() {
        if (_BluetoothSearching != null) {
            return _BluetoothSearching!!
        }
        _BluetoothSearching = ImageVector.Builder(
            name = "BluetoothSearching",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(360f, 880f)
                verticalLineToRelative(-304f)
                lineTo(176f, 760f)
                lineToRelative(-56f, -56f)
                lineToRelative(224f, -224f)
                lineToRelative(-224f, -224f)
                lineToRelative(56f, -56f)
                lineToRelative(184f, 184f)
                verticalLineToRelative(-304f)
                horizontalLineToRelative(40f)
                lineToRelative(228f, 228f)
                lineToRelative(-172f, 172f)
                lineToRelative(172f, 172f)
                lineTo(400f, 880f)
                horizontalLineToRelative(-40f)
                close()
                moveTo(440f, 384f)
                lineTo(516f, 308f)
                lineTo(440f, 234f)
                verticalLineToRelative(150f)
                close()
                moveTo(440f, 726f)
                lineTo(516f, 652f)
                lineTo(440f, 576f)
                verticalLineToRelative(150f)
                close()
                moveTo(662f, 574f)
                lineTo(570f, 480f)
                lineTo(662f, 388f)
                quadToRelative(9f, 22f, 14.5f, 45f)
                reflectiveQuadToRelative(5.5f, 47f)
                quadToRelative(0f, 24f, -5.5f, 47.5f)
                reflectiveQuadTo(662f, 574f)
                close()
                moveTo(780f, 688f)
                lineTo(730f, 640f)
                quadToRelative(20f, -37f, 31f, -77.5f)
                reflectiveQuadToRelative(11f, -82.5f)
                quadToRelative(0f, -42f, -11f, -82.5f)
                reflectiveQuadTo(730f, 320f)
                lineToRelative(50f, -50f)
                quadToRelative(29f, 48f, 44.5f, 101f)
                reflectiveQuadTo(840f, 480f)
                quadToRelative(0f, 56f, -15.5f, 108.5f)
                reflectiveQuadTo(780f, 688f)
                close()
            }
        }.build()

        return _BluetoothSearching!!
    }

@Suppress("ObjectPropertyName")
private var _BluetoothSearching: ImageVector? = null
