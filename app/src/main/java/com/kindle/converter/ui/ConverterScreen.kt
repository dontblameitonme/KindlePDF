package com.kindle.converter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kindle.converter.data.*
import com.kindle.converter.pdf.FontManager
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.viewmodel.ConverterViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true
) {
    val state by viewModel.uiState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCustomFont(uri)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.resolvePendingSave(uri)
        else viewModel.cancelPendingSave()
    }

    LaunchedEffect(state.pendingSaves.firstOrNull()?.tempFilePath) {
        val pending = state.pendingSaves.firstOrNull()
        if (pending != null) saveLauncher.launch(pending.suggestedName)
    }

    var showFontDialog by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var showPresetSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "电子书排版",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (state.isConverting) {
                        LinearProgressIndicator(
                            progress = { state.conversionProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (state.files.isNotEmpty()) viewModel.openPreviewDialog()
                                    else viewModel.previewSample()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                                enabled = !state.isTypesetting && state.fontName != null,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (state.isTypesetting) "生成中…"
                                    else if (state.files.isNotEmpty()) "预览排版"
                                    else "预览示例"
                                )
                            }

                            Button(
                                onClick = {
                                    if (state.files.isNotEmpty()) viewModel.convertAll()
                                    else filePickerLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !state.isConverting
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (state.files.isNotEmpty()) "转换为 PDF (${state.files.size})" else "选择文件")
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶栏：标题与两枚轻量图标按钮 (字体 / 预设)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "电子书排版",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showFontDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.FontDownload, contentDescription = "字体设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(
                        onClick = { showPresetDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "预设管理", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 1. 书籍卡片 (未导入时简洁引导，导入后轻巧展示，彻底告别占用巨屏的大长框)
            if (state.files.isEmpty()) {
                Surface(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "text/markdown",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/epub+zip",
                                "application/x-mobipocket-ebook",
                                "application/vnd.amazon.ebook",
                                "application/pdf",
                                "text/html",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("点击选择文件", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("支持 TXT / Markdown / EPUB / DOCX / MOBI / PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val firstFile = state.files.first()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    firstFile.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${formatFileSize(firstFile.size)} · 字体: ${state.fontName ?: "系统默认"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            ) {
                                Text("更换", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // 多文件横向流 (多于 1 个文件时呈现)
                        if (state.files.size > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(state.files) { index, file ->
                                    InputChip(
                                        selected = false,
                                        onClick = { },
                                        label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "移除",
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable { viewModel.removeFile(index) }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. 核心排版主卡片 (只放 4 个最关心的阅读读感项，呼吸感充足，彻底没有杂乱感！)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 水平对齐：4 个优雅排版矢量图标 (左/中/右/两端)，完全无文字，彻底杜绝换行断行！
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                            listOf(
                                TextAlignment.LEFT to Icons.AutoMirrored.Filled.FormatAlignLeft,
                                TextAlignment.CENTER to Icons.Default.FormatAlignCenter,
                                TextAlignment.RIGHT to Icons.AutoMirrored.Filled.FormatAlignRight,
                                TextAlignment.JUSTIFY to Icons.Default.FormatAlignJustify
                            ).forEach { (alignment, icon) ->
                                val isSelected = state.params.textAlignment == alignment
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                            else androidx.compose.ui.graphics.Color.Transparent
                                        )
                                        .clickable { viewModel.updateParams(state.params.copy(textAlignment = alignment)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = alignment.displayName,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 正文字号：全宽通栏滑块
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.FormatSize, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Text("正文字号", style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${state.params.fontSize.toInt()} pt", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = state.params.fontSize,
                            onValueChange = { viewModel.updateParams(state.params.copy(fontSize = it)) },
                            valueRange = 8f..24f,
                            steps = 15,
                            modifier = Modifier.fillMaxWidth().height(30.dp)
                        )
                    }

                    // 行间距：全宽通栏滑块
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.FormatLineSpacing, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Text("行间距", style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${String.format("%.1f", state.params.lineHeight)} x", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = state.params.lineHeight,
                            onValueChange = { viewModel.updateParams(state.params.copy(lineHeight = it)) },
                            valueRange = 1.0f..2.4f,
                            steps = 13,
                            modifier = Modifier.fillMaxWidth().height(30.dp)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 取消段首缩进：行级开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("取消段首缩进", style = MaterialTheme.typography.bodyMedium)
                            Text("段落首行顶格排版，电子书同步清零 2em 缩进", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.params.disableFirstLineIndent,
                            onCheckedChange = {
                                viewModel.updateParams(state.params.copy(disableFirstLineIndent = it))
                            }
                        )
                    }

                    // 【核心新特性】：各类缩进幅度自由自定义 (0% ~ 100% 连续滑块 + 快捷胶囊)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val currentPercent = (state.params.secondaryIndentRatio * 100).roundToInt()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("各类缩进幅度 (引用/列表/代码)", style = MaterialTheme.typography.labelMedium)
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        "$currentPercent% 压缩",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // 0% ~ 100% 自由连续调节滑块，支持任意自定义数值
                            Slider(
                                value = state.params.secondaryIndentRatio,
                                onValueChange = {
                                    viewModel.updateParams(state.params.copy(secondaryIndentRatio = it))
                                },
                                valueRange = 0f..1f,
                                steps = 19,
                                modifier = Modifier.fillMaxWidth().height(26.dp)
                            )

                            // 常用快捷胶囊：可快速一点即到 20%，也可以用滑块精准设置任意百分比
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    0.2f to "20% (极紧)",
                                    0.35f to "35%",
                                    0.5f to "50%",
                                    1.0f to "100%"
                                ).forEach { (ratio, label) ->
                                    val isMatch = kotlin.math.abs(state.params.secondaryIndentRatio - ratio) < 0.04f
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { viewModel.updateParams(state.params.copy(secondaryIndentRatio = ratio)) },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isMatch) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                        border = if (isMatch) null
                                                 else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isMatch) MaterialTheme.colorScheme.onPrimary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 高级规则收敛卡片 (点击展开/弹窗，低频选项不抢占主屏幕视野，彻底治愈眼花缭乱！)
            Surface(
                onClick = { showAdvancedDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("高级排版规则", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "${state.params.tableDisplayMode.displayName} · ${if (state.params.hideFrontToc) "跳过目录" else "保留目录"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 错误/状态通知 (若有)
            state.errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // --- 高级排版规则弹窗 (表格、分卷、页边距、页眉清理等次要设置集中于此) ---
    if (showAdvancedDialog) {
        AdvancedRulesDialog(
            params = state.params,
            onParamsChange = { viewModel.updateParams(it) },
            onDismiss = { showAdvancedDialog = false }
        )
    }

    // --- 字体管理弹窗 ---
    if (showFontDialog) {
        FontSelectionDialog(
            fonts = state.fonts,
            selectedFontId = state.selectedFontId,
            onSelectFont = { viewModel.selectFont(it) },
            onDeleteFont = { viewModel.deleteFont(it) },
            onImportFont = { fontPickerLauncher.launch(arrayOf("*/*")) },
            onDismiss = { showFontDialog = false }
        )
    }

    // --- 预设管理弹窗 ---
    if (showPresetDialog) {
        PresetManagementDialog(
            presets = state.presets,
            selectedPresetName = state.selectedPresetName,
            onLoadPreset = { viewModel.loadPreset(it) },
            onDeletePreset = { viewModel.deletePreset(it) },
            onSavePresetClick = {
                presetName = ""
                showPresetSaveDialog = true
            },
            onDismiss = { showPresetDialog = false }
        )
    }

    // --- 保存预设命名弹窗 ---
    if (showPresetSaveDialog) {
        AlertDialog(
            onDismissRequest = { showPresetSaveDialog = false },
            title = { Text("保存预设") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.savePreset(presetName)
                        showPresetSaveDialog = false
                    },
                    enabled = presetName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPresetSaveDialog = false }) { Text("取消") }
            }
        )
    }

    // --- 超大文件分卷询问弹窗 ---
    state.splitPrompt?.let { prompt ->
        var selectedVols by remember(prompt) { mutableStateOf(prompt.suggestedVolumes) }

        AlertDialog(
            onDismissRequest = { viewModel.dismissSplitPrompt() },
            icon = { Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("是否分卷？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "「${prompt.fileItem.name}」篇幅较长，建议分卷以提升翻页流畅度：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(2, 3, 4, 5).forEach { count ->
                            val label = if (count == 2) "2卷(上/下)" else if (count == 3) "3卷(上/中/下)" else "${count}卷"
                            FilterChip(
                                selected = selectedVols == count,
                                onClick = { selectedVols = count },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmSplitConversion(selectedVols) }) {
                    Text("分卷转换")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSplitPrompt() }) {
                    Text("取消")
                }
            }
        )
    }

    // --- 预览弹窗 ---
    if (state.showPreviewDialog) {
        PreviewDialog(
            pages = state.pages,
            currentPageIndex = state.previewPageIndex,
            isTypesetting = state.isTypesetting,
            typeface = state.fontTypeface,
            onPageChange = { viewModel.updatePreviewPage(it) },
            onClose = { viewModel.closePreviewDialog() }
        )
    }
}

/**
 * 高级排版规则弹窗：
 * 将跳过目录、清理页眉页脚、强制对齐、表格模式、分卷排版与页边距集中于此，
 * 彻底释放主屏幕视野，实现零视觉杂乱。
 */
@Composable
private fun AdvancedRulesDialog(
    params: TypesettingParams,
    onParamsChange: (TypesettingParams) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级排版规则") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 跳过正文前印刷目录
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("跳过正文前印刷目录", style = MaterialTheme.typography.bodyMedium)
                        Text("直达正文，保留电子大纲书签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = params.hideFrontToc,
                        onCheckedChange = { onParamsChange(params.copy(hideFrontToc = it)) }
                    )
                }

                HorizontalDivider()

                // 清理页眉页脚与页码
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("清理页眉页脚/页码", style = MaterialTheme.typography.bodyMedium)
                        Text("自动识别并剔除 PDF 原版页眉页码", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = params.stripHeadersFooters,
                        onCheckedChange = { onParamsChange(params.copy(stripHeadersFooters = it)) }
                    )
                }

                HorizontalDivider()

                // 强制对齐
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("强制右页边撑满对齐", style = MaterialTheme.typography.bodyMedium)
                        Text("微调字间距，消除行末锯齿", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = params.forceAlign,
                        onCheckedChange = { onParamsChange(params.copy(forceAlign = it)) }
                    )
                }

                HorizontalDivider()

                // 6 英寸表格呈现模式
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("表格呈现模式", style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TableDisplayMode.ALL.forEach { mode ->
                            FilterChip(
                                selected = params.tableDisplayMode == mode,
                                onClick = { onParamsChange(params.copy(tableDisplayMode = mode)) },
                                label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 分卷模式
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("长文档分卷排版", style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            VolumeSplitMode.NONE to "单本完整",
                            VolumeSplitMode.AUTO to "智能分卷",
                            VolumeSplitMode.TWO_VOLUMES to "分2卷"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = params.volumeSplitMode == mode,
                                onClick = { onParamsChange(params.copy(volumeSplitMode = mode)) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 页边距
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("上下页边距", style = MaterialTheme.typography.bodyMedium)
                        Text("${params.marginTop.toInt()} pt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = params.marginTop,
                        onValueChange = { onParamsChange(params.copy(marginTop = it, marginBottom = it)) },
                        valueRange = 4f..36f,
                        steps = 15
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("左右页边距", style = MaterialTheme.typography.bodyMedium)
                        Text("${params.marginLeft.toInt()} pt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = params.marginLeft,
                        onValueChange = { onParamsChange(params.copy(marginLeft = it, marginRight = it)) },
                        valueRange = 4f..36f,
                        steps = 15
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun FontSelectionDialog(
    fonts: List<FontManager.FontEntry>,
    selectedFontId: String?,
    onSelectFont: (String) -> Unit,
    onDeleteFont: (String) -> Unit,
    onImportFont: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSystemSelected = selectedFontId == FontManager.SYSTEM_FONT_ID ||
        (selectedFontId == null || fonts.none { it.id == selectedFontId })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字体管理") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFont(FontManager.SYSTEM_FONT_ID) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSystemSelected,
                        onClick = { onSelectFont(FontManager.SYSTEM_FONT_ID) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("系统默认字体", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()

                fonts.forEach { entry ->
                    val selected = entry.id == selectedFontId && !isSystemSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFont(entry.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectFont(entry.id) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { onDeleteFont(entry.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = onImportFont,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导入字体 (TTF/OTF)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun PresetManagementDialog(
    presets: List<PresetRepository.ParamPreset>,
    selectedPresetName: String?,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePresetClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排版预设") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (presets.isEmpty()) {
                    Text("暂无保存的预设", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    presets.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLoadPreset(preset.name)
                                    onDismiss()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (preset.name == selectedPresetName) FontWeight.Bold else FontWeight.Normal,
                                color = if (preset.name == selectedPresetName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(onClick = { onDeletePreset(preset.name) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        onDismiss()
                        onSavePresetClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存当前参数为预设")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PreviewDialog(
    pages: List<PageLayout>,
    currentPageIndex: Int,
    isTypesetting: Boolean,
    typeface: android.graphics.Typeface?,
    onPageChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    var multiPage by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.95f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("排版预览 (${pages.size} 页)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = multiPage,
                            onClick = { multiPage = !multiPage },
                            label = { Text("5页连排", style = MaterialTheme.typography.labelSmall) }
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }

                if (isTypesetting) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (pages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无内容可预览")
                    }
                } else if (multiPage) {
                    val pageGroupStart = (currentPageIndex / 5) * 5
                    val groupEnd = minOf(pageGroupStart + 5, pages.size)
                    val rowScroll = remember { androidx.compose.foundation.ScrollState(0) }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .horizontalScroll(rowScroll)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in pageGroupStart until groupEnd) {
                            val page = pages.getOrNull(i) ?: continue
                            val isCurrent = i == currentPageIndex
                            Column(
                                modifier = Modifier
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .padding(6.dp)
                                    .clickable { onPageChange(i) }
                            ) {
                                Text("第 ${i + 1} 页", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                                Box(modifier = Modifier.width(180.dp).height(240.dp)) {
                                    PreviewCanvas(page = page, typeface = typeface, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                } else {
                    val currentPage = pages[currentPageIndex]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PreviewCanvas(
                            page = currentPage,
                            typeface = typeface,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(4.dp))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onPageChange((currentPageIndex - 1).coerceAtLeast(0)) },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null)
                            Text("上一页")
                        }
                        Text(
                            "第 ${currentPageIndex + 1} / ${pages.size} 页",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = { onPageChange((currentPageIndex + 1).coerceAtMost(pages.size - 1)) },
                            enabled = currentPageIndex < pages.size - 1
                        ) {
                            Text("下一页")
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
