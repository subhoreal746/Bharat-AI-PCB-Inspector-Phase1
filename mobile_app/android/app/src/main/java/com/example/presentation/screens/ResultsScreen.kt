package com.example.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionRecord
import com.example.domain.model.InspectionTarget
import com.example.presentation.components.DemonstrationBadge
import com.example.presentation.components.DispositionBadge
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.SeverityChip
import com.example.presentation.components.TechnicalMetadataRow
import com.example.presentation.components.TonalNoticeBanner
import com.example.presentation.components.VerticalHairline
import com.example.presentation.components.formatDurationLabel
import com.example.presentation.components.formatInspectionTimestamp
import com.example.presentation.components.severityColor
import com.example.presentation.viewmodel.ResultsUiState
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ResultsScreen(
    uiState: ResultsUiState,
    suggestedCsvFilename: String,
    onBackClick: () -> Unit,
    onSetAnnotatedOverlay: (Boolean) -> Unit,
    onSelectDefect: (String?) -> Unit,
    onToggleProvenance: () -> Unit,
    onOpenAdvisoryClick: (String) -> Unit,
    onSafExportUriSelected: (Uri) -> Unit,
    onShareCsvClick: ((Uri) -> Unit) -> Unit,
    onClearStatusMessage: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalPcbColors.current

    val safCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) onSafExportUriSelected(uri)
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp
            )
        }
        return
    }

    val record = uiState.record
    if (record == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(BharatSpacing.section),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Inspection record not found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(BharatSpacing.md))
            Button(
                onClick = onBackClick,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Go Back")
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Editorial Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BharatSpacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("results_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.inspectionId,
                    style = MonoTextStyles.inspectionId,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.boardName} • ${formatInspectionTimestamp(record.timestampEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { safCreateLauncher.launch(suggestedCsvFilename) },
                modifier = Modifier.testTag("results_export_csv_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "Save CSV via SAF",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(
                onClick = {
                    onShareCsvClick { shareUri ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Share Inspection CSV")
                        )
                    }
                },
                modifier = Modifier.testTag("results_share_csv_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share Inspection CSV",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("results_screen"),
            contentPadding = PaddingValues(
                horizontal = BharatSpacing.content,
                vertical = BharatSpacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(BharatSpacing.lg)
        ) {
            if (uiState.statusMessage != null) {
                item {
                    TonalNoticeBanner(
                        message = uiState.statusMessage,
                        onClick = onClearStatusMessage
                    )
                }
            }

            // Level-3 Official Screening Disposition Surface
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BharatSpacing.lg, vertical = BharatSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
                        ) {
                            Text(
                                text = "Official Screening Disposition",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
                            ) {
                                DispositionBadge(
                                    disposition = record.officialDisposition,
                                    modifier = Modifier.testTag("results_disposition_badge")
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    tonalElevation = 0.dp,
                                    modifier = Modifier.testTag("results_target_badge")
                                ) {
                                    Text(
                                        text = if (record.target == InspectionTarget.TRANSISTOR) {
                                            "Visual Anomaly"
                                        } else {
                                            "PCB Board"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (record.isDemonstration) {
                                    DemonstrationBadge(label = "DEMO")
                                }
                            }
                        }

                        VerticalHairline(
                            height = 44.dp,
                            modifier = Modifier.padding(horizontal = BharatSpacing.md)
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = record.totalDefects.toString(),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Defects Found",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Transistor PatchCore Metrics Surface (Level-3 when target == TRANSISTOR)
            if (record.target == InspectionTarget.TRANSISTOR) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("results_transistor_metrics_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(BharatSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PatchCore Anomaly Metrics",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (record.officialDisposition == InspectionDisposition.PASS) {
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = colors.passBg
                                    ) {
                                        Text(
                                            text = "NORMAL",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.passFg,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else if (record.officialDisposition == InspectionDisposition.REVIEW) {
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = colors.reviewBg
                                    ) {
                                        Text(
                                            text = "ANOMALOUS",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.reviewFg,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            HairlineDivider()

                            // Responsive 2x2 metrics grid in IBM Plex Mono
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.md)
                            ) {
                                TransistorMetricCell(
                                    label = "Raw Anomaly Score",
                                    value = record.transistorAnomaly?.rawAnomalyScore?.let {
                                        String.format(Locale.US, "%.4f", it)
                                    } ?: "N/A",
                                    valueTag = "results_transistor_raw_score",
                                    modifier = Modifier.weight(1f)
                                )
                                TransistorMetricCell(
                                    label = "Display Score",
                                    value = record.transistorAnomaly?.normalizedDisplayScore?.let {
                                        String.format(Locale.US, "%.1f%%", it * 100f)
                                    } ?: "N/A",
                                    valueTag = "results_transistor_norm_score",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.md)
                            ) {
                                TransistorMetricCell(
                                    label = "Anomaly Area",
                                    value = record.transistorAnomaly?.affectedAreaPercent?.let {
                                        String.format(Locale.US, "%.2f%%", it)
                                    } ?: "0.00%",
                                    valueTag = "results_transistor_area_percent",
                                    modifier = Modifier.weight(1f)
                                )
                                val latencyMs = record.latencyMetrics.patchCoreInferenceMs
                                    ?: record.modelInferenceLatencyMs
                                TransistorMetricCell(
                                    label = "Inference Latency",
                                    value = latencyMs?.let { "${it}ms" } ?: "N/A",
                                    valueTag = "results_transistor_latency",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (record.officialDisposition == InspectionDisposition.PASS) {
                                Text(
                                    text = "Normal visual pattern. Anomaly score is within nominal boundaries; no anomalous localized region identified.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (record.officialDisposition == InspectionDisposition.REVIEW) {
                                val hasBox = record.defects.any { it.boundingBox != null }
                                Text(
                                    text = if (hasBox) {
                                        "Visual anomaly detected above screening threshold (code: TR-ANOMALY-REVIEW). Localized anomalous region highlighted in viewport."
                                    } else {
                                        "Visual anomaly detected above screening threshold (code: TR-ANOMALY-REVIEW). No localized bounding box available for this anomaly (diffuse or low-gradient anomaly map). Full-frame fallback is prohibited."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.reviewFg
                                )
                            }
                        }
                    }
                }
            }

            if (record.isDemonstration) {
                item {
                    TonalNoticeBanner(
                        message = "Demonstration Simulation Result: Detections were generated by DemoPcbDetectionEngine for sample evaluation and do not reflect live ONNX inference.",
                        icon = Icons.Outlined.Science,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconTint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("results_demo_banner")
                    )
                }
            }

            if (record.officialDisposition == InspectionDisposition.INCOMPLETE) {
                item {
                    TonalNoticeBanner(
                        message = record.policyRationale.replace("Transistor Anomaly", "Anomaly"),
                        icon = Icons.Outlined.ErrorOutline,
                        containerColor = colors.incompleteBg,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        iconTint = colors.incompleteFg,
                        modifier = Modifier.testTag("results_incomplete_banner")
                    )
                }
            } else if (record.lowClarityRisk) {
                item {
                    val scoreText = record.clarityScore?.let {
                        String.format(Locale.US, " (score %.1f)", it)
                    } ?: ""
                    TonalNoticeBanner(
                        message = "Low image clarity detected$scoreText — optical confidence may be degraded; manual review recommended.",
                        icon = Icons.Outlined.WarningAmber,
                        containerColor = colors.reviewBg,
                        contentColor = colors.reviewFg,
                        iconTint = colors.reviewFg
                    )
                }
            }

            // Dominant Interactive Pan & Zoom Viewport with Flat Underline M3 Tabs
            item {
                InteractiveAnnotatedPcbViewport(
                    record = record,
                    showAnnotated = uiState.showAnnotatedOverlay,
                    selectedDefectId = uiState.selectedDefectId,
                    onToggleOverlay = onSetAnnotatedOverlay,
                    onSelectDefect = onSelectDefect
                )
            }

            // Open Policy Evaluation Rationale (No Card Box)
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(BharatSpacing.sm))
                        Text(
                            text = record.policyProfile,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        text = record.policyRationale.replace("Transistor Anomaly", "Anomaly"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Policy v${record.policyVersion} • ${record.effectiveThresholdsSummary}",
                        style = MonoTextStyles.compact,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(BharatSpacing.sm))
                    HairlineDivider()
                }
            }

            // Open Detected Defects / Anomalies Section
            item {
                val listTitle = if (record.target == InspectionTarget.TRANSISTOR) {
                    "Detected Anomalies (${record.defects.size})"
                } else {
                    "Detected Defects (${record.defects.size})"
                }
                Text(
                    text = listTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (record.defects.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(BharatSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (record.officialDisposition == InspectionDisposition.INCOMPLETE) {
                                    "No detections available (inspection incomplete)"
                                } else if (record.target == InspectionTarget.TRANSISTOR) {
                                    "No visual anomalies detected"
                                } else {
                                    "No PCB defects detected"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (record.officialDisposition == InspectionDisposition.INCOMPLETE) {
                                    "Inference could not complete reliably. Resolve the runtime or image issue and re-inspect."
                                } else if (record.target == InspectionTarget.TRANSISTOR) {
                                    "Anomaly score remained below the anomaly decision threshold."
                                } else {
                                    "Zero bounding boxes exceeded the detector confidence threshold."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        record.defects.forEachIndexed { idx, defect ->
                            val isSelected = defect.defectId == uiState.selectedDefectId
                            DefectListRow(
                                defect = defect,
                                isSelected = isSelected,
                                onClick = { onSelectDefect(defect.defectId) }
                            )
                            if (idx < record.defects.lastIndex) {
                                HairlineDivider()
                            }
                        }
                    }
                }
            }

            // Primary AI Technical Advisory CTA
            item {
                val advLabel = when (record.advisory.availability) {
                    AdvisoryAvailability.AVAILABLE ->
                        if (record.advisory.isDemonstration) {
                            "Open AI Technical Advisory (Demo)"
                        } else {
                            "Open AI Technical Advisory (Ready)"
                        }
                    AdvisoryAvailability.UNAVAILABLE -> "Open AI Technical Advisory (Unavailable)"
                    AdvisoryAvailability.NOT_REQUESTED -> "Open AI Technical Advisory"
                }
                Button(
                    onClick = { onOpenAdvisoryClick(record.inspectionId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("results_open_advisory_button"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(BharatSpacing.sm))
                    Text(
                        text = advLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Expandable Model Provenance & Latency Strip
            item {
                ProvenanceAndLatencySection(
                    record = record,
                    expanded = uiState.isProvenanceExpanded,
                    onToggle = onToggleProvenance
                )
                Spacer(modifier = Modifier.height(BharatSpacing.md))
            }
        }
    }
}

@Composable
private fun TransistorMetricCell(
    label: String,
    value: String,
    valueTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MonoTextStyles.value,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(valueTag),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InteractiveAnnotatedPcbViewport(
    record: InspectionRecord,
    showAnnotated: Boolean,
    selectedDefectId: String?,
    onToggleOverlay: (Boolean) -> Unit,
    onSelectDefect: (String?) -> Unit
) {
    val colors = LocalPcbColors.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val ratio = if (record.imageWidth > 0 && record.imageHeight > 0) {
        (record.imageWidth.toFloat() / record.imageHeight.toFloat()).coerceIn(0.6f, 1.9f)
    } else {
        4f / 3f
    }

    val selectedTabIndex = if (showAnnotated) 1 else 0

    Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.widthIn(max = 224.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                        height = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                divider = {}
            ) {
                Tab(
                    selected = !showAnnotated,
                    onClick = { onToggleOverlay(false) },
                    modifier = Modifier.testTag("results_toggle_original"),
                    selectedContentColor = MaterialTheme.colorScheme.onBackground,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            text = "Original",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = showAnnotated,
                    onClick = { onToggleOverlay(true) },
                    modifier = Modifier.testTag("results_toggle_annotated"),
                    selectedContentColor = MaterialTheme.colorScheme.onBackground,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            text = "Annotated",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }

            TextButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("results_fit_reset_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = "Reset zoom",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (scale > 1.05f) {
                        "Reset (${String.format(Locale.US, "%.1fx", scale)})"
                    } else {
                        "Fit"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Unboxed Dominant Inspection Viewport
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(MaterialTheme.shapes.medium)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = newScale
                        if (newScale <= 1.01f) {
                            offset = Offset.Zero
                        } else {
                            val maxX = (size.width * (newScale - 1f)) / 2f
                            val maxY = (size.height * (newScale - 1f)) / 2f
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
                }
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            ) {
                AsyncImage(
                    model = Uri.parse(record.imageUri),
                    contentDescription = record.boardName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                if (showAnnotated && record.defects.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        record.defects.forEach { defect ->
                            val box = defect.boundingBox?.clamped() ?: return@forEach
                            val left = box.x * size.width
                            val top = box.y * size.height
                            val w = box.width * size.width
                            val h = box.height * size.height
                            val isSel = defect.defectId == selectedDefectId
                            val strokeCol = severityColor(defect.severity, colors.isDark)
                            val strokeW = if (isSel) 3.5.dp.toPx() else 2.dp.toPx()

                            if (isSel) {
                                drawRect(
                                    color = strokeCol.copy(alpha = 0.20f),
                                    topLeft = Offset(left, top),
                                    size = Size(w, h)
                                )
                            }
                            drawRect(
                                color = strokeCol,
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                style = Stroke(width = strokeW)
                            )
                        }
                    }

                    // Clickable normalized box hit-targets + crisp high-contrast labels
                    record.defects.forEachIndexed { idx, defect ->
                        val box = defect.boundingBox?.clamped() ?: return@forEachIndexed
                        val leftPx = (box.x * widthPx).roundToInt()
                        val topPx = (box.y * heightPx).roundToInt()
                        val boxW = with(LocalDensity.current) { (box.width * widthPx).toDp() }
                        val boxH = with(LocalDensity.current) { (box.height * heightPx).toDp() }
                        val tagColor = severityColor(defect.severity, colors.isDark)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(leftPx, topPx) }
                                .size(
                                    width = boxW.coerceAtLeast(36.dp),
                                    height = boxH.coerceAtLeast(36.dp)
                                )
                                .clickable { onSelectDefect(defect.defectId) }
                                .testTag("results_bbox_${defect.defectId}")
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = tagColor
                            ) {
                                val displayLabel = formatDefectDisplayLabel(defect)
                                Text(
                                    text = "#${idx + 1} $displayLabel ${(defect.confidence * 100).roundToInt()}%",
                                    style = MonoTextStyles.compact,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves the user-facing display label for a detected defect or anomaly on the Inspection Details screen.
 * Maps generic anomaly categories (e.g. raw class "transistor_anomaly" or
 * canonical label "Transistor Anomaly") to "Anomaly".
 */
internal fun formatDefectDisplayLabel(defect: DetectedDefect): String {
    return when {
        defect.rawClassName.equals("transistor_anomaly", ignoreCase = true) -> "Anomaly"
        defect.canonicalClassName.equals("Transistor Anomaly", ignoreCase = true) -> "Anomaly"
        defect.canonicalClassName.contains("transistor", ignoreCase = true) -> "Anomaly"
        else -> defect.canonicalClassName
    }
}

@Composable
private fun DefectListRow(
    defect: DetectedDefect,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalPcbColors.current
    val bg = if (isSelected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (isSelected) BharatSpacing.md else 0.dp,
                vertical = BharatSpacing.md
            )
            .testTag("results_defect_row_${defect.defectId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val displayLabel = formatDefectDisplayLabel(defect)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
            ) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                SeverityChip(severity = defect.severity)
            }

            if (defect.rawClassName != displayLabel) {
                Text(
                    text = "Raw class: ${defect.rawClassName}",
                    style = MonoTextStyles.compact,
                    color = colors.textMuted
                )
            }

            val box = defect.boundingBox
            val boxText = if (box != null) {
                String.format(
                    Locale.US,
                    "Confidence %.1f%% • Box [%.2f, %.2f, %.2f, %.2f]",
                    defect.confidence * 100f,
                    box.x,
                    box.y,
                    box.width,
                    box.height
                )
            } else {
                String.format(
                    Locale.US,
                    "Confidence %.1f%% • No localized region",
                    defect.confidence * 100f
                )
            }
            Text(
                text = boxText,
                style = MonoTextStyles.compact,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            defect.code?.let { code ->
                Text(
                    text = "Code: $code",
                    style = MonoTextStyles.compact,
                    color = colors.textMuted
                )
            }

            if (defect.requiresManualReview) {
                Text(
                    text = "Requires manual verification",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.reviewFg
                )
            }
        }
    }
}

@Composable
private fun ProvenanceAndLatencySection(
    record: InspectionRecord,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = BharatSpacing.lg, vertical = 14.dp)
                    .testTag("results_provenance_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Provenance & Latency",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = "Toggle provenance details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = BharatSpacing.lg,
                        end = BharatSpacing.lg,
                        bottom = BharatSpacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HairlineDivider()
                    Spacer(modifier = Modifier.height(BharatSpacing.sm))
                    TechnicalMetadataRow("Inference Engine", record.provenance.inferenceEngine)
                    TechnicalMetadataRow("Model Identity", record.provenance.modelIdentity)
                    TechnicalMetadataRow("Model SHA-256", record.provenance.modelSha256)
                    TechnicalMetadataRow("Model Contract", record.provenance.modelContractVersion)
                    TechnicalMetadataRow("Preprocessing", record.provenance.preprocessingVersion)
                    TechnicalMetadataRow("Decoder", record.provenance.decoderVersion)
                    TechnicalMetadataRow("Class Mapping", record.provenance.classMappingVersion)
                    TechnicalMetadataRow("Runtime Version", record.provenance.runtimeVersion)
                    HairlineDivider(modifier = Modifier.padding(vertical = BharatSpacing.sm))
                    val lat = record.latencyMetrics
                    TechnicalMetadataRow("Preprocess Latency", formatDurationLabel(lat.imagePreprocessingMs))
                    TechnicalMetadataRow("YOLO Inference", formatDurationLabel(lat.yoloInferenceMs))
                    TechnicalMetadataRow("Decode + NMS", formatDurationLabel(lat.outputDecodingNmsMs))
                    TechnicalMetadataRow("Policy Evaluation", formatDurationLabel(lat.policyEvaluationMs))
                    TechnicalMetadataRow("LLM Advisory", formatDurationLabel(lat.llmAdvisoryMs))
                    TechnicalMetadataRow("Total Pipeline", formatDurationLabel(lat.totalDurationMs))
                }
            }
        }
    }
}
