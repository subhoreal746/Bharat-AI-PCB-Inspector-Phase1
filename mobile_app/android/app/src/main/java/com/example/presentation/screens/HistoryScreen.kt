package com.example.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.model.InspectionRecord
import com.example.presentation.components.BrandHeader
import com.example.presentation.components.DispositionBadge
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.TonalNoticeBanner
import com.example.presentation.components.formatInspectionTimestamp
import com.example.presentation.viewmodel.HistoryDispositionFilter
import com.example.presentation.viewmodel.HistorySortOrder
import com.example.presentation.viewmodel.HistoryUiState
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onSearchQueryChanged: (String) -> Unit,
    onDispositionFilterSelected: (HistoryDispositionFilter) -> Unit,
    onSortOrderSelected: (HistorySortOrder) -> Unit,
    onStageExportAllCsv: () -> String,
    onStageExportSingleCsv: (InspectionRecord) -> String,
    onSafUriSelected: (Uri) -> Unit,
    onShareAllCsv: ((Uri) -> Unit) -> Unit,
    onShareRecordCsv: (InspectionRecord, (Uri) -> Unit) -> Unit,
    onRecordClick: (String) -> Unit,
    onClearStatusMessage: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalPcbColors.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val safCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) onSafUriSelected(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("history_screen"),
        contentPadding = PaddingValues(
            horizontal = BharatSpacing.content,
            vertical = BharatSpacing.sm
        ),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)
    ) {
        item {
            BrandHeader()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inspection History",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${uiState.totalRecordsCount} stored inspection record(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.totalRecordsCount > 0) {
                    Row {
                        IconButton(
                            onClick = {
                                val fileName = onStageExportAllCsv()
                                safCreateLauncher.launch(fileName)
                            },
                            modifier = Modifier.testTag("history_export_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Export all records CSV",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = {
                                onShareAllCsv { shareUri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share Inspection History CSV")
                                    )
                                }
                            },
                            modifier = Modifier.testTag("history_share_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Share all records CSV",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }

        if (uiState.statusMessage != null) {
            item {
                TonalNoticeBanner(
                    message = uiState.statusMessage,
                    onClick = onClearStatusMessage
                )
            }
        }

        // Clean Borderless Tonal Material 3 Search Field
        item {
            TextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = {
                    Text(
                        text = "Search ID, board name, or defect class",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input")
            )
        }

        // Compact Material 3 FilterChip Strip + Sort Dropdown
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HistoryDispositionFilter.entries.forEach { filter ->
                        val selected = uiState.dispositionFilter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { onDispositionFilterSelected(filter) },
                            label = {
                                Text(
                                    text = filter.label,
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
                            modifier = Modifier.testTag("history_filter_${filter.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(BharatSpacing.sm))

                Box {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .clickable { sortMenuExpanded = true }
                            .testTag("history_sort_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.sortOrder.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Sort order",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        HistorySortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = order.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortOrderSelected(order)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (uiState.dispositionFilter != HistoryDispositionFilter.ALL) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_active_filter_bar"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filtered by disposition: ${uiState.dispositionFilter.label} (${uiState.filteredRecords.size} record(s))",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onDispositionFilterSelected(HistoryDispositionFilter.ALL) },
                        modifier = Modifier.testTag("history_clear_filter_button")
                    ) {
                        Text(
                            text = "Clear filter",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        if (uiState.filteredRecords.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_empty_state")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = if (uiState.totalRecordsCount == 0) {
                                "No inspection records yet"
                            } else {
                                "No records match the current filter"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (uiState.totalRecordsCount == 0) {
                                "Completed PCB inspections are saved locally in Room and automatically logged to CSV."
                            } else {
                                "Try clearing the search query or switching the disposition filter."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Open Chronological List Directly on Canvas Separated by 1dp Hairlines
            itemsIndexed(
                items = uiState.filteredRecords,
                key = { _, rec -> rec.inspectionId }
            ) { index, record ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    HistoryRecordRow(
                        record = record,
                        onClick = { onRecordClick(record.inspectionId) },
                        onExportSaf = {
                            val name = onStageExportSingleCsv(record)
                            safCreateLauncher.launch(name)
                        },
                        onShare = {
                            onShareRecordCsv(record) { shareUri ->
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share ${record.inspectionId} CSV")
                                )
                            }
                        }
                    )
                    if (index < uiState.filteredRecords.lastIndex) {
                        HairlineDivider()
                    } else {
                        Spacer(modifier = Modifier.height(BharatSpacing.md))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordRow(
    record: InspectionRecord,
    onClick: () -> Unit,
    onExportSaf: () -> Unit,
    onShare: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("history_item_${record.inspectionId}"),
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
                style = MonoTextStyles.inspectionId,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = record.boardName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(BharatSpacing.sm))
        DispositionBadge(disposition = record.officialDisposition)
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Record export actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Save CSV via SAF",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onExportSaf()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Share CSV",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onShare()
                    }
                )
            }
        }
    }
}
