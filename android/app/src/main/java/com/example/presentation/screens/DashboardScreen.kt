package com.example.presentation.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionRecord
import com.example.domain.model.ModelInfo
import com.example.domain.usecase.DailyTrendPoint
import com.example.domain.usecase.DashboardPeriod
import com.example.domain.usecase.TrendMetric
import com.example.domain.usecase.TrendWindow
import com.example.presentation.components.BrandHeader
import com.example.presentation.components.DemonstrationBadge
import com.example.presentation.components.DispositionBadge
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.ModelStateBadge
import com.example.presentation.components.RuntimeStatusDialog
import com.example.presentation.components.SectionHeader
import com.example.presentation.components.VerticalHairline
import com.example.presentation.components.formatInspectionTimestamp
import com.example.presentation.viewmodel.DashboardUiState
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.BlueGrey
import com.example.ui.theme.DarkDivider
import com.example.ui.theme.DarkSurfaceHigh
import com.example.ui.theme.DeepestNavy
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles
import com.example.ui.theme.MutedGrey
import com.example.ui.theme.PaleGrey
import com.example.ui.theme.PrimaryTextDark
import com.example.ui.theme.Slate
import com.example.ui.theme.StatusIncompleteDark
import com.example.ui.theme.StatusIncompleteLight
import com.example.ui.theme.StatusPassDark
import com.example.ui.theme.StatusPassLight
import com.example.ui.theme.StatusRejectDark
import com.example.ui.theme.StatusRejectLight
import com.example.ui.theme.StatusReviewDark
import com.example.ui.theme.StatusReviewLight
import java.util.Locale

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onSelectPeriod: (DashboardPeriod) -> Unit,
    onSelectTrendWindow: (TrendWindow) -> Unit,
    onSelectTrendMetric: (TrendMetric) -> Unit,
    onStartInspectionClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onRecordClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onDispositionClick: (InspectionDisposition) -> Unit = {}
) {
    var showRuntimeDialog by remember { mutableStateOf(false) }

    if (showRuntimeDialog) {
        RuntimeStatusDialog(
            detectionModelInfo = uiState.detectionModelInfo,
            advisoryModelInfo = uiState.advisoryModelInfo,
            transistorModelInfo = uiState.transistorModelInfo,
            onOpenSettings = onSettingsClick,
            onDismiss = { showRuntimeDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(horizontal = BharatSpacing.content, vertical = BharatSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.section)
    ) {
        item {
            BrandHeader(
                onRuntimeStatusClick = { showRuntimeDialog = true },
                hasRuntimeAlert = uiState.hasRuntimeAlert
            )
        }

        item {
            val colors = LocalPcbColors.current
            val startButtonContainer = if (colors.isDark) {
                MaterialTheme.colorScheme.primary
            } else {
                DeepestNavy
            }
            val startButtonContent = if (colors.isDark) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                PrimaryTextDark
            }

            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                InspectionOverviewSurface(
                    uiState = uiState,
                    onSelectPeriod = onSelectPeriod,
                    onDispositionClick = onDispositionClick
                )

                Button(
                    onClick = onStartInspectionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("dashboard_start_inspection_button"),
                    shape = MaterialTheme.shapes.medium,
                    elevation = null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = startButtonContainer,
                        contentColor = startButtonContent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusWeak,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(BharatSpacing.sm))
                    Text(
                        text = "Start Inspection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
            InspectionOutcomesSection(
                uiState = uiState,
                onDispositionClick = onDispositionClick
            )
            Spacer(modifier = Modifier.height(BharatSpacing.content))
            HairlineDivider()
        }

        item {
            DefectTrendSection(
                uiState = uiState,
                onSelectTrendWindow = onSelectTrendWindow,
                onSelectTrendMetric = onSelectTrendMetric
            )
            Spacer(modifier = Modifier.height(BharatSpacing.content))
            HairlineDivider()
        }

        item {
            RecentInspectionsSection(
                records = uiState.metrics.recentInspections,
                onSeeAllClick = onHistoryClick,
                onRecordClick = onRecordClick,
                onStartInspectionClick = onStartInspectionClick
            )
            Spacer(modifier = Modifier.height(BharatSpacing.sm))
            HairlineDivider()
        }

        item {
            ModelReadinessSection(
                detectionModelInfo = uiState.detectionModelInfo,
                advisoryModelInfo = uiState.advisoryModelInfo,
                transistorModelInfo = uiState.transistorModelInfo,
                onConfigureClick = onSettingsClick
            )
            Spacer(modifier = Modifier.height(BharatSpacing.lg))
        }
    }
}

@Composable
private fun InspectionOverviewSurface(
    uiState: DashboardUiState,
    onSelectPeriod: (DashboardPeriod) -> Unit,
    onDispositionClick: (InspectionDisposition) -> Unit
) {
    val colors = LocalPcbColors.current
    var periodExpanded by remember { mutableStateOf(false) }

    // Inverse tonal treatment:
    // Dark Mode -> soft muted cool greyish-white (PaleGrey) against dark canvas
    // Light Mode -> dark blue-black (#06141B) against light canvas
    val overviewBg = if (colors.isDark) PaleGrey else DeepestNavy
    val overviewPrimaryText = if (colors.isDark) DeepestNavy else PrimaryTextDark
    val overviewSecondaryText = if (colors.isDark) BlueGrey else MutedGrey
    val overviewDivider = if (colors.isDark) Slate.copy(alpha = 0.28f) else DarkDivider
    val periodChipBg = if (colors.isDark) DeepestNavy.copy(alpha = 0.08f) else DarkSurfaceHigh
    val passMetricColor = if (colors.isDark) StatusPassLight else StatusPassDark
    val reviewMetricColor = if (colors.isDark) StatusReviewLight else StatusReviewDark
    val rejectMetricColor = if (colors.isDark) StatusRejectLight else StatusRejectDark
    val incompleteMetricColor = if (colors.isDark) StatusIncompleteLight else StatusIncompleteDark

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_overview_card"),
        shape = MaterialTheme.shapes.large,
        color = overviewBg,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(BharatSpacing.content)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Inspection Overview",
                    style = MaterialTheme.typography.titleMedium,
                    color = overviewPrimaryText
                )

                Box {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = periodChipBg,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .clickable { periodExpanded = true }
                            .testTag("dashboard_period_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.selectedPeriod.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = overviewPrimaryText
                            )
                            Spacer(modifier = Modifier.width(BharatSpacing.xs))
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Select overview timeframe",
                                tint = overviewSecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = periodExpanded,
                        onDismissRequest = { periodExpanded = false }
                    ) {
                        DashboardPeriod.entries.forEach { period ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = period.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                onClick = {
                                    periodExpanded = false
                                    onSelectPeriod(period)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(BharatSpacing.md))

            Text(
                text = uiState.metrics.totalInspections.toString(),
                style = MaterialTheme.typography.displayMedium,
                color = overviewPrimaryText,
                modifier = Modifier.testTag("dashboard_total_count")
            )
            Text(
                text = "Total Inspections",
                style = MaterialTheme.typography.bodySmall,
                color = overviewSecondaryText
            )

            Spacer(modifier = Modifier.height(BharatSpacing.lg))
            HairlineDivider(color = overviewDivider)
            Spacer(modifier = Modifier.height(BharatSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewSubMetric(
                    label = "Pass",
                    count = uiState.metrics.passCount,
                    valueColor = passMetricColor,
                    labelColor = overviewSecondaryText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDispositionClick(InspectionDisposition.PASS) }
                        .padding(vertical = BharatSpacing.xs)
                        .testTag("dashboard_metric_pass")
                )
                VerticalHairline(height = 30.dp, color = overviewDivider)
                OverviewSubMetric(
                    label = "Review",
                    count = uiState.metrics.reviewCount,
                    valueColor = reviewMetricColor,
                    labelColor = overviewSecondaryText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDispositionClick(InspectionDisposition.REVIEW) }
                        .padding(vertical = BharatSpacing.xs)
                        .testTag("dashboard_metric_review")
                )
                VerticalHairline(height = 30.dp, color = overviewDivider)
                OverviewSubMetric(
                    label = "Reject",
                    count = uiState.metrics.rejectCount,
                    valueColor = rejectMetricColor,
                    labelColor = overviewSecondaryText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDispositionClick(InspectionDisposition.REJECT) }
                        .padding(vertical = BharatSpacing.xs)
                        .testTag("dashboard_metric_reject")
                )
                if (uiState.metrics.incompleteCount > 0) {
                    VerticalHairline(height = 30.dp, color = overviewDivider)
                    OverviewSubMetric(
                        label = "Incomp.",
                        count = uiState.metrics.incompleteCount,
                        valueColor = incompleteMetricColor,
                        labelColor = overviewSecondaryText,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDispositionClick(InspectionDisposition.INCOMPLETE) }
                            .padding(vertical = BharatSpacing.xs)
                            .testTag("dashboard_metric_incomplete")
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewSubMetric(
    label: String,
    count: Int,
    valueColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = count.toString().padStart(2, '0'),
            style = MaterialTheme.typography.headlineSmall.merge(MonoTextStyles.tabularMetric),
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectionOutcomesSection(
    uiState: DashboardUiState,
    onDispositionClick: (InspectionDisposition) -> Unit = {}
) {
    val colors = LocalPcbColors.current
    val metrics = uiState.metrics

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_outcomes_section"),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)
    ) {
        SectionHeader(
            title = "Inspection Outcomes",
            trailing = if (metrics.hasDemonstrationRecords) {
                {
                    DemonstrationBadge(
                        label = "DEMO DATA",
                        modifier = Modifier.testTag("dashboard_demo_data_badge")
                    )
                }
            } else {
                null
            }
        )

        val total = metrics.totalInspections
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (total > 0) {
                if (metrics.passCount > 0) {
                    Box(
                        modifier = Modifier
                            .weight(metrics.passCount.toFloat())
                            .height(8.dp)
                            .background(colors.passFg)
                    )
                }
                if (metrics.reviewCount > 0) {
                    Box(
                        modifier = Modifier
                            .weight(metrics.reviewCount.toFloat())
                            .height(8.dp)
                            .background(colors.reviewFg)
                    )
                }
                if (metrics.rejectCount > 0) {
                    Box(
                        modifier = Modifier
                            .weight(metrics.rejectCount.toFloat())
                            .height(8.dp)
                            .background(colors.rejectFg)
                    )
                }
                if (metrics.incompleteCount > 0) {
                    Box(
                        modifier = Modifier
                            .weight(metrics.incompleteCount.toFloat())
                            .height(8.dp)
                            .background(colors.incompleteFg)
                    )
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(BharatSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
        ) {
            OutcomeLegendItem(
                dotColor = colors.passFg,
                label = "Pass",
                count = metrics.passCount,
                percent = metrics.passPercent,
                onClick = { onDispositionClick(InspectionDisposition.PASS) },
                modifier = Modifier.testTag("dashboard_legend_pass")
            )
            OutcomeLegendItem(
                dotColor = colors.reviewFg,
                label = "Review",
                count = metrics.reviewCount,
                percent = metrics.reviewPercent,
                onClick = { onDispositionClick(InspectionDisposition.REVIEW) },
                modifier = Modifier.testTag("dashboard_legend_review")
            )
            OutcomeLegendItem(
                dotColor = colors.rejectFg,
                label = "Reject",
                count = metrics.rejectCount,
                percent = metrics.rejectPercent,
                onClick = { onDispositionClick(InspectionDisposition.REJECT) },
                modifier = Modifier.testTag("dashboard_legend_reject")
            )
            if (metrics.incompleteCount > 0) {
                OutcomeLegendItem(
                    dotColor = colors.incompleteFg,
                    label = "Incomplete",
                    count = metrics.incompleteCount,
                    percent = metrics.incompletePercent,
                    onClick = { onDispositionClick(InspectionDisposition.INCOMPLETE) },
                    modifier = Modifier.testTag("dashboard_legend_incomplete")
                )
            }
        }
    }
}

@Composable
private fun OutcomeLegendItem(
    dotColor: Color,
    label: String,
    count: Int,
    percent: Float,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clickMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        modifier = clickMod.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${count.toString().padStart(2, '0')} (${String.format(Locale.US, "%.1f", percent)}%)",
            style = MonoTextStyles.compact,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DefectTrendSection(
    uiState: DashboardUiState,
    onSelectTrendWindow: (TrendWindow) -> Unit,
    onSelectTrendMetric: (TrendMetric) -> Unit
) {
    var windowMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_trend_section"),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)
    ) {
        SectionHeader(
            title = "Defect Trend",
            trailing = {
                Box {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .clickable { windowMenuExpanded = true }
                            .testTag("dashboard_trend_window_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.selectedTrendWindow.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(BharatSpacing.xs))
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Select trend timeframe",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = windowMenuExpanded,
                        onDismissRequest = { windowMenuExpanded = false }
                    ) {
                        TrendWindow.entries.forEach { window ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = window.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                onClick = {
                                    windowMenuExpanded = false
                                    onSelectTrendWindow(window)
                                }
                            )
                        }
                    }
                }
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm)) {
            TrendMetric.entries.forEach { metric ->
                val selected = uiState.selectedTrendMetric == metric
                FilterChip(
                    selected = selected,
                    onClick = { onSelectTrendMetric(metric) },
                    label = {
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = MaterialTheme.shapes.small,
                    border = null,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("trend_metric_${metric.name.lowercase()}")
                )
            }
        }

        // Open chart layout directly on canvas (no enclosing bordered box)
        if (!uiState.metrics.hasAnyTrendRecords) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BharatSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.md)
            ) {
                Icon(
                    imageVector = Icons.Outlined.QueryStats,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "No daily trend data yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Inspected boards stored in Room will plot daily counts here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            DailyTrendChart(
                points = uiState.metrics.trendPoints,
                metric = uiState.selectedTrendMetric
            )
        }
    }
}

@Composable
private fun DailyTrendChart(
    points: List<DailyTrendPoint>,
    metric: TrendMetric
) {
    val values = points.map { point ->
        when (metric) {
            TrendMetric.DEFECT_COUNT -> point.defectCount
            TrendMetric.INSPECTION_COUNT -> point.inspectionCount
        }
    }
    val maxVal = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val lineColor = MaterialTheme.colorScheme.onBackground
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val canvasBg = MaterialTheme.colorScheme.background

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BharatSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (metric == TrendMetric.DEFECT_COUNT) {
                    "Total Defects: ${values.sum()}"
                } else {
                    "Total Runs: ${values.sum()}"
                },
                style = MonoTextStyles.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Peak/day: ${values.maxOrNull() ?: 0}",
                style = MonoTextStyles.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
        ) {
            if (points.isEmpty()) return@Canvas
            val leftPad = 10.dp.toPx()
            val rightPad = 10.dp.toPx()
            val topPad = 12.dp.toPx()
            val bottomPad = 10.dp.toPx()
            val usableW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            val coords = points.mapIndexed { idx, _ ->
                val x = if (points.size == 1) {
                    leftPad + usableW / 2f
                } else {
                    leftPad + (idx.toFloat() / (points.size - 1).toFloat()) * usableW
                }
                val ratio = values[idx].toFloat() / maxVal.toFloat()
                val y = topPad + usableH * (1f - ratio)
                Offset(x, y)
            }

            // Quiet midpoint and baseline grid hairlines
            drawLine(
                color = dividerColor.copy(alpha = 0.55f),
                start = Offset(leftPad, topPad + usableH / 2f),
                end = Offset(leftPad + usableW, topPad + usableH / 2f),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = dividerColor,
                start = Offset(leftPad, topPad + usableH),
                end = Offset(leftPad + usableW, topPad + usableH),
                strokeWidth = 1.dp.toPx()
            )

            if (coords.size >= 2) {
                val strokePath = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (i in 0 until coords.lastIndex) {
                        val p0 = coords[i]
                        val p1 = coords[i + 1]
                        val midX = (p0.x + p1.x) / 2f
                        cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                    }
                }
                drawPath(
                    path = strokePath,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            coords.forEach { pt ->
                drawCircle(
                    color = lineColor,
                    radius = 3.5.dp.toPx(),
                    center = pt
                )
                drawCircle(
                    color = canvasBg,
                    radius = 1.75.dp.toPx(),
                    center = pt
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val step = if (points.size > 8) 2 else 1
            points.forEachIndexed { index, pt ->
                if (index % step == 0 || index == points.lastIndex) {
                    Text(
                        text = pt.shortLabel,
                        style = MonoTextStyles.compact,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentInspectionsSection(
    records: List<InspectionRecord>,
    onSeeAllClick: () -> Unit,
    onRecordClick: (String) -> Unit,
    onStartInspectionClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.xs)
    ) {
        SectionHeader(
            title = "Recent Inspections",
            actionLabel = "See all",
            actionTestTag = "dashboard_see_all_button",
            onActionClick = onSeeAllClick
        )

        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BharatSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BharatSpacing.xs)
            ) {
                Text(
                    text = "No inspections recorded yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Capture or upload a PCB image, or run one of the 3 sample boards on the Inspect tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onStartInspectionClick,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Go to New Inspection",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                records.forEachIndexed { idx, record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRecordClick(record.inspectionId) }
                            .padding(vertical = BharatSpacing.md)
                            .testTag("recent_inspection_row_${record.inspectionId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = Uri.parse(record.imageUri),
                            contentDescription = record.boardName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                        )
                        Spacer(modifier = Modifier.width(BharatSpacing.md))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = record.inspectionId,
                                style = MonoTextStyles.identifier,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = record.boardName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(formatInspectionTimestamp(record.timestampEpochMs))
                                    append(" • ${record.totalDefects} defect(s)")
                                    if (record.isDemonstration) append(" • DEMO")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(BharatSpacing.sm))
                        DispositionBadge(disposition = record.officialDisposition)
                    }
                    if (idx < records.lastIndex) {
                        HairlineDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelReadinessSection(
    detectionModelInfo: ModelInfo,
    advisoryModelInfo: ModelInfo,
    transistorModelInfo: ModelInfo,
    onConfigureClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.xs)
    ) {
        SectionHeader(
            title = "Model Readiness",
            actionLabel = "Configure",
            actionTestTag = "dashboard_configure_models_button",
            onActionClick = onConfigureClick
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            ModelReadinessRow(
                title = "YOLOv8n Defect Detector",
                subtitle = detectionModelInfo.runtimeLabel,
                info = detectionModelInfo
            )
            HairlineDivider()
            ModelReadinessRow(
                title = "PatchCore Unsupervised Anomaly",
                subtitle = transistorModelInfo.runtimeLabel,
                info = transistorModelInfo
            )
            HairlineDivider()
            ModelReadinessRow(
                title = "SmolLM2 360M Technical Advisory",
                subtitle = advisoryModelInfo.runtimeLabel,
                info = advisoryModelInfo
            )
        }
    }
}

@Composable
private fun ModelReadinessRow(
    title: String,
    subtitle: String,
    info: ModelInfo
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BharatSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(BharatSpacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(BharatSpacing.sm))
                ModelStateBadge(state = info.state)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = info.detailMessage,
                style = MonoTextStyles.compact,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
