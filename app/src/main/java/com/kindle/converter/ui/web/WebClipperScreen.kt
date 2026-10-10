package com.kindle.converter.ui.web

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.pdf.FontManager
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel
import com.kindle.converter.web.model.FetchMode
import com.kindle.converter.web.model.LinkItem
import com.kindle.converter.web.model.LinkStatus
import com.kindle.converter.web.model.WebArticle
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebClipperScreen(
    viewModel: WebClipperViewModel,
    converterViewModel: ConverterViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val webSettings by viewModel.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var activeBrowserUrl by remember { mutableStateOf<String?>(null) }
    var showPdfConfirmDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatusText by remember { mutableStateOf("") }

    // EPUB 导出 SAF Launcher
    val epubSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExporting = true
                exportStatusText = "正在生成 EPUB 电子书…"
                val tempFile = File(context.cacheDir, "web_${System.currentTimeMillis()}.epub")
                val res = viewModel.exportEpub(
                    title = state.bookTitle.ifBlank { state.links.firstOrNull { it.status is LinkStatus.Success }?.let { (it.status as LinkStatus.Success).article.title } ?: "网页精选集" },
                    outputFile = tempFile
                )
                if (res.isSuccess) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            tempFile.inputStream().use { it.copyTo(os) }
                        }
                        Toast.makeText(context, "EPUB 导出成功！", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "EPUB 生成失败：${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
                isExporting = false
                exportStatusText = ""
            }
        }
    }

    // PDF 导出 SAF Launcher
    val pdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExporting = true
                exportStatusText = "正在进行 6 英寸 Kindle 深度排版…"
                val tempPdf = File(context.cacheDir, "web_kindle_${System.currentTimeMillis()}.pdf")
                val fontManager = converterViewModel.fontManager
                val selectedId = converterViewModel.uiState.value.selectedFontId
                if (selectedId != null && fontManager.getCurrentEntry()?.id != selectedId) {
                    fontManager.selectFont(selectedId)
                }
                val params = converterViewModel.uiState.value.params
                val res = viewModel.exportKindlePdf(params, fontManager, tempPdf)
                if (res.isSuccess) {
                    val (file, pages) = res.getOrThrow()
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            file.inputStream().use { it.copyTo(os) }
                        }
                        Toast.makeText(context, "Kindle PDF 导出成功（共 $pages 页）！", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "PDF 生成失败：${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
                isExporting = false
                exportStatusText = ""
            }
        }
    }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeSnackbar()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 链接输入与操作卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "粘贴网页链接",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "支持批量（空格或换行分隔）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "支持知乎、微信公众号、CSDN、博客等任何公开网页链接…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            inputText = "https://zhuanlan.zhihu.com/p/65928174\nhttps://mp.weixin.qq.com/s/sample_article"
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("＋ 填入测试链接", style = MaterialTheme.typography.labelSmall)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val url = inputText.trim().lines().firstOrNull { it.isNotBlank() } ?: "https://www.zhihu.com"
                                activeBrowserUrl = url
                            },
                            enabled = !state.isWorking,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("内置浏览器")
                        }

                        if (inputText.isNotBlank() || state.links.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    inputText = ""
                                    viewModel.clearAll()
                                },
                                enabled = !state.isWorking,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("清空")
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.addUrls(inputText)
                                inputText = ""
                                viewModel.fetchRemaining()
                            },
                            enabled = inputText.isNotBlank() && !state.isWorking,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加并提取")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 提取文章列表标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "文章章节列表（${state.links.size} 篇）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (state.links.any { it.status is LinkStatus.Idle || it.status is LinkStatus.Error }) {
                TextButton(
                    onClick = { viewModel.fetchRemaining() },
                    enabled = !state.isWorking
                ) {
                    Text("抓取未完成项", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 链接卡片列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.links, key = { it.id }) { item ->
                LinkItemCard(
                    item = item,
                    index = state.links.indexOf(item),
                    total = state.links.size,
                    enabled = !state.isWorking,
                    onClick = {
                        if (item.status is LinkStatus.Success) {
                            viewModel.showPreview(item.status.article)
                        }
                    },
                    onRemove = { viewModel.removeLink(item.id) },
                    onMove = { delta -> viewModel.moveLink(item.id, delta) },
                    onRefetch = { mode -> viewModel.refetch(item.id, mode) },
                    onOpenInBrowser = { url -> activeBrowserUrl = url }
                )
            }

            if (state.links.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无链接。\n粘贴网址后点击「添加并提取」，\n自动拦截 App 弹窗、去广告并生成电子书。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // 正在处理状态
        if (state.isWorking || isExporting) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isExporting) exportStatusText else state.workingMessage.ifBlank { "正在提取正文…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // 底部双出口操作区
        val successCount = viewModel.articles.size
        if (successCount > 0) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已成功提取 $successCount 篇文章",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "可选择导出形式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 出口 1：Kindle PDF
                        Button(
                            onClick = {
                                val defaultName = viewModel.articles.firstOrNull()?.title?.take(18) ?: "网页精选"
                                pdfSaveLauncher.launch("$defaultName-Kindle版.pdf")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isWorking && !isExporting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("转为 Kindle PDF", maxLines = 1)
                        }

                        // 出口 2：标准 EPUB
                        OutlinedButton(
                            onClick = {
                                val defaultName = viewModel.articles.firstOrNull()?.title?.take(18) ?: "网页精选"
                                epubSaveLauncher.launch("$defaultName.epub")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isWorking && !isExporting,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出为 EPUB", maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // 正文纯净预览弹窗
    val previewArticle = state.previewArticle
    if (previewArticle != null) {
        WebArticlePreviewDialog(
            article = previewArticle,
            onDismiss = { viewModel.closePreview() }
        )
    }

    // Via 风格轻量级内置浏览器
    val browserUrl = activeBrowserUrl
    if (browserUrl != null) {
        InAppBrowserDialog(
            initialUrl = browserUrl,
            initialDarkMode = webSettings?.webViewDarkMode ?: false,
            onDismiss = { activeBrowserUrl = null },
            onToggleDarkMode = { viewModel.setWebViewDarkMode(it) },
            onClipCurrentPage = { url, html, title ->
                viewModel.addFromHtml(url, html, title)
            }
        )
    }
}

@Composable
private fun LinkItemCard(
    item: LinkItem,
    index: Int,
    total: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onRefetch: (FetchMode) -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status is LinkStatus.Success) { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "第 ${index + 1} 章",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusBadge(item.status)
            }

            when (val status = item.status) {
                is LinkStatus.Success -> {
                    Text(
                        text = status.article.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (status.article.possiblyTruncated) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "正文疑似被截断，可点右下角菜单选择「WebView 渲染」强力展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                is LinkStatus.Error -> {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { onOpenInBrowser(item.url) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("在内置浏览器中打开验证", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                else -> {}
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新抓取", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("在内置浏览器中打开（所见即所得）") },
                            onClick = { menuExpanded = false; onOpenInBrowser(item.url) }
                        )
                        DropdownMenuItem(
                            text = { Text("默认重新抓取") },
                            onClick = { menuExpanded = false; onRefetch(FetchMode.DEFAULT) }
                        )
                        DropdownMenuItem(
                            text = { Text("强制桌面 UA（PC版网页）") },
                            onClick = { menuExpanded = false; onRefetch(FetchMode.DESKTOP) }
                        )
                        DropdownMenuItem(
                            text = { Text("后台 WebView 渲染（自动展开/拦截App）") },
                            onClick = { menuExpanded = false; onRefetch(FetchMode.WEBVIEW) }
                        )
                    }
                }
                IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onMove(1) }, enabled = enabled && index < total - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: LinkStatus) {
    when (status) {
        is LinkStatus.Idle -> Text(
            "等待", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is LinkStatus.Fetching -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        is LinkStatus.Success -> Text(
            "成功", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        is LinkStatus.Error -> Text(
            "失败", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun WebArticlePreviewDialog(
    article: WebArticle,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "纯净正文预览",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    article.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                val scrollState = rememberScrollState()
                val textOnly = remember(article.contentHtml) {
                    org.jsoup.Jsoup.parse(article.contentHtml).text()
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = textOnly,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4f
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("关闭预览")
                    }
                }
            }
        }
    }
}
