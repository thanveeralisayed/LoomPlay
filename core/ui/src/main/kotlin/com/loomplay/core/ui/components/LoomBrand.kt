package com.loomplay.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loomplay.core.ui.theme.LoomColors

private object LoomBrandPaths {
    val exoRibbon: Path = Path().apply {
        moveTo(160f, 170f)
        cubicTo(110f, 210f, 100f, 290f, 145f, 340f)
        cubicTo(190f, 390f, 270f, 385f, 320f, 330f)
        lineTo(375f, 270f)
        lineTo(320f, 200f)
        cubicTo(265f, 140f, 200f, 135f, 160f, 170f)
        close()
    }

    val mpvRibbon: Path = Path().apply {
        moveTo(210f, 165f)
        cubicTo(260f, 120f, 340f, 125f, 380f, 180f)
        cubicTo(420f, 235f, 410f, 310f, 355f, 355f)
        lineTo(290f, 345f)
        cubicTo(330f, 305f, 345f, 250f, 310f, 205f)
        cubicTo(275f, 165f, 230f, 160f, 210f, 165f)
        close()
    }

    val playGlass: Path = Path().apply {
        moveTo(226f, 182f)
        cubicTo(226f, 172f, 237f, 166f, 246f, 171f)
        lineTo(334f, 229f)
        cubicTo(343f, 234f, 343f, 246f, 334f, 252f)
        lineTo(246f, 310f)
        cubicTo(237f, 315f, 226f, 309f, 226f, 299f)
        close()
    }

    val playHighlight: Path = Path().apply {
        moveTo(246f, 171f)
        lineTo(334f, 229f)
        cubicTo(340f, 233f, 342f, 240f, 338f, 245f)
        lineTo(328f, 238f)
        lineTo(248f, 184f)
        close()
    }
}

@Composable
fun LoomPlayLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    showGlow: Boolean = false,
) {
    val exoBrush = remember {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFFA5B4FC),
                0.35f to Color(0xFF6366F1),
                1.0f to Color(0xFF4338CA),
            ),
            start = Offset(120f, 120f),
            end = Offset(380f, 400f),
        )
    }
    val mpvBrush = remember {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFF0891B2),
                0.5f to Color(0xFF06B6D4),
                1.0f to Color(0xFF38BDF8),
            ),
            start = Offset(160f, 380f),
            end = Offset(400f, 140f),
        )
    }
    val playBrush = remember {
        Brush.linearGradient(
            colors = listOf(Color.White, Color(0xFFE2E8F0)),
            start = Offset(210f, 180f),
            end = Offset(330f, 330f),
        )
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        if (showGlow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0x586366F1),
                        0.6f to Color(0x2006B6D4),
                        1.0f to Color.Transparent,
                    ),
                    center = center,
                    radius = this.size.minDimension * 0.52f,
                ),
                radius = this.size.minDimension * 0.52f,
                center = center,
            )
        }

        val scale = this.size.minDimension / (if (showGlow) 355f else 315f)
        withTransform({
            translate(left = this.size.width / 2f, top = this.size.height / 2f)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            translate(left = -258f, top = -252f)
        }) {
            drawPath(path = LoomBrandPaths.exoRibbon, brush = exoBrush)
            drawPath(
                path = LoomBrandPaths.mpvRibbon,
                brush = mpvBrush,
                alpha = 0.90f,
                blendMode = BlendMode.Screen,
            )
            drawPath(path = LoomBrandPaths.playGlass, brush = playBrush)
            drawPath(path = LoomBrandPaths.playHighlight, color = Color.White, alpha = 0.40f)
        }
    }
}

@Composable
fun LoomPlayWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
) {
    val loomColor = MaterialTheme.colorScheme.onSurface
    val playColor = LoomColors.PrimaryScrub
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = loomColor)) { append("Loom") }
            withStyle(SpanStyle(color = playColor)) { append("Play") }
        },
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun LoomPlayBrandLockup(
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
    subtitle: String? = null,
    showGlow: Boolean = subtitle != null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoomPlayLogoMark(
            size = iconSize,
            showGlow = showGlow,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LoomPlayWordmark(
                style = if (subtitle != null) {
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    )
                } else {
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                    )
                },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

