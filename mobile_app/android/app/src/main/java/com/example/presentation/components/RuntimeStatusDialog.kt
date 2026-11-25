package com.example.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.ModelInfo
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.MonoTextStyles

@Composable
fun RuntimeStatusDialog(
    detectionModelInfo: ModelInfo,
    advisoryModelInfo: ModelInfo,
    transistorModelInfo: ModelInfo? = null,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = "On-Device AI Runtime Status",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)) {
                Text(
                    text = "Real camera and gallery inspections require imported, loaded on-device models. Sample boards run through the isolated demo simulator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(BharatSpacing.xs))
                RuntimeDialogSlotRow(info = detectionModelInfo)
                transistorModelInfo?.let {
                    HairlineDivider()
                    RuntimeDialogSlotRow(info = it)
                }
                HairlineDivider()
                RuntimeDialogSlotRow(info = advisoryModelInfo)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("runtime_dialog_open_settings")
            ) {
                Text(
                    text = "Manage Models",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun RuntimeDialogSlotRow(info: ModelInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BharatSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
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
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}
