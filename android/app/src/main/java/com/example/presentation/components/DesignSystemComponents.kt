package com.example.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles

@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = color
    )
}

@Composable
fun VerticalHairline(
    height: Dp = 20.dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    VerticalDivider(
        modifier = modifier.height(height),
        thickness = 1.dp,
        color = color
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionTestTag: String? = null,
    onActionClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        when {
            trailing != null -> trailing()
            actionLabel != null && onActionClick != null -> {
                TextButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TonalNoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onDismiss: (() -> Unit)? = null,
    dismissContentDescription: String = "Dismiss",
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val surfaceMod = if (onClick != null) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = 0.dp,
        modifier = surfaceMod
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BharatSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
            if (actionLabel != null && onActionClick != null) {
                Spacer(modifier = Modifier.width(BharatSpacing.xs))
                TextButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = dismissContentDescription,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TechnicalMetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelWeight: Float = 0.40f,
    maxLines: Int = 2
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(BharatSpacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(labelWeight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MonoTextStyles.metadata,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f - labelWeight),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ClarityStatusBadge(
    lowClarityRisk: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalPcbColors.current
    val (fg, bg, label) = if (lowClarityRisk) {
        Triple(colors.reviewFg, colors.reviewBg, "Low Clarity Risk")
    } else {
        Triple(colors.passFg, colors.passBg, "Clarity OK")
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
