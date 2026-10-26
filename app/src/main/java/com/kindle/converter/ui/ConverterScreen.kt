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
import androidx.compose.material.icons.automirrored.filled.CallSplit
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
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (state.files.isNotEmpty()) viewModel.openPreviewDialog()
                                    else viewModel.previewSample()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                enabled = !state.isTypesetting && state.fontName != null,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
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
                                    .weight(1.3f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !state.isConverting,
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
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
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 顶部文件与设置快捷栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件选择/展示
                if (state.files.isEmpty()) {
                    OutlinedButton(
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
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加文件", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            label = { Text("已选 ${state.files.size} 个文件", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                        IconButton(
                            onClick = { viewModel.clearFiles() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "清空", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // 右侧功能图标：字体与预设弹窗入口
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showFontDialog = true },
                        label = {
                            Text(
                                state.fontName ?: "默认字体",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.FontDownload, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )

                    AssistChip(
                        onClick = { showPresetDialog = true },
                        label = { Text("预设", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }

            // 文件列表预览（若有选定文件，以极窄横向滚动流呈现）
            if (state.files.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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

            // 排版参数控制核心面板（紧凑双列与药丸矩阵）
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 水平对齐方式
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextAlignment.ALL.forEach { ta ->
                            val selected = state.params.textAlignment == ta
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.updateParams(state.params.copy(textAlignment = ta)) },
                                label = { Text(ta.displayName, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }

                    // 开关药丸矩阵
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 取消段首缩进
                        FilterChip(
                            selected = state.params.disableFirstLineIndent,
                            onClick = {
                                viewModel.updateParams(state.params.copy(disableFirstLineIndent = !state.params.disableFirstLineIndent))
                            },
                            label = { Text("取消段首缩进", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp)
                        )

                        // 强制两端对齐
                        FilterChip(
                            selected = state.params.forceAlign,
                            onClick = {
                                viewModel.updateParams(state.params.copy(forceAlign = !state.params.forceAlign))
                            },
                            label = { Text("强制对齐", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp)
                        )

                        // 清理页眉页脚
                        FilterChip(
                            selected = state.params.stripHeadersFooters,
                            onClick = {
                                viewModel.updateParams(state.params.copy(stripHeadersFooters = !state.params.stripHeadersFooters))
                            },
                            label = { Text("清理页眉页脚", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp)
                        )

                        // 跳过印刷目录
                        FilterChip(
                            selected = state.params.hideFrontToc,
                            onClick = {
                                viewModel.updateParams(state.params.copy(hideFrontToc = !state.params.hideFrontToc))
                            },
                            label = { Text("跳过目录", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }

                    // 辅助缩进幅度（引用、多级列表、代码块压缩）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("辅助缩进", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                        listOf(0.2f to "20%", 0.5f to "50%", 0.8f to "80%", 1.0f to "100%").forEach { (ratio, label) ->
                            val isSelected = kotlin.math.abs(state.params.secondaryIndentRatio - ratio) < 0.05f
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.updateParams(state.params.copy(secondaryIndentRatio = ratio)) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // 双列紧凑滑块：字号与行距
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactSlider(
                            label = "字号",
                            value = state.params.fontSize,
                            range = 6f..24f,
                            steps = 17,
                            suffix = "pt",
                            modifier = Modifier.weight(1f),
                            onChange = { viewModel.updateParams(state.params.copy(fontSize = it)) }
                        )
                        CompactSlider(
                            label = "行距",
                            value = state.params.lineHeight,
                            range = 1.0f..3.0f,
                            steps = 19,
                            suffix = "x",
                            modifier = Modifier.weight(1f),
                            onChange = { viewModel.updateParams(state.params.copy(lineHeight = it)) }
                        )
                    }

                    // 双列紧凑滑块：段间距与首行缩进
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactSlider(
                            label = "段距",
                            value = state.params.paragraphSpacing,
                            range = 0f..24f,
                            steps = 23,
                            suffix = "pt",
                            modifier = Modifier.weight(1f),
                            onChange = { viewModel.updateParams(state.params.copy(paragraphSpacing = it)) }
                        )
                        CompactSlider(
                            label = if (state.params.disableFirstLineIndent) "首行缩进(已取消)" else "首行缩进",
                            value = if (state.params.disableFirstLineIndent) 0f else state.params.firstLineIndent,
                            range = 0f..48f,
                            steps = 23,
                            suffix = "pt",
                            enabled = !state.params.disableFirstLineIndent,
                            modifier = Modifier.weight(1f),
                            onChange = { viewModel.updateParams(state.params.copy(firstLineIndent = it)) }
                        )
                    }

                    // 双列紧凑滑块：页边距 (上下 / 左右)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactSlider(
                            label = "上下边距",
                            value = state.params.marginTop,
                            range = 0f..40f,
                            steps = 39,
                            suffix = "pt",
                            modifier = Modifier.weight(1f),
                            onChange = {
                                viewModel.updateParams(state.params.copy(marginTop = it, marginBottom = it))
                            }
                        )
                        CompactSlider(
                            label = "左右边距",
                            value = state.params.marginLeft,
                            range = 0f..40f,
                            steps = 39,
                            suffix = "pt",
                            modifier = Modifier.weight(1f),
                            onChange = {
                                viewModel.updateParams(state.params.copy(marginLeft = it, marginRight = it))
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // 页面尺寸、表格模式、分卷模式（单行紧凑下拉流）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 尺寸
                        var presetExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1.2f)) {
                            OutlinedButton(
                                onClick = { presetExpanded = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                val name = TypesettingParams.KINDLE_PRESETS.getOrNull(state.selectedPresetIndex)?.name
                                    ?.replace("Kindle ", "")?.replace("（基础版/Paperwhite）", "") ?: "尺寸"
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                            DropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
                                TypesettingParams.KINDLE_PRESETS.forEachIndexed { index, preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                                        onClick = {
                                            viewModel.selectPreset(index)
                                            presetExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // 表格模式
                        var tableExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { tableExpanded = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(state.params.tableDisplayMode.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                            DropdownMenu(expanded = tableExpanded, onDismissRequest = { tableExpanded = false }) {
                                TableDisplayMode.ALL.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.displayName, style = MaterialTheme.typography.bodySmall) },
                                        onClick = {
                                            viewModel.updateParams(state.params.copy(tableDisplayMode = mode))
                                            tableExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // 分卷模式
                        var splitExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { splitExpanded = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(state.params.volumeSplitMode.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                            DropdownMenu(expanded = splitExpanded, onDismissRequest = { splitExpanded = false }) {
                                VolumeSplitMode.ALL.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.displayName, style = MaterialTheme.typography.bodySmall) },
                                        onClick = {
                                            viewModel.updateParams(state.params.copy(volumeSplitMode = mode))
                                            splitExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 转换结果（若有）
            if (state.conversionResults.isNotEmpty()) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("转换结果", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        ResultsSection(results = state.conversionResults)
                    }
                }
            }

            // 状态/错误提示
            state.errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    // 字体管理弹窗
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

    // 预设管理弹窗
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

    // 保存预设命名弹窗
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

    // 超大文件分卷询问弹窗
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

    // 预览弹窗
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

@Composable
private fun CompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String = "",
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${if (suffix == "x") String.format("%.1f", value) else value.toInt()}$suffix",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.height(28.dp)
        )
    }
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
                // 系统字体
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
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.95f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

@Composable
private fun ResultsSection(results: List<ConverterViewModel.ConversionResult>) {
    results.forEach { result ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (result.success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (result.success) "${result.pageCount} 页 → ${result.outputPath ?: ""}" else result.error ?: "失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
