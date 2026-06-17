package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val NoAccounts: ImageVector
    get() {
        if (_NoAccounts != null) {
            return _NoAccounts!!
        }
        _NoAccounts = ImageVector.Builder(
            name = "NoAccounts",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(608f, 438f)
                lineTo(422f, 252f)
                quadToRelative(14f, -6f, 28.5f, -9f)
                reflectiveQuadToRelative(29.5f, -3f)
                quadToRelative(59f, 0f, 99.5f, 40.5f)
                reflectiveQuadTo(620f, 380f)
                quadToRelative(0f, 15f, -3f, 29.5f)
                reflectiveQuadToRelative(-9f, 28.5f)
                close()
                moveTo(234f, 684f)
                quadToRelative(51f, -39f, 114f, -61.5f)
                reflectiveQuadTo(480f, 600f)
                quadToRelative(18f, 0f, 34.5f, 1.5f)
                reflectiveQuadTo(549f, 606f)
                lineToRelative(-88f, -88f)
                quadToRelative(-47f, -6f, -80.5f, -39.5f)
                reflectiveQuadTo(341f, 398f)
                lineTo(227f, 284f)
                quadToRelative(-32f, 41f, -49.5f, 90.5f)
                reflectiveQuadTo(160f, 480f)
                quadToRelative(0f, 59f, 19.5f, 111f)
                reflectiveQuadToRelative(54.5f, 93f)
                close()
                moveTo(732f, 676f)
                quadToRelative(32f, -41f, 50f, -90.5f)
                reflectiveQuadTo(800f, 480f)
                quadToRelative(0f, -133f, -93.5f, -226.5f)
                reflectiveQuadTo(480f, 160f)
                quadToRelative(-56f, 0f, -105.5f, 18f)
                reflectiveQuadTo(284f, 228f)
                lineToRelative(448f, 448f)
                close()
                moveTo(325f, 848.5f)
                quadToRelative(-73f, -31.5f, -127.5f, -86f)
                reflectiveQuadToRelative(-86f, -127.5f)
                quadTo(80f, 562f, 80f, 479.5f)
                reflectiveQuadToRelative(31.5f, -155f)
                quadToRelative(31.5f, -72.5f, 86f, -127f)
                reflectiveQuadToRelative(127.5f, -86f)
                quadTo(398f, 80f, 480.5f, 80f)
                reflectiveQuadToRelative(155f, 31.5f)
                quadToRelative(72.5f, 31.5f, 127f, 86f)
                reflectiveQuadToRelative(86f, 127f)
                quadTo(880f, 397f, 880f, 479.5f)
                reflectiveQuadTo(848.5f, 635f)
                quadToRelative(-31.5f, 73f, -86f, 127.5f)
                reflectiveQuadToRelative(-127f, 86f)
                quadTo(563f, 880f, 480.5f, 880f)
                reflectiveQuadTo(325f, 848.5f)
                close()
                moveTo(480f, 800f)
                quadToRelative(53f, 0f, 100f, -15.5f)
                reflectiveQuadToRelative(86f, -44.5f)
                quadToRelative(-39f, -29f, -86f, -44.5f)
                reflectiveQuadTo(480f, 680f)
                quadToRelative(-53f, 0f, -100f, 15.5f)
                reflectiveQuadTo(294f, 740f)
                quadToRelative(39f, 29f, 86f, 44.5f)
                reflectiveQuadTo(480f, 800f)
                close()
                moveTo(480f, 740f)
                close()
            }
        }.build()

        return _NoAccounts!!
    }

@Suppress("ObjectPropertyName")
private var _NoAccounts: ImageVector? = null
