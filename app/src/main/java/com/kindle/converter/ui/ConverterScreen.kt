package com.kindle.converter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kindle.converter.data.*
import com.kindle.converter.pdf.FontManager
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.viewmodel.ConverterViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private val SUPPORTED_EBOOK_MIMES = arrayOf(
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

/**
 * 电子书排版主页面：
 * 遵循 Material Design 3 (M3 Expressive) 无边框色阶分层与渐进式折叠设计：
 * - 多选文件队列支持随时追加/单项移除/批量清空；
 * - 中间 4 张核心卡片（文字排版 / 缩进设置 / 版面与分页 / 净化与对齐）支持独立展开/折叠，
 *   并通过 ConverterViewModel 持久化记忆用户上次状态；
 * - 字体与预设管理收敛至顶栏，通过 ModalBottomSheet 轻量呼出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    showFontSheet: Boolean = false,
    onShowFontSheetChange: (Boolean) -> Unit = {},
    showPresetSheet: Boolean = false,
    onShowPresetSheetChange: (Boolean) -> Unit = {}
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

    var internalFontSheet by remember { mutableStateOf(false) }
    var internalPresetSheet by remember { mutableStateOf(false) }
    val isFontSheetOpen = if (showTopBar) internalFontSheet else showFontSheet
    val setFontSheetOpen: (Boolean) -> Unit = if (showTopBar) {
        { internalFontSheet = it }
    } else {
        onShowFontSheetChange
    }
    val isPresetSheetOpen = if (showTopBar) internalPresetSheet else showPresetSheet
    val setPresetSheetOpen: (Boolean) -> Unit = if (showTopBar) {
        { internalPresetSheet = it }
    } else {
        onShowPresetSheetChange
    }

    var showPresetSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        Text(
                            text = "排版",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    actions = {
                        FontSelectorPillAction(
                            fontName = state.fontName ?: "系统默认",
                            onClick = { setFontSheetOpen(true) }
                        )
                        IconButton(onClick = { setPresetSheetOpen(true) }) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmarks,
                                contentDescription = "预设",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            ConverterBottomActionBar(
                isConverting = state.isConverting,
                progress = state.conversionProgress,
                isTypesetting = state.isTypesetting,
                hasFont = state.fontName != null,
                fileCount = state.files.size,
                onPreviewClick = {
                    if (state.files.isNotEmpty()) viewModel.openPreviewDialog()
                    else viewModel.previewSample()
                },
                onPrimaryClick = {
                    if (state.files.isNotEmpty()) viewModel.convertAll()
                    else filePickerLauncher.launch(SUPPORTED_EBOOK_MIMES)
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MultiFileSelectorCard(
                files = state.files,
                onAddFiles = { filePickerLauncher.launch(SUPPORTED_EBOOK_MIMES) },
                onRemoveFile = { viewModel.removeFile(it) },
                onClearFiles = { viewModel.clearFiles() }
            )

            // 1. 文字排版（对齐 / 字号 / 行距）
            TypographySectionCard(
                params = state.params,
                expanded = ConverterViewModel.isExpanded(state.expandedSections, ConverterViewModel.SECTION_TYPOGRAPHY),
                onToggle = { viewModel.toggleSectionExpanded(ConverterViewModel.SECTION_TYPOGRAPHY) },
                onParamsChange = { viewModel.updateParams(it) }
            )

            // 2. 缩进设置（取消段首缩进 / 段首缩进 / 0%~100% 自由缩进比例）
            IndentSectionCard(
                params = state.params,
                expanded = ConverterViewModel.isExpanded(state.expandedSections, ConverterViewModel.SECTION_INDENT),
                onToggle = { viewModel.toggleSectionExpanded(ConverterViewModel.SECTION_INDENT) },
                onParamsChange = { viewModel.updateParams(it) }
            )

            // 3. 版面与分页（纸张尺寸 / 表格模式 / 分卷 / 段距与边距）
            LayoutSectionCard(
                params = state.params,
                expanded = ConverterViewModel.isExpanded(state.expandedSections, ConverterViewModel.SECTION_LAYOUT),
                onToggle = { viewModel.toggleSectionExpanded(ConverterViewModel.SECTION_LAYOUT) },
                onSelectPaperPreset = { viewModel.selectPreset(it) },
                onParamsChange = { viewModel.updateParams(it) }
            )

            // 4. 净化与对齐（强制对齐 / 清理页眉页脚 / 跳过目录 / 标题加粗 / 图片环绕）
            RulesSectionCard(
                params = state.params,
                expanded = ConverterViewModel.isExpanded(state.expandedSections, ConverterViewModel.SECTION_RULES),
                onToggle = { viewModel.toggleSectionExpanded(ConverterViewModel.SECTION_RULES) },
                onParamsChange = { viewModel.updateParams(it) }
            )

            ConversionFeedbackBanner(
                errorMessage = state.errorMessage,
                results = state.conversionResults,
                onDismiss = { viewModel.clearFeedback() }
            )

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (isFontSheetOpen) {
        FontSelectionBottomSheet(
            fonts = state.fonts,
            selectedFontId = state.selectedFontId,
            onSelectFont = {
                viewModel.selectFont(it)
                setFontSheetOpen(false)
            },
            onDeleteFont = { viewModel.deleteFont(it) },
            onImportFont = { fontPickerLauncher.launch(arrayOf("*/*")) },
            onDismiss = { setFontSheetOpen(false) }
        )
    }

    if (isPresetSheetOpen) {
        PresetSelectionBottomSheet(
            presets = state.presets,
            selectedPresetName = state.selectedPresetName,
            onLoadPreset = {
                viewModel.loadPreset(it)
                setPresetSheetOpen(false)
            },
            onDeletePreset = { viewModel.deletePreset(it) },
            onSavePresetClick = {
                presetName = ""
                setPresetSheetOpen(false)
                showPresetSaveDialog = true
            },
            onDismiss = { setPresetSheetOpen(false) }
        )
    }

    if (showPresetSaveDialog) {
        AlertDialog(
            onDismissRequest = { showPresetSaveDialog = false },
            title = { Text("保存预设") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    placeholder = { Text("预设名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
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

    state.splitPrompt?.let { prompt ->
        SplitVolumeDialog(
            fileName = prompt.fileItem.name,
            suggestedVolumes = prompt.suggestedVolumes,
            onConfirm = { viewModel.confirmSplitConversion(it) },
            onDismiss = { viewModel.dismissSplitPrompt() }
        )
    }

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
 * 顶部多选文件队列卡片：
 * - 空态引导一键调用系统多选器；
 * - 单文件显示文件名与大小，支持右侧追加更多文件或清空；
 * - 多文件自动展示总数/总大小，并以横向胶囊队列呈现每本书，可独立移除。
 */
@Composable
private fun MultiFileSelectorCard(
    files: List<ConverterViewModel.FileItem>,
    onAddFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearFiles: () -> Unit
) {
    Surface(
        onClick = { if (files.isEmpty()) onAddFiles() },
        enabled = files.isEmpty(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            files.isEmpty() -> Icons.Default.Add
                            files.size == 1 -> Icons.AutoMirrored.Filled.MenuBook
                            else -> Icons.Default.FolderCopy
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            files.isEmpty() -> "导入书籍（支持多选）"
                            files.size == 1 -> files.first().name
                            else -> "已选 ${files.size} 个文件"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (files.isNotEmpty()) {
                        val totalSize = files.sumOf { it.size }
                        Text(
                            text = formatFileSize(totalSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (files.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = onAddFiles,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "追加文件", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onClearFiles,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (files.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(files) { index, file ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 128.dp)
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clip(CircleShape)
                                        .clickable { onRemoveFile(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypographySectionCard(
    params: TypesettingParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onParamsChange: (TypesettingParams) -> Unit
) {
    val lineText = "${String.format("%.1f", params.lineHeight)}×"
    val summary = "${params.textAlignment.displayName} · ${params.fontSize.toInt()}pt · $lineText"

    CollapsibleTonalCard(
        title = "文字排版",
        summary = summary,
        icon = Icons.Default.FormatSize,
        expanded = expanded,
        onToggle = onToggle
    ) {
        M3ConnectedIconSegments(
            options = listOf(
                Triple(TextAlignment.LEFT, Icons.AutoMirrored.Filled.FormatAlignLeft, "左对齐"),
                Triple(TextAlignment.CENTER, Icons.Default.FormatAlignCenter, "居中"),
                Triple(TextAlignment.RIGHT, Icons.AutoMirrored.Filled.FormatAlignRight, "右对齐"),
                Triple(TextAlignment.JUSTIFY, Icons.Default.FormatAlignJustify, "两端对齐")
            ),
            selected = params.textAlignment,
            onSelect = { onParamsChange(params.copy(textAlignment = it)) }
        )

        M3ExpressiveSlider(
            label = "字号",
            valueText = "${params.fontSize.toInt()} pt",
            value = params.fontSize,
            onValueChange = { onParamsChange(params.copy(fontSize = it)) },
            valueRange = 8f..24f,
            stepSize = 1f
        )

        M3ExpressiveSlider(
            label = "行距",
            valueText = lineText,
            value = params.lineHeight,
            onValueChange = { onParamsChange(params.copy(lineHeight = it)) },
            valueRange = 1.0f..2.4f,
            stepSize = 0.1f
        )
    }
}

@Composable
private fun IndentSectionCard(
    params: TypesettingParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onParamsChange: (TypesettingParams) -> Unit
) {
    val indentPercent = (params.secondaryIndentRatio * 100).roundToInt()
    val firstPart = if (params.disableFirstLineIndent) "顶格" else "${params.firstLineIndent.toInt()}pt"
    val summary = "$firstPart · $indentPercent%"

    CollapsibleTonalCard(
        title = "缩进设置",
        summary = summary,
        icon = Icons.AutoMirrored.Filled.FormatIndentIncrease,
        expanded = expanded,
        onToggle = onToggle
    ) {
        M3SwitchRow(
            label = "取消段首缩进",
            checked = params.disableFirstLineIndent,
            onCheckedChange = { onParamsChange(params.copy(disableFirstLineIndent = it)) }
        )

        // 开启「取消段首缩进」时自动收起段首缩进滑块，避免相互冲突的冗余控件
        AnimatedVisibility(visible = !params.disableFirstLineIndent) {
            M3ExpressiveSlider(
                label = "段首缩进",
                valueText = "${params.firstLineIndent.toInt()} pt",
                value = params.firstLineIndent,
                onValueChange = { onParamsChange(params.copy(firstLineIndent = it)) },
                valueRange = 0f..36f,
                stepSize = 2f
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            M3ExpressiveSlider(
                label = "引用与列表缩进",
                valueText = "$indentPercent%",
                value = params.secondaryIndentRatio,
                onValueChange = { onParamsChange(params.copy(secondaryIndentRatio = it)) },
                valueRange = 0f..1f,
                stepSize = 0.01f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.20f, 0.35f, 0.50f, 1.0f).forEach { ratio ->
                    val pct = (ratio * 100).roundToInt()
                    val isSelected = abs(params.secondaryIndentRatio - ratio) < 0.015f
                    Surface(
                        onClick = { onParamsChange(params.copy(secondaryIndentRatio = ratio)) },
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutSectionCard(
    params: TypesettingParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectPaperPreset: (Int) -> Unit,
    onParamsChange: (TypesettingParams) -> Unit
) {
    val activePaperIndex = resolvePaperPresetIndex(params)
    val paperLabel = when (activePaperIndex) {
        1 -> "7英寸"
        3 -> "A4"
        else -> "6英寸"
    }
    val tableLabel = tableModeShortName(params.tableDisplayMode)
    val summary = "$paperLabel · $tableLabel · 边距${params.marginTop.toInt()}"

    CollapsibleTonalCard(
        title = "版面与分页",
        summary = summary,
        icon = Icons.Default.AutoAwesomeMosaic,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("纸张尺寸", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            M3ConnectedTextSegments(
                options = listOf(0 to "6英寸", 1 to "7英寸", 3 to "A4"),
                selected = activePaperIndex,
                onSelect = onSelectPaperPreset
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("表格模式", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            M3ConnectedTextSegments(
                options = listOf(
                    TableDisplayMode.SMART_CARD to "卡片表格",
                    TableDisplayMode.COMPACT_GRID to "紧凑网格"
                ),
                selected = params.tableDisplayMode,
                onSelect = { onParamsChange(params.copy(tableDisplayMode = it)) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("分卷策略", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            M3ConnectedTextSegments(
                options = listOf(
                    VolumeSplitMode.NONE to "不分卷",
                    VolumeSplitMode.AUTO to "自动",
                    VolumeSplitMode.TWO_VOLUMES to "2卷",
                    VolumeSplitMode.THREE_VOLUMES to "3卷"
                ),
                selected = params.volumeSplitMode,
                onSelect = { onParamsChange(params.copy(volumeSplitMode = it)) }
            )
        }

        M3ExpressiveSlider(
            label = "段落间距",
            valueText = "${params.paragraphSpacing.toInt()} pt",
            value = params.paragraphSpacing,
            onValueChange = { onParamsChange(params.copy(paragraphSpacing = it)) },
            valueRange = 0f..24f,
            stepSize = 1f
        )

        M3ExpressiveSlider(
            label = "上下边距",
            valueText = "${params.marginTop.toInt()} pt",
            value = params.marginTop,
            onValueChange = { onParamsChange(params.copy(marginTop = it, marginBottom = it)) },
            valueRange = 4f..36f,
            stepSize = 2f
        )

        M3ExpressiveSlider(
            label = "左右边距",
            valueText = "${params.marginLeft.toInt()} pt",
            value = params.marginLeft,
            onValueChange = { onParamsChange(params.copy(marginLeft = it, marginRight = it)) },
            valueRange = 4f..36f,
            stepSize = 2f
        )
    }
}

@Composable
private fun RulesSectionCard(
    params: TypesettingParams,
    expanded: Boolean,
    onToggle: () -> Unit,
    onParamsChange: (TypesettingParams) -> Unit
) {
    val activeTags = buildList {
        if (params.forceAlign) add("强对齐")
        if (params.stripHeadersFooters) add("清理页眉")
        if (params.hideFrontToc) add("跳过目录")
    }
    val summary = activeTags.ifEmpty { listOf("标准") }.take(2).joinToString(" · ")

    CollapsibleTonalCard(
        title = "净化与对齐",
        summary = summary,
        icon = Icons.Default.Tune,
        expanded = expanded,
        onToggle = onToggle
    ) {
        M3SwitchRow(
            label = "强制右对齐",
            checked = params.forceAlign,
            onCheckedChange = { onParamsChange(params.copy(forceAlign = it)) }
        )
        M3SwitchRow(
            label = "清理页眉页脚",
            checked = params.stripHeadersFooters,
            onCheckedChange = { onParamsChange(params.copy(stripHeadersFooters = it)) }
        )
        M3SwitchRow(
            label = "跳过前置目录",
            checked = params.hideFrontToc,
            onCheckedChange = { onParamsChange(params.copy(hideFrontToc = it)) }
        )
        M3SwitchRow(
            label = "标题加粗",
            checked = params.boldHeadings,
            onCheckedChange = { onParamsChange(params.copy(boldHeadings = it)) }
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("图片环绕", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            M3ConnectedTextSegments(
                options = listOf(
                    ImageWrapMode.TOP_AND_BOTTOM to "嵌入",
                    ImageWrapMode.BEHIND_TEXT to "水印",
                    ImageWrapMode.HIDE_IMAGES to "隐藏"
                ),
                selected = params.imageWrapMode,
                onSelect = { onParamsChange(params.copy(imageWrapMode = it)) }
            )
        }
    }
}

@Composable
private fun ConverterBottomActionBar(
    isConverting: Boolean,
    progress: Float,
    isTypesetting: Boolean,
    hasFont: Boolean,
    fileCount: Int,
    onPreviewClick: () -> Unit,
    onPrimaryClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (isConverting) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                        )
                        Text(
                            text = "${(progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPreviewClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        enabled = !isTypesetting && hasFont,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isTypesetting) "生成中" else "预览",
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onPrimaryClick,
                        modifier = Modifier
                            .weight(1.45f)
                            .height(46.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (fileCount > 0) Icons.Default.PictureAsPdf else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                fileCount <= 0 -> "选择文件"
                                fileCount == 1 -> "导出 PDF"
                                else -> "导出 PDF ($fileCount)"
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversionFeedbackBanner(
    errorMessage: String?,
    results: List<ConverterViewModel.ConversionResult>,
    onDismiss: () -> Unit
) {
    if (errorMessage == null && results.isEmpty()) return

    val hasFailure = errorMessage != null || results.any { !it.success }
    val bgColor = if (hasFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fgColor = if (hasFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val text = errorMessage ?: run {
        val okCount = results.count { it.success }
        val failList = results.filter { !it.success }
        if (failList.isEmpty()) {
            "已完成 $okCount 份转换"
        } else {
            "成功 $okCount 份，失败 ${failList.size} 份（${failList.first().error ?: "未知错误"}）"
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = fgColor,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = fgColor,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSelectionBottomSheet(
    fonts: List<FontManager.FontEntry>,
    selectedFontId: String?,
    onSelectFont: (String) -> Unit,
    onDeleteFont: (String) -> Unit,
    onImportFont: () -> Unit,
    onDismiss: () -> Unit
) {
    val isSystemSelected = selectedFontId == FontManager.SYSTEM_FONT_ID ||
        (selectedFontId == null || fonts.none { it.id == selectedFontId })

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                FilledTonalButton(
                    onClick = onImportFont,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入", style = MaterialTheme.typography.labelMedium)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val totalRows = 1 + fonts.size
                Surface(
                    onClick = { onSelectFont(FontManager.SYSTEM_FONT_ID) },
                    shape = m3GroupedItemShape(0, totalRows),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("系统默认", style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            imageVector = if (isSystemSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSystemSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                fonts.forEachIndexed { idx, entry ->
                    val selected = entry.id == selectedFontId && !isSystemSelected
                    Surface(
                        onClick = { onSelectFont(entry.id) },
                        shape = m3GroupedItemShape(idx + 1, totalRows),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                            IconButton(onClick = { onDeleteFont(entry.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSelectionBottomSheet(
    presets: List<PresetRepository.ParamPreset>,
    selectedPresetName: String?,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePresetClick: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                FilledTonalButton(
                    onClick = onSavePresetClick,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存当前", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无预设",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    presets.forEachIndexed { idx, preset ->
                        val selected = preset.name == selectedPresetName
                        Surface(
                            onClick = { onLoadPreset(preset.name) },
                            shape = m3GroupedItemShape(idx, presets.size),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onDeletePreset(preset.name) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitVolumeDialog(
    fileName: String,
    suggestedVolumes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedVols by remember(fileName) { mutableStateOf(suggestedVolumes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("分卷导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "「$fileName」篇幅较长",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                M3ConnectedTextSegments(
                    options = listOf(2 to "2卷", 3 to "3卷", 4 to "4卷", 5 to "5卷"),
                    selected = selectedVols,
                    onSelect = { selectedVols = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedVols) }, shape = CircleShape) {
                Text("分卷转换")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.95f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${pages.size} 页", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                        Text("无预览内容")
                    }
                } else if (multiPage) {
                    val pageGroupStart = (currentPageIndex / 5) * 5
                    val groupEnd = minOf(pageGroupStart + 5, pages.size)
                    val rowScroll = remember { androidx.compose.foundation.ScrollState(0) }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .horizontalScroll(rowScroll)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in pageGroupStart until groupEnd) {
                            val page = pages.getOrNull(i) ?: continue
                            val isCurrent = i == currentPageIndex
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer
                                    )
                                    .padding(6.dp)
                                    .clickable { onPageChange(i) }
                            ) {
                                Text("P${i + 1}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
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
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PreviewCanvas(
                            page = currentPage,
                            typeface = typeface,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(6.dp))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onPageChange((currentPageIndex - 1).coerceAtLeast(0)) },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "上一页")
                        }
                        Text(
                            text = "${currentPageIndex + 1} / ${pages.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { onPageChange((currentPageIndex + 1).coerceAtMost(pages.size - 1)) },
                            enabled = currentPageIndex < pages.size - 1
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "下一页")
                        }
                    }
                }
            }
        }
    }
}

private fun resolvePaperPresetIndex(params: TypesettingParams): Int {
    val presets = TypesettingParams.KINDLE_PRESETS
    if (abs(params.pageWidth - TypesettingParams.mmToPoints(presets[1].widthMm)) < 2f) return 1
    if (abs(params.pageWidth - TypesettingParams.mmToPoints(presets[3].widthMm)) < 5f) return 3
    return 0
}

private fun tableModeShortName(mode: TableDisplayMode): String = when (mode) {
    TableDisplayMode.SMART_CARD -> "卡片表格"
    TableDisplayMode.COMPACT_GRID -> "紧凑网格"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024f * 1024f))
    }
}
