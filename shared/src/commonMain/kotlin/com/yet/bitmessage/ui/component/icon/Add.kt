package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Add: ImageVector
    get() {
        if (_Add != null) {
            return _Add!!
        }
        _Add = ImageVector.Builder(
            name = "Add",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(440f, 440f)
                verticalLineTo(200f)
                horizontalLineTo(520f)
                verticalLineTo(440f)
                horizontalLineTo(760f)
                verticalLineTo(520f)
                horizontalLineTo(520f)
                verticalLineTo(760f)
                horizontalLineTo(440f)
                verticalLineTo(520f)
                horizontalLineTo(200f)
                verticalLineTo(440f)
                close()
            }
        }.build()
        return _Add!!
    }

private var _Add: ImageVector? = null
