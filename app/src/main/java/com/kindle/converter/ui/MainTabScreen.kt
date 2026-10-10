package com.kindle.converter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kindle.converter.ui.web.WebClipperScreen
import com.kindle.converter.ui.web.WebSettingsScreen
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel

enum class MainTab(
    val title: String,
    val subtitle: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
) {
    WEB(
        title = "网页剪藏 (Web Clipper)",
        subtitle = "批量提取文章并转为电子书",
        selectedIcon = Icons.Filled.Language,
        unselectedIcon = Icons.Outlined.Language,
        label = "网页剪藏"
    ),
    FILES(
        title = "Kindle PDF Converter",
        subtitle = "本地文档转 6 英寸墨水屏 PDF",
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        label = "本地文档"
    ),
    SETTINGS(
        title = "设置与反截断规则",
        subtitle = "用户脚本管理与全局策略",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        label = "设置规则"
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedTab.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedTab.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
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
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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
                    showTopBar = false
                )
                MainTab.SETTINGS -> WebSettingsScreen(
                    viewModel = webViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
