package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import java.util.Locale

@Composable
fun HairlineNocCard(
    modifier: Modifier = Modifier,
    borderColor: Color = LocalNocColors.current.hairlineBorder,
    containerColor: Color = LocalNocColors.current.cardSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun LedStatusBadge(
    label: String,
    dotColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .border(1.dp, dotColor.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor
        )
    }
}

@Composable
fun RadialCpuGauge(
    percent: Float,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp
) {
    val noc = LocalNocColors.current
    val animatedPercent by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 100f),
        animationSpec = spring(stiffness = 220f),
        label = "cpu_gauge_anim"
    )
    val gaugeColor = when {
        animatedPercent >= 90f -> noc.critical
        animatedPercent >= 70f -> noc.warning
        else -> noc.healthy
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 10.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = noc.elevatedSurface,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = gaugeColor.copy(alpha = 0.22f),
                startAngle = 135f,
                sweepAngle = 270f * (animatedPercent / 100f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx + 4.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = gaugeColor,
                startAngle = 135f,
                sweepAngle = 270f * (animatedPercent / 100f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "%.0f%%", animatedPercent),
                style = NocMonoStyles.metricHero,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "CPU",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SparklineChart(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    secondaryValues: List<Float>? = null,
    secondaryColor: Color = LocalNocColors.current.warning,
    maxValueOverride: Float? = null
) {
    val noc = LocalNocColors.current
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Subtle horizontal NOC gridlines
        val gridLines = 3
        for (i in 1..gridLines) {
            val y = h * (i / (gridLines + 1f))
            drawLine(
                color = noc.hairlineBorder.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        val combinedMax = maxValueOverride ?: maxOf(
            values.maxOrNull() ?: 1f,
            secondaryValues?.maxOrNull() ?: 0f,
            1f
        )

        fun drawSeries(series: List<Float>, color: Color, fillArea: Boolean) {
            if (series.isEmpty()) return
            val effectivePoints = if (series.size == 1) listOf(series[0], series[0]) else series
            val stepX = w / (effectivePoints.size - 1).coerceAtLeast(1)
            val linePath = Path()
            val fillPath = Path()

            effectivePoints.forEachIndexed { index, raw ->
                val x = index * stepX
                val norm = (raw / combinedMax).coerceIn(0f, 1f)
                val y = h - (norm * (h - 6.dp.toPx())) - 3.dp.toPx()
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo((effectivePoints.size - 1) * stepX, h)
            fillPath.close()

            if (fillArea) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.28f), Color.Transparent)
                    )
                )
            }
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        drawSeries(values, lineColor, fillArea = true)
        if (!secondaryValues.isNullOrEmpty()) {
            drawSeries(secondaryValues, secondaryColor, fillArea = false)
        }
    }
}

@Composable
fun UtilizationProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 7.dp
) {
    val noc = LocalNocColors.current
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 260f),
        label = "util_bar"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(noc.elevatedSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun EmptyNocState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String? = null,
    modifier: Modifier = Modifier
) {
    val noc = LocalNocColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.size(88.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = noc.healthy.copy(alpha = 0.12f),
                radius = size.minDimension * 0.48f,
                center = center
            )
            drawCircle(
                color = noc.healthy.copy(alpha = 0.3f),
                radius = size.minDimension * 0.34f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = noc.healthy,
                radius = 6.dp.toPx(),
                center = center
            )
            // Node arms
            val armOffset = size.minDimension * 0.32f
            listOf(
                Offset(center.x - armOffset, center.y - armOffset * 0.4f),
                Offset(center.x + armOffset, center.y - armOffset * 0.4f),
                Offset(center.x, center.y + armOffset)
            ).forEach { target ->
                drawLine(
                    color = noc.network.copy(alpha = 0.7f),
                    start = center,
                    end = target,
                    strokeWidth = 1.5.dp.toPx()
                )
                drawCircle(color = noc.network, radius = 3.5.dp.toPx(), center = target)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAction,
                modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier
            ) {
                Text(text = actionLabel)
            }
        }
    }
}
