package com.example.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.navigation.TopLevelDestination
import com.example.ui.theme.LocalPcbColors

@Composable
fun AdaptiveInspectionScaffold(
    widthSizeClass: WindowWidthSizeClass,
    currentTopLevel: TopLevelDestination?,
    onSelectDestination: (TopLevelDestination) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val colors = LocalPcbColors.current
    val isCompact = widthSizeClass == WindowWidthSizeClass.Compact

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.canvas,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = snackbarHost,
        bottomBar = {
            if (isCompact && currentTopLevel != null) {
                PillBottomNavigationBar(
                    selected = currentTopLevel,
                    onSelect = onSelectDestination
                )
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isCompact && currentTopLevel != null) {
                AdaptiveNavigationRail(
                    selected = currentTopLevel,
                    onSelect = onSelectDestination
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                content(
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 840.dp)
                )
            }
        }
    }
}

@Composable
private fun PillBottomNavigationBar(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit
) {
    val colors = LocalPcbColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.navContainer,
            border = BorderStroke(1.dp, colors.navBorder),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopLevelDestination.entries.forEach { destination ->
                    val isSelected = destination == selected
                    val icon = destinationIcon(destination, isSelected)
                    val containerColor = if (isSelected) colors.navSelectedPill else colors.navContainer
                    val contentColor = if (isSelected) colors.onNavSelectedPill else colors.navUnselected

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(containerColor)
                            .clickable(role = Role.Tab) { onSelect(destination) }
                            .testTag(destination.testTag)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = destination.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveNavigationRail(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit
) {
    val colors = LocalPcbColors.current
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = colors.navContainer
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                modifier = Modifier.testTag(destination.testTag),
                icon = {
                    Icon(
                        imageVector = destinationIcon(destination, isSelected),
                        contentDescription = destination.label
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.onNavSelectedPill,
                    selectedTextColor = colors.textPrimary,
                    indicatorColor = colors.navSelectedPill,
                    unselectedIconColor = colors.navUnselected,
                    unselectedTextColor = colors.navUnselected
                )
            )
        }
    }
}

private fun destinationIcon(destination: TopLevelDestination, selected: Boolean): ImageVector {
    return when (destination) {
        TopLevelDestination.DASHBOARD -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        TopLevelDestination.INSPECT -> if (selected) Icons.Filled.CenterFocusWeak else Icons.Outlined.CenterFocusWeak
        TopLevelDestination.HISTORY -> if (selected) Icons.Filled.History else Icons.Outlined.History
        TopLevelDestination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    }
}
