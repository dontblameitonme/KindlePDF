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
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
) {
    WEB(
        title = "网页剪藏",
        selectedIcon = Icons.Filled.Language,
        unselectedIcon = Icons.Outlined.Language,
        label = "网页"
    ),
    FILES(
        title = "电子书排版",
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedTab.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    converterViewModel = converterViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
