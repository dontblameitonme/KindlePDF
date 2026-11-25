package com.kindle.converter.ui.web

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kindle.converter.ui.m3GroupedItemShape
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel
import com.kindle.converter.web.model.FetchMode
import com.kindle.converter.web.model.LinkItem
import com.kindle.converter.web.model.LinkStatus
import com.kindle.converter.web.model.WebArticle
import kotlinx.coroutines.launch
import java.io.File

/**
 * 网页剪藏主页面（Material 3 Expressive 风格）：
 * 顶部采用搜索胶囊输入区，中部为无描边分组章节列表（单行溢出菜单替换多排图标），
 * 底部保留双出口（EPUB / PDF）导出药丸栏，全程去除冗余引导文字。
 */
@Composable
fun WebClipperScreen(
    viewModel: WebClipperViewModel,
    converterViewModel: ConverterViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val converterState by converterViewModel.uiState.collectAsState()
    val webSettings by viewModel.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var activeBrowserUrl by remember { mutableStateOf<String?>(null) }

    val detectedUrlCount = remember(inputText) {
        if (inputText.isBlank()) 0
        else {
            val matches = Regex("""https?://[^\s<>"]+""").findAll(inputText).count()
            if (matches > 0) matches else inputText.trim().lines().count { it.isNotBlank() }
        }
    }

    val onExecuteExtract = {
        if (inputText.isNotBlank() && !state.isWorking) {
            viewModel.addUrls(inputText)
            inputText = ""
            viewModel.fetchRemaining()
        }
    }

    val epubSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) viewModel.resolvePendingExport(uri)
        else viewModel.cancelPendingExport()
    }

    val pdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.resolvePendingExport(uri)
        else viewModel.cancelPendingExport()
    }

    LaunchedEffect(state.pendingExport?.tempFile?.absolutePath) {
        val pending = state.pendingExport ?: return@LaunchedEffect
        if (pending.isPdf) {
            pdfSaveLauncher.launch(pending.suggestedName)
        } else {
            epubSaveLauncher.launch(pending.suggestedName)
        }
    }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            val duration = if (it.contains("失败") || it.contains("导出成功")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, it, duration).show()
            viewModel.consumeSnackbar()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // 1. 胶囊式链接输入区
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("粘贴链接", style = MaterialTheme.typography.bodyMedium)
                    },
                    minLines = 2,
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onExecuteExtract() })
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    inputText = if (inputText.isBlank()) clip else "$inputText\n$clip"
                                } else {
                                    Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("粘贴", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Surface(
                            onClick = {
                                val url = inputText.trim().lines().firstOrNull { it.isNotBlank() } ?: "https://www.zhihu.com"
                                activeBrowserUrl = url
                            },
                            enabled = !state.isWorking,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("浏览器", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (inputText.isNotBlank() || state.links.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    inputText = ""
                                    viewModel.clearAll()
                                },
                                enabled = !state.isWorking,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "清空",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onExecuteExtract,
                        enabled = inputText.isNotBlank() && !state.isWorking,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = if (detectedUrlCount > 0) "提取 ($detectedUrlCount)" else "提取",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 2. 列表标题与状态动作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.links.isEmpty()) "文章队列" else "${state.links.size} 篇文章",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (state.links.any { it.status is LinkStatus.Idle || it.status is LinkStatus.Error }) {
                TextButton(
                    onClick = { viewModel.fetchRemaining() },
                    enabled = !state.isWorking,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("重试失败", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 3. 分组章节列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(state.links, key = { _, item -> item.id }) { index, item ->
                LinkItemRow(
                    item = item,
                    index = index,
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "暂无文章",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. 进度条（抓取/导出中）
        if (state.isWorking) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = state.workingMessage.ifBlank { "提取中" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // 5. 底部双出口药丸栏
        val successCount = viewModel.articles.size
        if (successCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val params = converterState.params
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "就绪 $successCount 篇",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FilterChip(
                        selected = params.hideFrontToc,
                        onClick = { converterViewModel.updateParams(params.copy(hideFrontToc = !params.hideFrontToc)) },
                        label = { Text("跳过目录", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val defaultTitle = state.bookTitle.ifBlank {
                                viewModel.articles.firstOrNull()?.title ?: "网页精选集"
                            }
                            viewModel.prepareEpubExport(
                                title = defaultTitle,
                                disableFirstLineIndent = params.disableFirstLineIndent
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        enabled = !state.isWorking,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("EPUB", maxLines = 1)
                    }

                    Button(
                        onClick = {
                            viewModel.preparePdfExport(
                                params = params,
                                fontManager = converterViewModel.fontManager,
                                selectedFontId = converterState.selectedFontId
                            )
                        },
                        modifier = Modifier
                            .weight(1.45f)
                            .height(46.dp),
                        enabled = !state.isWorking,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出 PDF", maxLines = 1)
                    }
                }
            }
        }
    }

    val previewArticle = state.previewArticle
    if (previewArticle != null) {
        WebArticlePreviewDialog(
            article = previewArticle,
            onDismiss = { viewModel.closePreview() }
        )
    }

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
private fun LinkItemRow(
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status is LinkStatus.Success) { onClick() },
        shape = m3GroupedItemShape(index, total),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format("%02d", index + 1),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val primaryTitle = when (val status = item.status) {
                    is LinkStatus.Success -> status.article.title
                    is LinkStatus.Error -> status.message
                    else -> item.url
                }
                Text(
                    text = primaryTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (item.status is LinkStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusDot(item.status)
                    Text(
                        text = item.domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.status is LinkStatus.Success && item.status.article.possiblyTruncated) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = "可能截断",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            if (item.status is LinkStatus.Error) {
                FilledTonalIconButton(
                    onClick = { onOpenInBrowser(item.url) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Public, contentDescription = "浏览器", modifier = Modifier.size(16.dp))
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("内置浏览器打开") },
                        onClick = { menuExpanded = false; onOpenInBrowser(item.url) }
                    )
                    DropdownMenuItem(
                        text = { Text("重新抓取") },
                        onClick = { menuExpanded = false; onRefetch(FetchMode.DEFAULT) }
                    )
                    DropdownMenuItem(
                        text = { Text("桌面版抓取") },
                        onClick = { menuExpanded = false; onRefetch(FetchMode.DESKTOP) }
                    )
                    DropdownMenuItem(
                        text = { Text("WebView 渲染") },
                        onClick = { menuExpanded = false; onRefetch(FetchMode.WEBVIEW) }
                    )
                    HorizontalDivider()
                    if (index > 0) {
                        DropdownMenuItem(
                            text = { Text("上移") },
                            onClick = { menuExpanded = false; onMove(-1) }
                        )
                    }
                    if (index < total - 1) {
                        DropdownMenuItem(
                            text = { Text("下移") },
                            onClick = { menuExpanded = false; onMove(1) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onRemove() }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: LinkStatus) {
    when (status) {
        is LinkStatus.Fetching -> CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        is LinkStatus.Success -> Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF4ADE80))
        )
        is LinkStatus.Error -> Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        is LinkStatus.Idle -> Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline)
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
            shape = RoundedCornerShape(24.dp),
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
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Text(
                    text = article.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

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
            }
        }
    }
}
