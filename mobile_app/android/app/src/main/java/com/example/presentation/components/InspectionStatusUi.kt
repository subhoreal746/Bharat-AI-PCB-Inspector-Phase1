package com.example.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.DefectSeverity
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.ModelRuntimeState
import com.example.ui.theme.LocalPcbColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DispositionBadge(
    disposition: InspectionDisposition,
    modifier: Modifier = Modifier
) {
    val colors = LocalPcbColors.current
    val (fg, bg, icon, label) = when (disposition) {
        InspectionDisposition.PASS -> Quad(colors.passFg, colors.passBg, Icons.Filled.CheckCircle, "PASS")
        InspectionDisposition.REVIEW -> Quad(colors.reviewFg, colors.reviewBg, Icons.Filled.RemoveCircle, "REVIEW")
        InspectionDisposition.REJECT -> Quad(colors.rejectFg, colors.rejectBg, Icons.Filled.Cancel, "REJECT")
        InspectionDisposition.INCOMPLETE -> Quad(colors.incompleteFg, colors.incompleteBg, Icons.Filled.ErrorOutline, "INCOMPLETE")
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = bg,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg
            )
        }
    }
}

@Composable
fun SeverityChip(
    severity: DefectSeverity,
    modifier: Modifier = Modifier
) {
    val colors = LocalPcbColors.current
    val (fg, bg, label) = when (severity) {
        DefectSeverity.CRITICAL -> Triple(colors.rejectFg, colors.rejectBg, "CRITICAL")
        DefectSeverity.MEDIUM -> Triple(colors.reviewFg, colors.reviewBg, "MEDIUM")
        DefectSeverity.LOW -> Triple(colors.textSecondary, MaterialTheme.colorScheme.surfaceContainerHigh, "LOW")
        DefectSeverity.MANUAL_REVIEW -> Triple(colors.reviewFg, colors.reviewBg, "MANUAL REVIEW")
        DefectSeverity.REQUIRES_DATASET_VERIFICATION -> Triple(
            colors.incompleteFg,
            colors.incompleteBg,
            "VERIFY DATASET"
        )
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = bg,
        tonalElevation = 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun DemonstrationBadge(
    label: String = "DEMO DATA",
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ModelStateBadge(
    state: ModelRuntimeState,
    modifier: Modifier = Modifier
) {
    val colors = LocalPcbColors.current
    val (fg, bg, label) = when (state) {
        ModelRuntimeState.READY -> Triple(colors.passFg, colors.passBg, "READY")
        ModelRuntimeState.LOADING -> Triple(colors.reviewFg, colors.reviewBg, "LOADING")
        ModelRuntimeState.ERROR -> Triple(colors.rejectFg, colors.rejectBg, "ERROR")
        ModelRuntimeState.UNLOADED -> Triple(colors.incompleteFg, colors.incompleteBg, "UNLOADED")
        ModelRuntimeState.NOT_LOADED -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            "NOT LOADED"
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = bg,
        tonalElevation = 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun severityColor(severity: DefectSeverity, isDark: Boolean): Color {
    return when (severity) {
        DefectSeverity.CRITICAL -> if (isDark) Color(0xFFE86F6A) else Color(0xFFB3261E)
        DefectSeverity.MEDIUM -> if (isDark) Color(0xFFE5A93B) else Color(0xFFA85408)
        DefectSeverity.LOW -> if (isDark) Color(0xFF52C984) else Color(0xFF157A3C)
        DefectSeverity.MANUAL_REVIEW,
        DefectSeverity.REQUIRES_DATASET_VERIFICATION -> if (isDark) Color(0xFF9BA8AB) else Color(0xFF4A5C6A)
    }
}

fun formatInspectionTimestamp(epochMs: Long): String {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    return fmt.format(Date(epochMs))
}

fun formatDurationLabel(ms: Long?): String {
    return if (ms == null) "—" else "${ms} ms"
}

fun formatBytesHuman(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
