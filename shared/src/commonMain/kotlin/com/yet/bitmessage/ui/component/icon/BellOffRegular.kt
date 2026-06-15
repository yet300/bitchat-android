package com.yet.bitmessage.ui.component.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BellOffRegular: ImageVector
    get() {
        if (_BellOffRegular != null) {
            return _BellOffRegular!!
        }
        _BellOffRegular = ImageVector.Builder(
            name = "BellOffRegular",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 22f)
                arcToRelative(2.98f, 2.98f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2.818f, -2f)
                lineTo(9.182f, 20f)
                arcTo(2.98f, 2.98f, 0f, isMoreThanHalf = false, isPositiveArc = false, 12f, 22f)
                close()
                moveTo(21f, 18f)
                verticalLineToRelative(-2f)
                arcToRelative(0.996f, 0.996f, 0f, isMoreThanHalf = false, isPositiveArc = false, -0.293f, -0.707f)
                lineTo(19f, 13.586f)
                lineTo(19f, 10f)
                curveToRelative(0f, -3.217f, -2.185f, -5.927f, -5.145f, -6.742f)
                curveTo(13.562f, 2.52f, 12.846f, 2f, 12f, 2f)
                reflectiveCurveToRelative(-1.562f, 0.52f, -1.855f, 1.258f)
                curveToRelative(-1.323f, 0.364f, -2.463f, 1.128f, -3.346f, 2.127f)
                lineTo(3.707f, 2.293f)
                lineTo(2.293f, 3.707f)
                lineToRelative(18f, 18f)
                lineToRelative(1.414f, -1.414f)
                lineToRelative(-1.362f, -1.362f)
                arcTo(0.993f, 0.993f, 0f, isMoreThanHalf = false, isPositiveArc = false, 21f, 18f)
                close()
                moveTo(12f, 5f)
                curveToRelative(2.757f, 0f, 5f, 2.243f, 5f, 5f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.266f, 0.105f, 0.52f, 0.293f, 0.707f)
                lineTo(19f, 16.414f)
                lineTo(19f, 17f)
                horizontalLineToRelative(-0.586f)
                lineTo(8.207f, 6.793f)
                curveTo(9.12f, 5.705f, 10.471f, 5f, 12f, 5f)
                close()
                moveTo(6.707f, 14.707f)
                arcTo(0.996f, 0.996f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7f, 14f)
                verticalLineToRelative(-2.879f)
                lineTo(5.068f, 9.189f)
                curveTo(5.037f, 9.457f, 5f, 9.724f, 5f, 10f)
                verticalLineToRelative(3.586f)
                lineToRelative(-1.707f, 1.707f)
                arcTo(0.996f, 0.996f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3f, 16f)
                verticalLineToRelative(2f)
                arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1f, 1f)
                horizontalLineToRelative(10.879f)
                lineToRelative(-2f, -2f)
                lineTo(5f, 17f)
                verticalLineToRelative(-0.586f)
                lineToRelative(1.707f, -1.707f)
                close()
            }
        }.build()

        return _BellOffRegular!!
    }

@Suppress("ObjectPropertyName")
private var _BellOffRegular: ImageVector? = null
