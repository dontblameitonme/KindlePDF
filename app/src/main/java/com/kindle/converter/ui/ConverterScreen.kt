package com.kindle.converter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kindle.converter.data.ImageWrapMode
import com.kindle.converter.data.TextAlignment
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.data.VerticalAlignment
import com.kindle.converter.viewmodel.ConverterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel = viewModel()
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

    // 同目录写入失败时的回退：让用户选择保存位置
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

    var showPresetSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 16.dp)
                        .offset { IntOffset(10, 0) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        "Kindle PDF Converter",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        bottomBar = {
            if (state.files.isNotEmpty()) {
                BottomBar(
                    isConverting = state.isConverting,
                    progress = state.conversionProgress,
                    onConvert = { viewModel.convertAll() }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // 内容区与 TopAppBar 之间的明显间距，避免视觉重叠
            Spacer(modifier = Modifier.height(8.dp))

            // --- 文件 ---
            SectionCard(
                title = "文件",
                // 文件区始终展开（功能入口，不折叠）
                expanded = true,
                onToggle = { /* 文件区不参与折叠 */ }
            ) {
                FileSelectionSection(
                    files = state.files,
                    onAddFiles = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "text/markdown",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/epub+zip",
                                "application/pdf",
                                "text/html",
                                "*/*"
                            )
                        )
                    },
                    onRemoveFile = { viewModel.removeFile(it) },
                    onClearAll = { viewModel.clearFiles() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 字体管理（可折叠） ---
            SectionCard(
                title = "字体",
                expanded = state.expandedSections["fonts"] ?: true,
                onToggle = { viewModel.toggleSectionExpanded("fonts") }
            ) {
                FontSection(
                    fonts = state.fonts,
                    selectedFontId = state.selectedFontId,
                    onSelectFont = { viewModel.selectFont(it) },
                    onDeleteFont = { viewModel.deleteFont(it) },
                    onImportFont = { fontPickerLauncher.launch(arrayOf("*/*")) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 排版设置（可折叠） ---
            SectionCard(
                title = "排版设置",
                expanded = state.expandedSections["params"] ?: true,
                onToggle = { viewModel.toggleSectionExpanded("params") }
            ) {
                ParameterPanel(
                    params = state.params,
                    selectedPresetIndex = state.selectedPresetIndex,
                    onParamsChange = { viewModel.updateParams(it) },
                    onPresetChange = { viewModel.selectPreset(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 参数预设（可折叠） ---
            SectionCard(
                title = "参数预设",
                expanded = state.expandedSections["presets"] ?: true,
                onToggle = { viewModel.toggleSectionExpanded("presets") }
            ) {
                PresetSection(
                    presets = state.presets,
                    selectedPresetName = state.selectedPresetName,
                    onLoadPreset = { viewModel.loadPreset(it) },
                    onDeletePreset = { viewModel.deletePreset(it) },
                    onSavePreset = {
                        presetName = ""
                        showPresetSaveDialog = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 预览按钮 ---
            if (state.files.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.openPreviewDialog() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = !state.isTypesetting && state.fontName != null
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.isTypesetting) "正在生成预览…" else "预览")
                }
            }

            // --- 预览示例排版：内置示例文字铺满多页，无需文件也能实时看排版 ---
            OutlinedButton(
                onClick = { viewModel.previewSample() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = !state.isTypesetting && state.fontName != null
            ) {
                Icon(Icons.Default.MenuBook, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.isTypesetting) "正在生成预览…" else "预览示例排版")
            }

            // --- 转换结果 ---
            if (state.conversionResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard(title = "转换结果（${state.conversionResults.size}）") {
                    ResultsSection(results = state.conversionResults)
                }
            }

            state.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
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

    // --- 保存预设命名弹窗 ---
    if (showPresetSaveDialog) {
        AlertDialog(
            onDismissRequest = { showPresetSaveDialog = false },
            title = { Text("保存为参数预设") },
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
}

@Composable
private fun SectionCard(
    title: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题栏：可点击折叠
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
                    .padding(bottom = if (expanded) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (onToggle != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                      else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun FileSelectionSection(
    files: List<ConverterViewModel.FileItem>,
    onAddFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    if (files.isNotEmpty()) {
        files.forEachIndexed { index, file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (file.size > 0) {
                        Text(
                            text = formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onRemoveFile(index) }) {
                    Icon(Icons.Default.Close, contentDescription = "移除")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClearAll) { Text("清空列表") }
    }

    Button(
        onClick = onAddFiles,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("选择文件")
    }
}

@Composable
private fun FontSection(
    fonts: List<com.kindle.converter.pdf.FontManager.FontEntry>,
    selectedFontId: String?,
    onSelectFont: (String) -> Unit,
    onDeleteFont: (String) -> Unit,
    onImportFont: () -> Unit
) {
    if (fonts.isEmpty()) {
        Text(
            text = "尚未导入字体，请点击下方按钮导入 TTF/OTF 字体。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        fonts.forEach { entry ->
            val selected = entry.id == selectedFontId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSelectFont(entry.id) }) {
                    Icon(
                        imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.Circle,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = entry.name,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectFont(entry.id) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { onDeleteFont(entry.id) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除字体",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    OutlinedButton(onClick = onImportFont, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("导入字体")
    }
}

@Composable
private fun ParameterPanel(
    params: TypesettingParams,
    selectedPresetIndex: Int,
    onParamsChange: (TypesettingParams) -> Unit,
    onPresetChange: (Int) -> Unit
) {
    var presetExpanded by remember { mutableStateOf(false) }

    Text("页面尺寸", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    Box {
        OutlinedButton(onClick = { presetExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            val presetName = TypesettingParams.KINDLE_PRESETS.getOrNull(selectedPresetIndex)?.name ?: "自定义"
            Text(presetName)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
            TypesettingParams.KINDLE_PRESETS.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        onPresetChange(index)
                        presetExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("自定义") },
                onClick = { presetExpanded = false }
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("对齐方式（水平）", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextAlignment.ALL.forEach { ta ->
            val selected = params.textAlignment == ta
            FilterChip(
                selected = selected,
                onClick = { onParamsChange(params.copy(textAlignment = ta)) },
                label = { Text(ta.displayName, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 强制对齐（默认开）：把每段除末行以外的所有行撑满到右页边，消除参差
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = params.forceAlign,
            onCheckedChange = { onParamsChange(params.copy(forceAlign = it)) },
            enabled = params.textAlignment == TextAlignment.LEFT ||
                params.textAlignment == TextAlignment.JUSTIFY
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("强制对齐（推荐开启）", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (params.textAlignment) {
                    TextAlignment.CENTER, TextAlignment.RIGHT ->
                        "当前为${params.textAlignment.displayName}，强制对齐不生效"
                    else ->
                        "每段除末行外一律撑满右页边，消除忽长忽短的参差边界"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text("垂直对齐（页面内）", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VerticalAlignment.ALL.forEach { va ->
            val selected = params.verticalAlignment == va
            FilterChip(
                selected = selected,
                onClick = { onParamsChange(params.copy(verticalAlignment = va)) },
                label = { Text(va.displayName, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("图片环绕方式", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ImageWrapMode.ALL.forEach { iw ->
            val selected = params.imageWrapMode == iw
            FilterChip(
                selected = selected,
                onClick = { onParamsChange(params.copy(imageWrapMode = iw)) },
                label = { Text(iw.displayName, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 智能清理 PDF 页眉页脚/页码（开关）
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = params.stripHeadersFooters,
            onCheckedChange = { onParamsChange(params.copy(stripHeadersFooters = it)) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("智能清理页眉页脚/页码", style = MaterialTheme.typography.bodyMedium)
            Text(
                "适用于 PDF 输入；自动丢弃页码、章节标题等",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SliderParam("字号", params.fontSize, 6f..24f, 17, "pt") { onParamsChange(params.copy(fontSize = it)) }
    SliderParam("行距", params.lineHeight, 1.0f..3.0f, 19, "x") { onParamsChange(params.copy(lineHeight = it)) }
    SliderParam("段间距", params.paragraphSpacing, 0f..24f, 23, "pt") { onParamsChange(params.copy(paragraphSpacing = it)) }
    SliderParam("首行缩进", params.firstLineIndent, 0f..48f, 23, "pt") { onParamsChange(params.copy(firstLineIndent = it)) }

    Text("页边距", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    SliderParam("上", params.marginTop, 0f..40f, 39, "pt") { onParamsChange(params.copy(marginTop = it)) }
    SliderParam("下", params.marginBottom, 0f..40f, 39, "pt") { onParamsChange(params.copy(marginBottom = it)) }
    SliderParam("左", params.marginLeft, 0f..40f, 39, "pt") { onParamsChange(params.copy(marginLeft = it)) }
    SliderParam("右", params.marginRight, 0f..40f, 39, "pt") { onParamsChange(params.copy(marginRight = it)) }
}

@Composable
private fun PresetSection(
    presets: List<com.kindle.converter.pdf.PresetRepository.ParamPreset>,
    selectedPresetName: String?,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePreset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    if (presets.isEmpty()) {
        Text(
            text = "暂无已保存预设。调整好参数后点击“保存当前为预设”。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedPresetName ?: "选择预设")
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            onLoadPreset(preset.name)
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedPresetName != null) {
            OutlinedButton(
                onClick = { onDeletePreset(selectedPresetName) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("删除当前预设")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    Button(onClick = onSavePreset, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Save, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("保存当前为预设")
    }
}

@Composable
private fun SliderParam(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String = "",
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${if (suffix == "x") String.format("%.1f", value) else value.toInt()}$suffix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun PreviewDialog(
    pages: List<com.kindle.converter.data.PageLayout>,
    currentPageIndex: Int,
    isTypesetting: Boolean,
    typeface: android.graphics.Typeface?,
    onPageChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    // 单页大图 vs 5页横排 切换（默认单页大图，彻底解决预览看不到的痛点）
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
                // --- 顶部工具栏：诊断信息 + 模式切换 + 关闭 ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "预览 · 共 ${pages.size} 页" +
                            (if (pages.isNotEmpty()) " · 当前第 ${currentPageIndex + 1} 页 · ${pages.getOrNull(currentPageIndex)?.elements?.size ?: 0} 元素" else ""),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { multiPage = !multiPage }) {
                        Text(
                            if (multiPage) "切换单页" else "切换5页",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                if (isTypesetting && pages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("正在生成预览…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    return@Column
                }

                if (pages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无可预览内容", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("请导入文件后点击预览，或点击首页「预览示例排版」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    return@Column
                }

                if (multiPage) {
                    // ===== 五页横排模式（旧实现）=====
                    val pageGroupStart = (currentPageIndex / 5) * 5
                    val groupEnd = minOf(pageGroupStart + 5, pages.size)
                    val canPrevGroup = pageGroupStart > 0
                    val canNextGroup = groupEnd < pages.size

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onPageChange((pageGroupStart - 5).coerceAtLeast(0)) },
                            enabled = canPrevGroup
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = null)
                            Text("前 5 页")
                        }
                        TextButton(
                            onClick = { onPageChange((pageGroupStart - 1).coerceAtLeast(0)) },
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
                        TextButton(
                            onClick = { onPageChange((groupEnd).coerceAtMost(pages.size - 1)) },
                            enabled = canNextGroup
                        ) {
                            Text("后 5 页")
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
                                Text(
                                    "第 ${i + 1} 页",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(180.dp)
                                        .height(240.dp)
                                ) {
                                    PreviewCanvas(
                                        page = page,
                                        typeface = typeface,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ===== 单页大图模式（默认）：一页占满弹窗宽度 + 翻页控件 =====
                    val currentPage = pages[currentPageIndex]

                    // 大图区域
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
                                .background(
                                    color = androidx.compose.ui.graphics.Color.White,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                    }

                    // 底部翻页控件
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
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (result.success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (result.success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (result.success)
                        "${result.pageCount} 页 → ${result.outputPath ?: ""}"
                    else
                        result.error ?: "未知错误",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    isConverting: Boolean,
    progress: Float,
    onConvert: () -> Unit
) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        if (isConverting) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        } else {
            Button(
                onClick = onConvert,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("转换为 PDF")
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
