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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 用户脚本《骚扰拦截》卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "用户脚本《骚扰拦截》",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "移动端网页反弹窗与自动展开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            scriptInfo?.displayVersion ?: "v1.5.9",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    "内置由 airbash 编写的《骚扰拦截》开源规则库（GPL-3.0），覆盖知乎、贴吧、百家号、CSDN、简书等 70+ 主流站点。抓取时自动阻断“打开 App”强制弹窗，并自动点击“展开全文”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f
                )

                if (scriptInfo != null && scriptInfo!!.updatedAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "更新时间：${DateFormat.format("yyyy-MM-dd HH:mm", Date(scriptInfo!!.updatedAt))} · 来源：${scriptInfo!!.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.updateScriptFromUrl() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("检查规则更新")
                    }

                    OutlinedButton(
                        onClick = { viewModel.resetScriptToAsset() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复内置规则")
                    }
                }
            }
        }

        // 网络抓取偏好卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "抓取与网络偏好",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))

                // UA 设置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("默认请求 User-Agent", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("桌面 UA (PC端) 几乎不存在“打开App”弹窗", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings?.defaultUa == UaMode.DESKTOP,
                        onCheckedChange = { checked ->
                            viewModel.setDefaultUa(if (checked) UaMode.DESKTOP else UaMode.MOBILE)
                        }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // 图片嵌入
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载并嵌入正文插图", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("在 Kindle PDF / EPUB 中保留原网页插图", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings?.embedImages ?: true,
                        onCheckedChange = { viewModel.setEmbedImages(it) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // 网页全局深色模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("网页全局深色模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("内置浏览器与网页渲染时自动适配舒适深色背景（永久保存选择状态）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings?.webViewDarkMode ?: false,
                        onCheckedChange = { viewModel.setWebViewDarkMode(it) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // 直连 WebView 域名列表
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("直连 WebView 渲染域名（免403）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("命中的域名直接调用后台 Chromium 渲染，彻底避免 403 拦截（支持以逗号或换行分隔）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    var domainText by remember(settings?.directWebViewDomains) {
                        mutableStateOf(settings?.directWebViewDomains ?: "zhihu.com, zhuanlan.zhihu.com, juejin.cn")
                    }
                    OutlinedTextField(
                        value = domainText,
                        onValueChange = {
                            domainText = it
                            viewModel.setDirectWebViewDomains(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // 始终使用 WebView 渲染
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("始终使用 WebView 渲染引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("所有网页直接调用 Chromium 内核完整渲染，兼容性最高但速度较慢。默认关闭（智能双轨模式，优先 0.3s 极速通道，遇反爬自动升级）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings?.alwaysUseWebView ?: false,
                        onCheckedChange = { viewModel.setAlwaysUseWebView(it) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // 并发数
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("并发下载任务数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${settings?.concurrency ?: 3} 个线程", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = (settings?.concurrency ?: 3).toFloat(),
                        onValueChange = { viewModel.setConcurrency(it.toInt()) },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                }
            }
        }

        // 设置备份与迁移卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "设置备份与迁移",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "备份/恢复排版预设、网页规则与配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.Backup,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    "导出格式为通用 JSON 文件，包含所有已保存的排版参数预设、界面状态、网页抓取配置（UA、图片嵌入、并发数）以及自定义反截断脚本。更换手机或重装应用时可一键导入恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            exportSettingsLauncher.launch("kindle_pdf_settings.json")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出设置")
                    }

                    OutlinedButton(
                        onClick = {
                            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入设置")
                    }
                }
            }
        }

        // 功能特性说明卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("三级反截断策略说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. 默认桌面 UA：PC 端页面通常包含完整正文且无移动端引导弹窗；\n" +
                    "2. 静态清洗与骚扰拦截：屏蔽 70+ 中文网站 App 诱导框并放开隐藏内容；\n" +
                    "3. WebView 离线渲染兜底：对重度 JS 动态加载的页面，真实执行 JS 自动点击“展开全文”，导出最终完整 DOM。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f
                )
            }
        }
    }
}
