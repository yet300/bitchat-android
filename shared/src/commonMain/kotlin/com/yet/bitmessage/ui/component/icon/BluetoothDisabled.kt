package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BluetoothDisabled: ImageVector
    get() {
        if (_BluetoothDisabled != null) {
            return _BluetoothDisabled!!
        }
        _BluetoothDisabled = ImageVector.Builder(
            name = "BluetoothDisabled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(792f, 904f)
                lineTo(624f, 736f)
                lineTo(480f, 880f)
                horizontalLineToRelative(-40f)
                verticalLineToRelative(-304f)
                lineTo(256f, 760f)
                lineToRelative(-56f, -56f)
                lineToRelative(196f, -196f)
                lineTo(56f, 168f)
                lineToRelative(56f, -56f)
                lineToRelative(736f, 736f)
                lineToRelative(-56f, 56f)
                close()
                moveTo(520f, 726f)
                lineToRelative(46f, -46f)
                lineToRelative(-46f, -46f)
                verticalLineToRelative(92f)
                close()
                moveTo(564f, 452f)
                lineTo(508f, 396f)
                lineTo(596f, 308f)
                lineTo(520f, 234f)
                verticalLineToRelative(174f)
                lineToRelative(-80f, -80f)
                verticalLineToRelative(-248f)
                horizontalLineToRelative(40f)
                lineToRelative(228f, 228f)
                lineToRelative(-144f, 144f)
                close()
            }
        }.build()

        return _BluetoothDisabled!!
    }

@Suppress("ObjectPropertyName")
private var _BluetoothDisabled: ImageVector? = null
