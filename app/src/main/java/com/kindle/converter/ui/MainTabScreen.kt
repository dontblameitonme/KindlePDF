package com.kindle.converter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kindle.converter.ui.web.WebClipperScreen
import com.kindle.converter.ui.web.WebSettingsScreen
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel

enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
) {
    WEB(
        title = "网页",
        selectedIcon = Icons.Filled.Language,
        unselectedIcon = Icons.Outlined.Language,
        label = "网页"
    ),
    FILES(
        title = "排版",
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        label = "排版"
    ),
    SETTINGS(
        title = "设置",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        label = "设置"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    webViewModel: WebClipperViewModel = viewModel(),
    converterViewModel: ConverterViewModel = viewModel(),
    selectedTab: MainTab = MainTab.WEB,
    onTabSelected: (MainTab) -> Unit = {}
) {
    val converterState by converterViewModel.uiState.collectAsState()
    var showFontSheet by remember { mutableStateOf(false) }
    var showPresetSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedTab.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (selectedTab == MainTab.FILES) {
                        FontSelectorPillAction(
                            fontName = converterState.fontName ?: "系统默认",
                            onClick = { showFontSheet = true }
                        )
                        IconButton(onClick = { showPresetSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmarks,
                                contentDescription = "预设",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp
            ) {
                MainTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.WEB -> WebClipperScreen(
                    viewModel = webViewModel,
                    converterViewModel = converterViewModel,
                    modifier = Modifier.fillMaxSize()
                )
                MainTab.FILES -> ConverterScreen(
                    viewModel = converterViewModel,
                    modifier = Modifier.fillMaxSize(),
                    showTopBar = false,
                    showFontSheet = showFontSheet,
                    onShowFontSheetChange = { showFontSheet = it },
                    showPresetSheet = showPresetSheet,
                    onShowPresetSheetChange = { showPresetSheet = it }
                )
                MainTab.SETTINGS -> WebSettingsScreen(
                    viewModel = webViewModel,
                    converterViewModel = converterViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 顶栏字体选择药丸按钮：点击直接从底部拉起字体切换面板。
 */
@Composable
internal fun FontSelectorPillAction(
    fontName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .padding(end = 4.dp)
            .height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FontDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = fontName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp)
            )
        }
    }
}
