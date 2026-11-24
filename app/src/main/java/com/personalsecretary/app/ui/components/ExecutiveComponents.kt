package com.personalsecretary.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.ExecutiveInsight
import com.personalsecretary.app.data.InsightSeverity
import com.personalsecretary.app.data.MonthlyCashFlow
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.SkyAccent
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary

@Composable
fun ExecutiveCard(
    modifier: Modifier = Modifier,
    borderColor: Color = CardBorder,
    containerColor: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    ringColor: Color,
    size: Dp = 52.dp,
    strokeWidth: Dp = 5.dp,
    centerText: String
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = CardBorder,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                style = stroke
            )
        }
        Text(
            text = centerText,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun InsightCard(insight: ExecutiveInsight, modifier: Modifier = Modifier) {
    val accent = when (insight.severity) {
        InsightSeverity.POSITIVE -> EmeraldAccent
        InsightSeverity.CAUTION -> CoralAccent
        InsightSeverity.INFO -> AccentGold
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = insight.domain.name.replace("_", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = insight.detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = "Advice: ${insight.recommendation}",
                style = MaterialTheme.typography.bodySmall,
                color = AccentGold
            )
        }
    }
}

@Composable
fun MonthlyCashFlowChart(
    flows: List<MonthlyCashFlow>,
    modifier: Modifier = Modifier
) {
    if (flows.isEmpty()) {
        Text(
            text = "Sync Day Book to render monthly cash flow.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        return
    }
    val maxVal = flows.maxOf { maxOf(it.income, it.expense) }.coerceAtLeast(1.0)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LegendDot("Income", EmeraldAccent)
            LegendDot("Expense", CoralAccent)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            val groupWidth = size.width / flows.size
            val barWidth = (groupWidth * 0.28f).coerceAtMost(26f)
            val gap = 6f
            val chartHeight = size.height - 8f

            flows.forEachIndexed { idx, f ->
                val centerX = groupWidth * idx + groupWidth / 2f
                val incH = ((f.income / maxVal) * chartHeight).toFloat().coerceAtLeast(4f)
                val expH = ((f.expense / maxVal) * chartHeight).toFloat().coerceAtLeast(4f)

                drawRoundRect(
                    color = EmeraldAccent,
                    topLeft = Offset(centerX - barWidth - gap / 2f, chartHeight - incH),
                    size = Size(barWidth, incH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawRoundRect(
                    color = CoralAccent,
                    topLeft = Offset(centerX + gap / 2f, chartHeight - expH),
                    size = Size(barWidth, expH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            flows.forEach { f ->
                Text(
                    text = f.monthKey.takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun StepsWeekChart(
    records: List<DailyStepRecord>,
    goal: Int,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) return
    val highestDay = records.maxOf { it.steps }
    val showGoalLine = highestDay >= (goal * 0.25).toLong()
    val maxSteps = if (showGoalLine) {
        maxOf(highestDay, goal.toLong(), 1000L).toFloat()
    } else {
        maxOf((highestDay * 1.45).toLong(), 200L).toFloat()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Step value labels above each bar so even small counts (e.g. 66) are clearly legible
        Row(modifier = Modifier.fillMaxWidth()) {
            records.forEach { rec ->
                val label = when {
                    rec.steps <= 0L -> ""
                    rec.steps >= 1000L -> String.format(java.util.Locale.US, "%.1fk", rec.steps / 1000.0)
                    else -> rec.steps.toString()
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rec.steps >= goal) EmeraldAccent else SkyAccent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
        ) {
            val colW = size.width / records.size
            val barW = (colW * 0.45f).coerceAtMost(34f)
            val h = size.height - 6f

            if (showGoalLine) {
                val goalY = h - ((goal / maxSteps) * h)
                drawLine(
                    color = AccentGold.copy(alpha = 0.55f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 2f
                )
            }

            records.forEachIndexed { idx, rec ->
                val barH = if (rec.steps > 0L) {
                    ((rec.steps / maxSteps) * h).coerceAtLeast(12f)
                } else {
                    3f
                }
                val x = colW * idx + (colW - barW) / 2f
                val color = when {
                    rec.steps >= goal -> EmeraldAccent
                    rec.steps > 0L -> SkyAccent
                    else -> CardBorder
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, h - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            records.forEach { rec ->
                Text(
                    text = rec.dateIso.takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        if (!showGoalLine) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Auto-scaled for visibility · Daily target: ${String.format(java.util.Locale.US, "%,d", goal)} steps",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
