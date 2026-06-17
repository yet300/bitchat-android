package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SettingsBluetooth: ImageVector
    get() {
        if (_SettingsBluetooth != null) {
            return _SettingsBluetooth!!
        }
        _SettingsBluetooth = ImageVector.Builder(
            name = "SettingsBluetooth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(291.5f, 948.5f)
                quadTo(280f, 937f, 280f, 920f)
                reflectiveQuadToRelative(11.5f, -28.5f)
                quadTo(303f, 880f, 320f, 880f)
                reflectiveQuadToRelative(28.5f, 11.5f)
                quadTo(360f, 903f, 360f, 920f)
                reflectiveQuadToRelative(-11.5f, 28.5f)
                quadTo(337f, 960f, 320f, 960f)
                reflectiveQuadToRelative(-28.5f, -11.5f)
                close()
                moveTo(451.5f, 948.5f)
                quadTo(440f, 937f, 440f, 920f)
                reflectiveQuadToRelative(11.5f, -28.5f)
                quadTo(463f, 880f, 480f, 880f)
                reflectiveQuadToRelative(28.5f, 11.5f)
                quadTo(520f, 903f, 520f, 920f)
                reflectiveQuadToRelative(-11.5f, 28.5f)
                quadTo(497f, 960f, 480f, 960f)
                reflectiveQuadToRelative(-28.5f, -11.5f)
                close()
                moveTo(611.5f, 948.5f)
                quadTo(600f, 937f, 600f, 920f)
                reflectiveQuadToRelative(11.5f, -28.5f)
                quadTo(623f, 880f, 640f, 880f)
                reflectiveQuadToRelative(28.5f, 11.5f)
                quadTo(680f, 903f, 680f, 920f)
                reflectiveQuadToRelative(-11.5f, 28.5f)
                quadTo(657f, 960f, 640f, 960f)
                reflectiveQuadToRelative(-28.5f, -11.5f)
                close()
                moveTo(440f, 800f)
                verticalLineToRelative(-304f)
                lineTo(256f, 680f)
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
                lineToRelative(-228f, 228f)
                horizontalLineToRelative(-40f)
                close()
                moveTo(520f, 646f)
                lineTo(596f, 572f)
                lineTo(520f, 496f)
                verticalLineToRelative(150f)
                close()
                moveTo(520f, 304f)
                lineTo(596f, 228f)
                lineTo(520f, 154f)
                verticalLineToRelative(150f)
                close()
            }
        }.build()

        return _SettingsBluetooth!!
    }

@Suppress("ObjectPropertyName")
private var _SettingsBluetooth: ImageVector? = null
