package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val DoneAll: ImageVector
    get() {
        if (_DoneAll != null) {
            return _DoneAll!!
        }
        _DoneAll = ImageVector.Builder(
            name = "DoneAll",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(268f, 720f)
                lineTo(42f, 494f)
                lineToRelative(57f, -56f)
                lineToRelative(170f, 170f)
                lineToRelative(56f, 56f)
                lineToRelative(-57f, 56f)
                close()
                moveTo(494f, 720f)
                lineTo(268f, 494f)
                lineToRelative(56f, -57f)
                lineToRelative(170f, 170f)
                lineToRelative(368f, -368f)
                lineToRelative(56f, 57f)
                lineToRelative(-424f, 424f)
                close()
                moveTo(494f, 494f)
                lineTo(437f, 438f)
                lineTo(635f, 240f)
                lineTo(692f, 296f)
                lineTo(494f, 494f)
                close()
            }
        }.build()

        return _DoneAll!!
    }

@Suppress("ObjectPropertyName")
private var _DoneAll: ImageVector? = null
