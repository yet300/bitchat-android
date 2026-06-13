package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Send: ImageVector
    get() {
        if (_Send != null) {
            return _Send!!
        }
        _Send = ImageVector.Builder(
            name = "Send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(120f, 800f)
                verticalLineToRelative(-640f)
                lineToRelative(760f, 320f)
                lineToRelative(-760f, 320f)
                close()
                moveTo(200f, 680f)
                lineTo(674f, 480f)
                lineTo(200f, 280f)
                verticalLineToRelative(140f)
                lineToRelative(240f, 60f)
                lineToRelative(-240f, 60f)
                verticalLineToRelative(140f)
                close()
                moveTo(200f, 680f)
                verticalLineToRelative(-400f)
                verticalLineToRelative(400f)
                close()
            }
        }.build()

        return _Send!!
    }

@Suppress("ObjectPropertyName")
private var _Send: ImageVector? = null
