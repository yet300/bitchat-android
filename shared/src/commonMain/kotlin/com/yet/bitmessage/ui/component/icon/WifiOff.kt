package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val WifiOff: ImageVector
    get() {
        if (_WifiOff != null) {
            return _WifiOff!!
        }
        _WifiOff = ImageVector.Builder(
            name = "WifiOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(790f, 904f)
                lineTo(414f, 526f)
                quadToRelative(-47f, 11f, -87.5f, 33f)
                reflectiveQuadTo(254f, 614f)
                lineToRelative(-84f, -86f)
                quadToRelative(32f, -32f, 69f, -56f)
                reflectiveQuadToRelative(79f, -42f)
                lineToRelative(-90f, -90f)
                quadToRelative(-41f, 21f, -76.5f, 46.5f)
                reflectiveQuadTo(84f, 444f)
                lineTo(0f, 358f)
                quadToRelative(32f, -32f, 66.5f, -57.5f)
                reflectiveQuadTo(140f, 252f)
                lineToRelative(-84f, -84f)
                lineToRelative(56f, -56f)
                lineToRelative(736f, 736f)
                lineToRelative(-58f, 56f)
                close()
                moveTo(409f, 810.5f)
                quadTo(380f, 781f, 380f, 740f)
                quadToRelative(0f, -42f, 29f, -71f)
                reflectiveQuadToRelative(71f, -29f)
                quadToRelative(42f, 0f, 71f, 29f)
                reflectiveQuadToRelative(29f, 71f)
                quadToRelative(0f, 41f, -29f, 70.5f)
                reflectiveQuadTo(480f, 840f)
                quadToRelative(-42f, 0f, -71f, -29.5f)
                close()
                moveTo(716f, 602f)
                lineToRelative(-29f, -29f)
                lineToRelative(-29f, -29f)
                lineToRelative(-144f, -144f)
                quadToRelative(81f, 8f, 151.5f, 41f)
                reflectiveQuadTo(790f, 528f)
                lineToRelative(-74f, 74f)
                close()
                moveTo(876f, 444f)
                quadToRelative(-77f, -77f, -178.5f, -120.5f)
                reflectiveQuadTo(480f, 280f)
                quadToRelative(-21f, 0f, -40.5f, 1.5f)
                reflectiveQuadTo(400f, 286f)
                lineTo(298f, 184f)
                quadToRelative(44f, -12f, 89.5f, -18f)
                reflectiveQuadToRelative(92.5f, -6f)
                quadToRelative(142f, 0f, 265f, 53f)
                reflectiveQuadToRelative(215f, 145f)
                lineToRelative(-84f, 86f)
                close()
            }
        }.build()

        return _WifiOff!!
    }

@Suppress("ObjectPropertyName")
private var _WifiOff: ImageVector? = null
