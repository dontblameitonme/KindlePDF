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

@Composable
fun WebSettingsScreen(
    viewModel: WebClipperViewModel,
    modifier: Modifier = Modifier
) {
    val scriptInfo by viewModel.scriptInfo.collectAsState(initial = null)
    val settings by viewModel.settings.collectAsState(initial = null)
    val scrollState = rememberScrollState()

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
