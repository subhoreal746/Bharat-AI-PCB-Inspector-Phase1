package com.example.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.ModelRuntimeState
import com.example.presentation.components.DemonstrationBadge
import com.example.presentation.components.DispositionBadge
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.ModelStateBadge
import com.example.presentation.components.TechnicalMetadataRow
import com.example.presentation.components.formatDurationLabel
import com.example.presentation.viewmodel.AdvisoryUiState
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.MonoTextStyles

@Composable
fun AdvisoryScreen(
    uiState: AdvisoryUiState,
    onBackClick: () -> Unit,
    onOpenSettingsClick: () -> Unit
) {
    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                text = "Inspection record unavailable",
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

    val advisory = record.advisory

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
                modifier = Modifier.testTag("advisory_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to Results",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Technical Advisory",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.inspectionId} • ${record.boardName}",
                    style = MonoTextStyles.inspectionId,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("advisory_screen"),
            contentPadding = PaddingValues(
                horizontal = BharatSpacing.content,
                vertical = BharatSpacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(BharatSpacing.content)
        ) {
            // Level-3 Authoritative Deterministic Policy Surface
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(BharatSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Authoritative Policy Disposition",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(BharatSpacing.sm))
                            DispositionBadge(disposition = record.officialDisposition)
                        }
                        Text(
                            text = "Advisory guidance is strictly explanatory and does not modify bounding boxes, defect counts, or official screening disposition.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (advisory.isDemonstration) {
                            DemonstrationBadge(label = "DEMO ADVISORY")
                        }
                    }
                }
            }

            if (advisory.availability != AdvisoryAvailability.AVAILABLE) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("advisory_unavailable_card")
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(BharatSpacing.sm))
                                    Text(
                                        text = "Offline Advisory Unavailable",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.width(BharatSpacing.sm))
                                val badgeState = when {
                                    uiState.advisoryModelInfo.state == ModelRuntimeState.READY ->
                                        ModelRuntimeState.ERROR
                                    else -> uiState.advisoryModelInfo.state
                                }
                                ModelStateBadge(state = badgeState)
                            }

                            Text(
                                text = advisory.unavailableReason
                                    ?: "SmolLM2 360M ExecuTorch runtime is not loaded. Import a compatible .pte model and tokenizer in Settings to generate local engineering notes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = onOpenSettingsClick,
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("advisory_open_settings_button")
                            ) {
                                Text(
                                    text = "Configure ExecuTorch in Settings",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            } else {
                // Open Editorial Report Sections (Directly on Canvas, No Section Cards)
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(BharatSpacing.content)
                    ) {
                        AdvisoryEditorialSection(
                            icon = Icons.Outlined.Layers,
                            title = "Severity & Screening Context",
                            body = advisory.severityClassification
                                ?: "${record.totalDefects} defect(s) flagged; policy disposition = ${record.officialDisposition.name}."
                        )
                        AdvisoryEditorialSection(
                            icon = Icons.Outlined.Build,
                            title = "Probable Root Cause",
                            body = advisory.probableRootCause ?: "Not provided."
                        )
                        AdvisoryEditorialSection(
                            icon = Icons.Outlined.CheckBox,
                            title = "Recommended Operator Action",
                            body = advisory.recommendedOperatorAction
                                ?: "Perform visual verification under microscope."
                        )
                        AdvisoryEditorialSection(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            title = "Relevant Standard",
                            body = advisory.relevantStandard ?: "None verified"
                        )
                        AdvisoryEditorialSection(
                            icon = Icons.Outlined.CenterFocusWeak,
                            title = "Limitations & Scope",
                            body = advisory.limitations
                                ?: "AI advisory is informational only and relies solely on optical bounding-box detections."
                        )
                    }
                }

                // Compact Technical Execution Metadata Surface (IBM Plex Mono)
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(BharatSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(BharatSpacing.xs)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Equalizer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(BharatSpacing.sm))
                                Text(
                                    text = "Advisory Execution Metadata",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HairlineDivider(modifier = Modifier.padding(vertical = BharatSpacing.sm))
                            TechnicalMetadataRow(
                                label = "Provenance",
                                value = advisory.provenanceSummary ?: "N/A",
                                labelWeight = 0.34f,
                                maxLines = 4
                            )
                            TechnicalMetadataRow(
                                label = "Latency",
                                value = formatDurationLabel(advisory.advisoryLatencyMs),
                                labelWeight = 0.34f
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(BharatSpacing.md))
                }
            }
        }
    }
}

@Composable
private fun AdvisoryEditorialSection(
    icon: ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
