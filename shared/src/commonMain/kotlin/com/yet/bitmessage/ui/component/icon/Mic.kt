package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Mic: ImageVector
    get() {
        if (_Mic != null) return _Mic!!
        _Mic = ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // Microphone capsule body
                moveTo(480f, 520f)
                quadToRelative(-50f, 0f, -85f, -35f)
                reflectiveQuadToRelative(-35f, -85f)
                verticalLineToRelative(-200f)
                quadToRelative(0f, -50f, 35f, -85f)
                reflectiveQuadToRelative(85f, -35f)
                quadToRelative(50f, 0f, 85f, 35f)
                reflectiveQuadToRelative(35f, 85f)
                verticalLineToRelative(200f)
                quadToRelative(0f, 50f, -35f, 85f)
                reflectiveQuadToRelative(-85f, 35f)
                close()
                // Stand arc + base
                moveTo(440f, 840f)
                verticalLineToRelative(-123f)
                quadToRelative(-121f, -15f, -200f, -100.5f)
                reflectiveQuadTo(160f, 400f)
                horizontalLineToRelative(80f)
                quadToRelative(0f, 100f, 70f, 170f)
                reflectiveQuadToRelative(170f, 70f)
                quadToRelative(100f, 0f, 170f, -70f)
                reflectiveQuadToRelative(70f, -170f)
                horizontalLineToRelative(80f)
                quadToRelative(0f, 116f, -79f, 196.5f)
                reflectiveQuadTo(520f, 717f)
                verticalLineToRelative(123f)
                horizontalLineToRelative(-80f)
                close()
            }
        }.build()
        return _Mic!!
    }

@Suppress("ObjectPropertyName")
private var _Mic: ImageVector? = null
