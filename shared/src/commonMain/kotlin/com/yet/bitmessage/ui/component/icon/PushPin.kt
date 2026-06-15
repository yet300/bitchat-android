package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val PushPin: ImageVector
    get() {
        if (_PushPin != null) {
            return _PushPin!!
        }
        _PushPin = ImageVector.Builder(
            name = "PushPin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveToRelative(640f, 480f)
                lineToRelative(80f, 80f)
                verticalLineToRelative(80f)
                lineTo(520f, 640f)
                verticalLineToRelative(240f)
                lineToRelative(-40f, 40f)
                lineToRelative(-40f, -40f)
                verticalLineToRelative(-240f)
                lineTo(240f, 640f)
                verticalLineToRelative(-80f)
                lineToRelative(80f, -80f)
                verticalLineToRelative(-280f)
                horizontalLineToRelative(-40f)
                verticalLineToRelative(-80f)
                horizontalLineToRelative(400f)
                verticalLineToRelative(80f)
                horizontalLineToRelative(-40f)
                verticalLineToRelative(280f)
                close()
                moveTo(354f, 560f)
                horizontalLineToRelative(252f)
                lineToRelative(-46f, -46f)
                verticalLineToRelative(-314f)
                lineTo(400f, 200f)
                verticalLineToRelative(314f)
                lineToRelative(-46f, 46f)
                close()
                moveTo(480f, 560f)
                close()
            }
        }.build()

        return _PushPin!!
    }

@Suppress("ObjectPropertyName")
private var _PushPin: ImageVector? = null
