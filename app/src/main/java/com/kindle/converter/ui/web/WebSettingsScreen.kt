package com.kindle.converter.ui.web

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kindle.converter.viewmodel.WebClipperViewModel
import com.kindle.converter.web.settings.UaMode
import java.util.Date

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.kindle.converter.viewmodel.ConverterViewModel
import kotlinx.coroutines.launch

@Composable
fun WebSettingsScreen(
    viewModel: WebClipperViewModel,
    converterViewModel: ConverterViewModel? = null,
    modifier: Modifier = Modifier
) {
    val scriptInfo by viewModel.scriptInfo.collectAsState(initial = null)
    val settings by viewModel.settings.collectAsState(initial = null)
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        viewModel.exportSettings(out)
                    }
                    Toast.makeText(context, "设置与排版预设已成功导出", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
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
                    } ?: Result.failure(Exception("无法打开文件流"))

                    if (result.isSuccess) {
                        converterViewModel?.reloadPresets()
                        Toast.makeText(context, result.getOrNull() ?: "设置导入成功", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "导入失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "读取设置文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 规则库
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("规则库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            scriptInfo?.displayVersion ?: "内置",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.updateScriptFromUrl() },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("更新规则", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = { viewModel.resetScriptToAsset() },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重置规则", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 抓取偏好
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("抓取设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // UA 设置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("桌面端 User-Agent", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings?.defaultUa == UaMode.DESKTOP,
                        onCheckedChange = { checked ->
                            viewModel.setDefaultUa(if (checked) UaMode.DESKTOP else UaMode.MOBILE)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // 图片嵌入
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("下载正文插图", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings?.embedImages ?: true,
                        onCheckedChange = { viewModel.setEmbedImages(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // 网页深色模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("网页深色模式", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings?.webViewDarkMode ?: false,
                        onCheckedChange = { viewModel.setWebViewDarkMode(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // 始终使用 WebView 渲染
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全量 WebView 渲染", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings?.alwaysUseWebView ?: false,
                        onCheckedChange = { viewModel.setAlwaysUseWebView(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // 并发数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("并发线程: ${settings?.concurrency ?: 3}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = (settings?.concurrency ?: 3).toFloat(),
                        onValueChange = { viewModel.setConcurrency(it.toInt()) },
                        valueRange = 1f..6f,
                        steps = 4,
                        modifier = Modifier.width(140.dp)
                    )
                }
            }
        }

        // 备份与恢复
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("备份与恢复", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            exportSettingsLauncher.launch("kindle_pdf_settings.json")
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出配置", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入配置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
