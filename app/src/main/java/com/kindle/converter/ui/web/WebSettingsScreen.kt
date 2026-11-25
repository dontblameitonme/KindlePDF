package com.kindle.converter.ui.web

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kindle.converter.ui.M3ExpressiveSlider
import com.kindle.converter.ui.M3SwitchRow
import com.kindle.converter.ui.m3GroupedItemShape
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel
import com.kindle.converter.web.settings.UaMode
import kotlinx.coroutines.launch

/**
 * 设置页（Android M3 Expressive 系统设置分组风格）：
 * 使用无描边色阶分层分组卡片，消除冗余解释文案，单屏清晰承载规则库、抓取偏好与配置备份。
 */
@Composable
fun WebSettingsScreen(
    viewModel: WebClipperViewModel,
    converterViewModel: ConverterViewModel? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scriptInfo by viewModel.scriptInfo.collectAsState(initial = null)
    val settings by viewModel.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.snackbar) {
        uiState.snackbar?.let {
            val duration = if (it.contains("失败")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, it, duration).show()
            viewModel.consumeSnackbar()
        }
    }

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val os = context.contentResolver.openOutputStream(uri)
                        ?: error("无法打开目标文件写入流")
                    os.use { out ->
                        viewModel.exportSettings(out)
                        out.flush()
                    }
                    Toast.makeText(context, "配置已导出", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val result = context.contentResolver.openInputStream(uri)?.use { input ->
                        viewModel.importSettings(input)
                    } ?: Result.failure(Exception("无法读取文件"))

                    if (result.isSuccess) {
                        converterViewModel?.reloadPresets()
                        Toast.makeText(context, result.getOrNull() ?: "配置已导入", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "导入失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 规则库
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionHeader("规则库")
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "内置规则",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = scriptInfo?.displayVersion ?: "内置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.updateScriptFromUrl() },
                            enabled = !uiState.isWorking,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            if (uiState.isWorking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("更新", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        IconButton(
                            onClick = { viewModel.resetScriptToAsset() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "重置规则",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. 网页抓取设置分组
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionHeader("网页抓取")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val totalRows = 5
                SettingsGroupedRow(index = 0, total = totalRows) {
                    M3SwitchRow(
                        label = "桌面版 UA",
                        checked = settings?.defaultUa == UaMode.DESKTOP,
                        onCheckedChange = { checked ->
                            viewModel.setDefaultUa(if (checked) UaMode.DESKTOP else UaMode.MOBILE)
                        }
                    )
                }
                SettingsGroupedRow(index = 1, total = totalRows) {
                    M3SwitchRow(
                        label = "下载正文插图",
                        checked = settings?.embedImages ?: true,
                        onCheckedChange = { viewModel.setEmbedImages(it) }
                    )
                }
                SettingsGroupedRow(index = 2, total = totalRows) {
                    M3SwitchRow(
                        label = "网页深色模式",
                        checked = settings?.webViewDarkMode ?: false,
                        onCheckedChange = { viewModel.setWebViewDarkMode(it) }
                    )
                }
                SettingsGroupedRow(index = 3, total = totalRows) {
                    M3SwitchRow(
                        label = "WebView 渲染",
                        checked = settings?.alwaysUseWebView ?: false,
                        onCheckedChange = { viewModel.setAlwaysUseWebView(it) }
                    )
                }
                SettingsGroupedRow(index = 4, total = totalRows) {
                    val threads = settings?.concurrency ?: 3
                    M3ExpressiveSlider(
                        label = "并发数",
                        valueText = "$threads",
                        value = threads.toFloat(),
                        onValueChange = { viewModel.setConcurrency(it.toInt()) },
                        valueRange = 1f..6f,
                        stepSize = 1f
                    )
                }
            }
        }

        // 3. 备份与恢复
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionHeader("备份")
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { exportSettingsLauncher.launch("kindle_pdf_settings.json") },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出", style = MaterialTheme.typography.labelLarge)
                    }

                    FilledTonalButton(
                        onClick = { importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SettingsGroupedRow(
    index: Int,
    total: Int,
    content: @Composable () -> Unit
) {
    Surface(
        shape = m3GroupedItemShape(index, total),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            content()
        }
    }
}
