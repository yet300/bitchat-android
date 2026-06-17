package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val LockPerson: ImageVector
    get() {
        if (_LockPerson != null) {
            return _LockPerson!!
        }
        _LockPerson = ImageVector.Builder(
            name = "LockPerson",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(762.5f, 702.5f)
                quadTo(780f, 685f, 780f, 660f)
                reflectiveQuadToRelative(-17.5f, -42.5f)
                quadTo(745f, 600f, 720f, 600f)
                reflectiveQuadToRelative(-42.5f, 17.5f)
                quadTo(660f, 635f, 660f, 660f)
                reflectiveQuadToRelative(17.5f, 42.5f)
                quadTo(695f, 720f, 720f, 720f)
                reflectiveQuadToRelative(42.5f, -17.5f)
                close()
                moveTo(776f, 826f)
                quadToRelative(26f, -14f, 43f, -39f)
                quadToRelative(-23f, -14f, -48f, -20.5f)
                reflectiveQuadToRelative(-51f, -6.5f)
                quadToRelative(-26f, 0f, -51f, 6.5f)
                reflectiveQuadTo(621f, 787f)
                quadToRelative(17f, 25f, 43f, 39f)
                reflectiveQuadToRelative(56f, 14f)
                quadToRelative(30f, 0f, 56f, -14f)
                close()
                moveTo(360f, 320f)
                horizontalLineToRelative(240f)
                verticalLineToRelative(-80f)
                quadToRelative(0f, -50f, -35f, -85f)
                reflectiveQuadToRelative(-85f, -35f)
                quadToRelative(-50f, 0f, -85f, 35f)
                reflectiveQuadToRelative(-35f, 85f)
                verticalLineToRelative(80f)
                close()
                moveTo(490f, 880f)
                lineTo(240f, 880f)
                quadToRelative(-33f, 0f, -56.5f, -23.5f)
                reflectiveQuadTo(160f, 800f)
                verticalLineToRelative(-400f)
                quadToRelative(0f, -33f, 23.5f, -56.5f)
                reflectiveQuadTo(240f, 320f)
                horizontalLineToRelative(40f)
                verticalLineToRelative(-80f)
                quadToRelative(0f, -83f, 58.5f, -141.5f)
                reflectiveQuadTo(480f, 40f)
                quadToRelative(83f, 0f, 141.5f, 58.5f)
                reflectiveQuadTo(680f, 240f)
                verticalLineToRelative(80f)
                horizontalLineToRelative(40f)
                quadToRelative(33f, 0f, 56.5f, 23.5f)
                reflectiveQuadTo(800f, 400f)
                verticalLineToRelative(52f)
                quadToRelative(-18f, -6f, -37.5f, -9f)
                reflectiveQuadToRelative(-42.5f, -3f)
                verticalLineToRelative(-40f)
                lineTo(240f, 400f)
                verticalLineToRelative(400f)
                horizontalLineToRelative(212f)
                quadToRelative(8f, 24f, 16f, 41.5f)
                reflectiveQuadTo(490f, 880f)
                close()
                moveTo(578.5f, 861.5f)
                quadTo(520f, 803f, 520f, 720f)
                reflectiveQuadToRelative(58.5f, -141.5f)
                quadTo(637f, 520f, 720f, 520f)
                reflectiveQuadToRelative(141.5f, 58.5f)
                quadTo(920f, 637f, 920f, 720f)
                reflectiveQuadTo(861.5f, 861.5f)
                quadTo(803f, 920f, 720f, 920f)
                reflectiveQuadTo(578.5f, 861.5f)
                close()
                moveTo(240f, 400f)
                verticalLineToRelative(400f)
                verticalLineToRelative(-400f)
                close()
            }
        }.build()

        return _LockPerson!!
    }

@Suppress("ObjectPropertyName")
private var _LockPerson: ImageVector? = null
