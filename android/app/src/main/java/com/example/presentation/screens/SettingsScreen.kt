package com.example.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.presentation.components.BrandHeader
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.ModelStateBadge
import com.example.presentation.components.TechnicalMetadataRow
import com.example.presentation.components.TonalNoticeBanner
import com.example.presentation.components.VerticalHairline
import com.example.presentation.components.formatBytesHuman
import com.example.presentation.viewmodel.SettingsUiState
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    suggestedAllCsvFilename: String,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onUpdatePolicyConfig: (InspectionPolicyConfig) -> Unit,
    onResetPolicyDefaults: () -> Unit,
    onImportDetectionModel: (Uri) -> Unit,
    onLoadDetectionModel: () -> Unit,
    onUnloadDetectionModel: () -> Unit,
    onImportTransistorModel: (Uri) -> Unit = {},
    onLoadTransistorModel: () -> Unit = {},
    onUnloadTransistorModel: () -> Unit = {},
    onImportAdvisoryModel: (Uri) -> Unit,
    onImportAdvisoryTokenizer: (Uri) -> Unit,
    onLoadAdvisoryModel: () -> Unit,
    onUnloadAdvisoryModel: () -> Unit,
    onInspectModelMetadata: (ModelSlot) -> Unit,
    onDismissMetadataDialog: () -> Unit,
    onDismissModelStatusBanner: () -> Unit,
    onReconcileCsvOutbox: () -> Unit,
    onExportAllCsvToSafUri: (Uri) -> Unit,
    onRequestClearHistory: () -> Unit,
    onDismissClearHistory: () -> Unit,
    onConfirmClearHistory: () -> Unit,
    onClearStorageMessage: () -> Unit
) {
    val colors = LocalPcbColors.current

    val onnxPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportDetectionModel(uri) }

    val transistorPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportTransistorModel(uri) }

    val ptePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportAdvisoryModel(uri) }

    val tokenizerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportAdvisoryTokenizer(uri) }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) onExportAllCsvToSafUri(uri) }

    if (uiState.inspectedMetadataBody != null) {
        AlertDialog(
            onDismissRequest = onDismissMetadataDialog,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = uiState.inspectedMetadataTitle ?: "Model Metadata",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = uiState.inspectedMetadataBody,
                    style = MonoTextStyles.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissMetadataDialog) {
                    Text(
                        text = "Close",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }

    if (uiState.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = onDismissClearHistory,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = "Clear Inspection History?",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "This permanently deletes all ${uiState.totalRecordsCount} Room inspection records, resets the internal CSV audit log and ledger, and removes unreferenced PCB image files.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmClearHistory,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.rejectFg,
                        contentColor = MaterialTheme.colorScheme.background
                    ),
                    modifier = Modifier.testTag("settings_confirm_clear_history")
                ) {
                    Text(
                        text = "Delete All",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearHistory) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(
            horizontal = BharatSpacing.content,
            vertical = BharatSpacing.sm
        ),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.section)
    ) {
        // Editorial Header
        item {
            Column {
                BrandHeader()
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Theme, on-device runtimes, screening policy & CSV storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.isBusy) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
        }

        if (uiState.modelStatusBanner != null) {
            item {
                val banner = uiState.modelStatusBanner
                val fg = if (banner.success) colors.passFg else colors.rejectFg
                val bg = if (banner.success) colors.passBg else colors.rejectBg
                TonalNoticeBanner(
                    message = banner.message,
                    icon = if (banner.success) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.ErrorOutline
                    },
                    containerColor = bg,
                    contentColor = fg,
                    iconTint = fg,
                    onDismiss = onDismissModelStatusBanner,
                    dismissContentDescription = "Dismiss status",
                    modifier = Modifier.testTag("settings_model_banner")
                )
            }
        }

        // 1. Appearance (Open Inline Segmented Bar)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modes = listOf(
                        AppThemeMode.SYSTEM to "System",
                        AppThemeMode.LIGHT to "Light",
                        AppThemeMode.DARK to "Dark"
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        val selected = uiState.themeMode == mode
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSetThemeMode(mode) }
                                .testTag("settings_theme_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                        if (index < modes.lastIndex) {
                            VerticalHairline(
                                height = 18.dp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Model Runtime & Import (Open Hairline-Separated Rows)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                Text(
                    text = "Model Runtime & Import",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                ModelSlotRow(
                    info = uiState.detectionModelInfo,
                    primaryImportLabel = "Import .onnx",
                    onPrimaryImport = { onnxPicker.launch(arrayOf("*/*")) },
                    secondaryImportLabel = null,
                    onSecondaryImport = null,
                    onLoad = onLoadDetectionModel,
                    onUnload = onUnloadDetectionModel,
                    onInspect = { onInspectModelMetadata(ModelSlot.PCB_DETECTION) }
                )

                HairlineDivider()

                ModelSlotRow(
                    info = uiState.transistorModelInfo,
                    primaryImportLabel = "Import .onnx",
                    onPrimaryImport = { transistorPicker.launch(arrayOf("*/*")) },
                    secondaryImportLabel = null,
                    onSecondaryImport = null,
                    onLoad = onLoadTransistorModel,
                    onUnload = onUnloadTransistorModel,
                    onInspect = { onInspectModelMetadata(ModelSlot.TRANSISTOR_ANOMALY) }
                )

                HairlineDivider()

                ModelSlotRow(
                    info = uiState.advisoryModelInfo,
                    primaryImportLabel = "Import .pte",
                    onPrimaryImport = { ptePicker.launch(arrayOf("*/*")) },
                    secondaryImportLabel = "Tokenizer",
                    onSecondaryImport = { tokenizerPicker.launch(arrayOf("*/*")) },
                    onLoad = onLoadAdvisoryModel,
                    onUnload = onUnloadAdvisoryModel,
                    onInspect = { onInspectModelMetadata(ModelSlot.AI_ADVISORY) }
                )
            }
        }

        // 3. Inspection Screening Policy (Open Section with Switches & Inline Resolution Selector)
        item {
            val policy = uiState.policyConfig
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                Text(
                    text = "Inspection Screening Policy",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = BharatSpacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${policy.profileName} • Provisional screening thresholds only; does not assert certified IPC-A-610 compliance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    PolicySwitchRow(
                        title = "Reject on Critical Defects",
                        subtitle = "Classifies open/short/missing hole/spurious copper as REJECT instead of REVIEW.",
                        checked = policy.rejectOnCritical,
                        testTag = "settings_policy_reject_critical",
                        onCheckedChange = { onUpdatePolicyConfig(policy.copy(rejectOnCritical = it)) }
                    )
                    HairlineDivider()
                    PolicySwitchRow(
                        title = "Review on Medium Defects",
                        subtitle = "Routes mouse bite / spur / solder bridge / tombstoning findings to REVIEW.",
                        checked = policy.reviewOnMedium,
                        testTag = "settings_policy_review_medium",
                        onCheckedChange = { onUpdatePolicyConfig(policy.copy(reviewOnMedium = it)) }
                    )
                    HairlineDivider()
                    PolicySwitchRow(
                        title = "Review on Low Severity Defects",
                        subtitle = "Escalates low-severity findings (e.g., pin hole / solder ball) to REVIEW.",
                        checked = policy.reviewOnLow,
                        testTag = "settings_policy_review_low",
                        onCheckedChange = { onUpdatePolicyConfig(policy.copy(reviewOnLow = it)) }
                    )
                    HairlineDivider()
                }

                Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)) {
                    Text(
                        text = "Minimum Optical Frame Resolution",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dims = listOf(224, 256, 320, 512)
                        dims.forEachIndexed { index, dim ->
                            val active = policy.minImageWidth == dim && policy.minImageHeight == dim
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                tonalElevation = 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        onUpdatePolicyConfig(
                                            policy.copy(minImageWidth = dim, minImageHeight = dim)
                                        )
                                    }
                                    .testTag("settings_min_dim_$dim")
                            ) {
                                Text(
                                    text = "${dim}×${dim}",
                                    style = MonoTextStyles.metadata,
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            if (index < dims.lastIndex) {
                                VerticalHairline(
                                    height = 16.dp,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Active Guard: Poor image clarity with zero detections always returns REVIEW (never PASS).",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted
                    )
                }

                OutlinedButton(
                    onClick = onResetPolicyDefaults,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .testTag("settings_reset_policy_button")
                ) {
                    Text(
                        text = "Reset Policy to Baseline Defaults",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // 4. Storage & CSV Audit (Open Technical Summary + Action Strip)
        item {
            val stats = uiState.storageStats
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                HairlineDivider()
                Text(
                    text = "Storage & CSV Audit",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TechnicalMetadataRow(
                        label = "Room Inspection Records",
                        value = uiState.totalRecordsCount.toString(),
                        labelWeight = 0.52f
                    )
                    TechnicalMetadataRow(
                        label = "CSV Outbox / Ledger",
                        value = "${stats.confirmedCsvLedgerCount} confirmed • ${stats.pendingCsvOutboxCount} pending",
                        labelWeight = 0.52f
                    )
                    TechnicalMetadataRow(
                        label = "Internal Audit Log Size",
                        value = formatBytesHuman(stats.auditFileSizeBytes),
                        labelWeight = 0.52f
                    )
                    TechnicalMetadataRow(
                        label = "Captured PCB Images",
                        value = formatBytesHuman(stats.imageStorageBytes),
                        labelWeight = 0.52f
                    )
                }

                if (uiState.storageActionMessage != null) {
                    TonalNoticeBanner(
                        message = uiState.storageActionMessage,
                        onClick = onClearStorageMessage,
                        modifier = Modifier.testTag("settings_storage_banner")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onReconcileCsvOutbox,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .testTag("settings_sync_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sync Audit",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { safExportLauncher.launch(suggestedAllCsvFilename) },
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .testTag("settings_export_all_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Export SAF",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRequestClearHistory,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.rejectFg),
                    border = BorderStroke(1.dp, colors.rejectFg.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .testTag("settings_clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Clear Inspection History",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // 5. About & Implementation Status (Editorial Footnotes on Canvas)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)) {
                HairlineDivider()
                Spacer(modifier = Modifier.height(BharatSpacing.xs))
                Text(
                    text = "About & Implementation Status",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Bharat AI PCB Inspector • Offline-First Native Edge AI",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "• Real camera & gallery inspections require an imported YOLOv8n .onnx file; unready production runtime returns INCOMPLETE.\n" +
                        "• Prototype Sample Boards execute strictly through DemoPcbDetectionEngine (isDemonstration=true).\n" +
                        "• Optional AI Technical Advisory uses ExecuTorch Android (.pte model + tokenizer) and never alters deterministic policy dispositions.\n" +
                        "• Dataset Note: 'copper' class defaults to REQUIRES_DATASET_VERIFICATION pending training schema confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(BharatSpacing.md))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSlotRow(
    info: ModelInfo,
    primaryImportLabel: String,
    onPrimaryImport: () -> Unit,
    secondaryImportLabel: String?,
    onSecondaryImport: (() -> Unit)?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onInspect: () -> Unit
) {
    val colors = LocalPcbColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(BharatSpacing.sm))
                ModelStateBadge(state = info.state)
            }

            Text(
                text = info.runtimeLabel,
                style = MonoTextStyles.compact,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = info.detailMessage,
                style = MonoTextStyles.compact,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (info.sha256 != null) {
                Text(
                    text = "SHA-256: ${info.sha256.take(16)}...",
                    style = MonoTextStyles.compact,
                    color = colors.textMuted
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Compact inline action strip with subtle hairline separators
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                InlineModelAction(
                    label = primaryImportLabel,
                    leadingIcon = Icons.Outlined.Description,
                    onClick = onPrimaryImport
                )

                if (secondaryImportLabel != null && onSecondaryImport != null) {
                    VerticalHairline(
                        height = 14.dp,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    InlineModelAction(
                        label = secondaryImportLabel,
                        onClick = onSecondaryImport
                    )
                }

                if (info.state == ModelRuntimeState.READY) {
                    VerticalHairline(
                        height = 14.dp,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    InlineModelAction(
                        label = "Unload",
                        onClick = onUnload
                    )
                } else if (info.artifactPath != null) {
                    VerticalHairline(
                        height = 14.dp,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    InlineModelAction(
                        label = "Load",
                        accent = true,
                        onClick = onLoad
                    )
                }

                VerticalHairline(
                    height = 14.dp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                InlineModelAction(
                    label = "Metadata",
                    trailingIcon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    onClick = onInspect
                )
            }
        }
    }
}

@Composable
private fun InlineModelAction(
    label: String,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(BharatSpacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainer,
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
