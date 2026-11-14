package com.loomplay.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loomplay.core.model.EngineType
import com.loomplay.core.ui.theme.LoomColors
import com.loomplay.core.ui.theme.TimecodeTextStyle
import com.loomplay.core.ui.theme.tabular

@Composable
fun EngineBadgePill(
    engine: EngineType,
    label: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = if (engine == EngineType.EXOPLAYER) LoomColors.ExoEmerald else LoomColors.MpvCyan
    val text = label ?: if (engine == EngineType.EXOPLAYER) "EXO" else "MPV"

    Surface(
        modifier = modifier
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CircleShape,
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
    }
}

@Composable
fun TechBadgeChip(
    text: String,
    accentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = accentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Color.White,
    containerColor: Color = Color.Transparent,
    showBorder: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = containerColor,
        border = if (showBorder) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun LoomScrubberBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapterFractions: List<Float> = emptyList(),
) {
    val hasDuration = durationMs > 0L
    val safeDuration = durationMs.coerceAtLeast(1L)
    val actualProgress = if (hasDuration) (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedProgress = if (hasDuration) (bufferedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) else 0f

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(actualProgress) }
    var widthPx by remember { mutableFloatStateOf(1f) }

    val displayedFraction = if (isDragging && hasDuration) dragFraction else actualProgress

    val trackBgColor = remember { Color.White.copy(alpha = 0.22f) }
    val bufferedColor = remember { Color.White.copy(alpha = 0.40f) }
    val progressColor = LoomColors.PrimaryContainer
    val chapterNotchColor = remember { Color.Black.copy(alpha = 0.7f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .then(
                if (hasDuration) {
                    Modifier
                        .pointerInput(safeDuration) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                onSeekTo((fraction * safeDuration).toLong())
                            }
                        }
                        .pointerInput(safeDuration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    onSeekTo((dragFraction * safeDuration).toLong())
                                    isDragging = false
                                },
                                onDragCancel = { isDragging = false },
                            )
                        }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val trackHeight = if (isDragging) 6.dp.toPx() else 4.dp.toPx()
                val topY = (size.height - trackHeight) / 2f
                val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)

                // 1. Base Track
                drawRoundRect(
                    color = trackBgColor,
                    topLeft = Offset(0f, topY),
                    size = Size(size.width, trackHeight),
                    cornerRadius = corner,
                )

                // 2. Buffered Progress
                if (bufferedProgress > 0f) {
                    drawRoundRect(
                        color = bufferedColor,
                        topLeft = Offset(0f, topY),
                        size = Size(size.width * bufferedProgress, trackHeight),
                        cornerRadius = corner,
                    )
                }

                // 3. Playback Progress
                if (displayedFraction > 0f) {
                    drawRoundRect(
                        color = progressColor,
                        topLeft = Offset(0f, topY),
                        size = Size(size.width * displayedFraction, trackHeight),
                        cornerRadius = corner,
                    )
                }

                // 4. Chapter notches
                val notchWidth = 2.dp.toPx()
                val notchHeight = 6.dp.toPx()
                val notchTopY = (size.height - notchHeight) / 2f
                chapterFractions.forEach { frac ->
                    val clamped = frac.coerceIn(0.02f, 0.98f)
                    val notchX = size.width * clamped - (notchWidth / 2f)
                    drawRect(
                        color = chapterNotchColor,
                        topLeft = Offset(notchX, notchTopY),
                        size = Size(notchWidth, notchHeight),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Scrubber Thumb with lambda-based graphicsLayer offset for 60fps/120fps hardware movement without recomposing
        val thumbRadiusPx = if (isDragging) 9.dp else 6.5.dp
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val thumbW = (thumbRadiusPx * 2).toPx()
                    translationX = ((widthPx - thumbW) * displayedFraction).coerceAtLeast(0f)
                }
                .size(if (isDragging) 18.dp else 13.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (isDragging) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(LoomColors.PrimaryContainer),
            )
        }
    }
}

@Composable
fun GestureCapsuleIndicator(
    icon: ImageVector,
    percent: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = LoomColors.SurfaceLowest.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(104.dp)
                    .clip(CircleShape)
                    .background(LoomColors.SurfaceHighest),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((percent / 100f).coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(accentColor),
                )
            }
            Text(
                text = "$percent%",
                style = TimecodeTextStyle.tabular().copy(fontSize = 11.sp),
                color = LoomColors.OnSurface,
            )
        }
    }
}

fun formatTimecode(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

