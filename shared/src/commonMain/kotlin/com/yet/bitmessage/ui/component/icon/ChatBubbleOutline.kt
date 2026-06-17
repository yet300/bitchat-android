package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val ChatBubbleOutline: ImageVector
    get() {
        if (_ChatBubbleOutline != null) {
            return _ChatBubbleOutline!!
        }
        _ChatBubbleOutline = ImageVector.Builder(
            name = "ChatBubbleOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(80f, 880f)
                verticalLineToRelative(-720f)
                quadToRelative(0f, -33f, 23.5f, -56.5f)
                reflectiveQuadTo(160f, 80f)
                horizontalLineToRelative(640f)
                quadToRelative(33f, 0f, 56.5f, 23.5f)
                reflectiveQuadTo(880f, 160f)
                verticalLineToRelative(480f)
                quadToRelative(0f, 33f, -23.5f, 56.5f)
                reflectiveQuadTo(800f, 720f)
                lineTo(240f, 720f)
                lineTo(80f, 880f)
                close()
                moveTo(206f, 640f)
                horizontalLineToRelative(594f)
                verticalLineToRelative(-480f)
                lineTo(160f, 160f)
                verticalLineToRelative(525f)
                lineToRelative(46f, -45f)
                close()
                moveTo(160f, 640f)
                verticalLineToRelative(-480f)
                verticalLineToRelative(480f)
                close()
            }
        }.build()

        return _ChatBubbleOutline!!
    }

@Suppress("ObjectPropertyName")
private var _ChatBubbleOutline: ImageVector? = null
