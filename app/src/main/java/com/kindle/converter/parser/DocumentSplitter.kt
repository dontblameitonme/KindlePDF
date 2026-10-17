package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TocEntry
import com.kindle.converter.data.VolumeSplitMode
import kotlin.math.abs

/**
 * 智能长文档分卷拆分引擎。
 *
 * 核心设计目标：
 * 1. 章节大纲对齐（自然章节边界）：优先按 Document.toc 中的大纲目录或正文标题（Block.Heading）边界切分，
 *    确保每一卷都从完整的第一章/首节开始，绝不把一个段落或一个章节腰斩；
 * 2. 目录书签物理索引精确重映射：切分后各卷独立保留属于该卷的 TocEntry 列表，
 *    自动将 blockIndex 减去当前卷在原书中的全局起始偏移量，使各卷生成的 PDF 侧边大纲书签在第 1 页精准生效；
 * 3. 卷名与标题典雅命名：
 *    - 2 卷时自动命名为「上册 / 下册」；
 *    - 3 卷时自动命名为「上册 / 中册 / 下册」；
 *    - 4 卷及以上自动命名为「第1卷 / 第2卷 / 第3卷...」；
 * 4. 智能容量自适应测算（calculateAutoVolumes）：
 *    若设为 AUTO，预估长书真实排版页数（以 Kindle 6 寸墨水屏每页 ~250 字为标尺）；
 *    若小于 600 页则保持单本，每满约 500 页自动扩增一卷（最高至 10 卷）。
 */
object DocumentSplitter {

    data class Volume(
        val index: Int,            // 0, 1, 2...
        val totalVolumes: Int,    // 总卷数
        val label: String,        // "上册", "第1卷"...
        val document: Document
    )

    /**
     * 智能测算建议分卷数。
     */
    fun calculateAutoVolumes(document: Document): Int {
        val totalBlocks = document.blocks.size
        if (totalBlocks < 300) return 1

        // 统计总文字量
        var totalChars = 0L
        for (block in document.blocks) {
            when (block) {
                is Block.Paragraph -> totalChars += block.runs.sumOf { it.text.length.toLong() }
                is Block.Heading -> totalChars += block.runs.sumOf { it.text.length.toLong() }
                is Block.QuoteBlock -> totalChars += block.runs.sumOf { it.text.length.toLong() }
                is Block.ListItem -> totalChars += block.runs.sumOf { it.text.length.toLong() }
                else -> {}
            }
        }

        // Kindle 6 英寸 12pt~14pt 一页约 240~280 汉字
        val estimatedPages = (totalChars / 250).coerceAtLeast((totalBlocks / 4).toLong()).toInt()

        return when {
            estimatedPages <= 600 -> 1
            estimatedPages <= 1100 -> 2
            estimatedPages <= 1700 -> 3
            estimatedPages <= 2400 -> 4
            estimatedPages <= 3200 -> 5
            else -> (estimatedPages / 500).coerceIn(2, 10)
        }
    }

    /**
     * 根据分卷模式拆分文档。
     */
    fun split(document: Document, mode: VolumeSplitMode): List<Volume> {
        val count = when (mode) {
            VolumeSplitMode.NONE -> 1
            VolumeSplitMode.AUTO -> calculateAutoVolumes(document)
            else -> mode.volumeCount
        }
        return splitIntoVolumes(document, count)
    }

    /**
     * 将文档切分为指定卷数。
     */
    fun splitIntoVolumes(document: Document, targetVolumes: Int): List<Volume> {
        val numVolumes = targetVolumes.coerceAtLeast(1)
        val blocks = document.blocks
        if (numVolumes <= 1 || blocks.size < numVolumes) {
            return listOf(Volume(0, 1, "全一卷", document))
        }

        // 确定切分点的 Block 索引列表（包含 0 和 blocks.size）
        val cutBlockIndices = findCutBlockIndices(document, numVolumes)

        val volumes = mutableListOf<Volume>()
        for (i in 0 until numVolumes) {
            val startBlock = cutBlockIndices[i]
            val endBlock = cutBlockIndices[i + 1]
            if (startBlock >= endBlock) continue

            val subBlocks = blocks.subList(startBlock, endBlock)

            // 重映射当前卷范围内的 TOC 目录大纲
            val subToc = document.toc
                .filter { it.blockIndex in startBlock until endBlock }
                .map { it.copy(blockIndex = it.blockIndex - startBlock) }

            val label = getVolumeLabel(i, numVolumes)
            val subTitle = if (document.title.isNotBlank()) {
                "${document.title}（$label）"
            } else {
                label
            }

            val subDoc = Document(
                blocks = subBlocks,
                toc = subToc,
                title = subTitle,
                author = document.author
            )
            volumes.add(Volume(i, numVolumes, label, subDoc))
        }

        return if (volumes.isEmpty()) listOf(Volume(0, 1, "全一卷", document)) else volumes
    }

    /**
     * 寻找最佳切分点：优先依据 TOC 章节边界，次选 Heading 边界，兜底按 block 均分。
     */
    private fun findCutBlockIndices(document: Document, numVolumes: Int): List<Int> {
        val totalBlocks = document.blocks.size
        val cuts = mutableListOf<Int>()
        cuts.add(0)

        // 策略 1：如果文档有充裕的 TOC 章节大纲
        val sortedToc = document.toc.sortedBy { it.blockIndex }.filter { it.blockIndex in 1 until totalBlocks }
        if (sortedToc.size >= numVolumes) {
            val idealStep = totalBlocks.toFloat() / numVolumes
            var lastIndex = 0
            for (v in 1 until numVolumes) {
                val idealBlock = (v * idealStep).toInt()
                // 找到 blockIndex 大于 lastIndex 且最靠近 idealBlock 的 TOC 项
                val candidate = sortedToc
                    .filter { it.blockIndex > lastIndex && it.blockIndex < totalBlocks }
                    .minByOrNull { abs(it.blockIndex - idealBlock) }

                val cut = candidate?.blockIndex ?: ((lastIndex + idealBlock) / 2).coerceIn(lastIndex + 1, totalBlocks - 1)
                cuts.add(cut)
                lastIndex = cut
            }
            cuts.add(totalBlocks)
            return cuts
        }

        // 策略 2：如果没有足够的 TOC，寻找 Block.Heading 标题块
        val headingIndices = document.blocks.indices
            .filter { it > 0 && document.blocks[it] is Block.Heading }
        if (headingIndices.size >= numVolumes) {
            val idealStep = totalBlocks.toFloat() / numVolumes
            var lastIndex = 0
            for (v in 1 until numVolumes) {
                val idealBlock = (v * idealStep).toInt()
                val candidate = headingIndices
                    .filter { it > lastIndex && it < totalBlocks }
                    .minByOrNull { abs(it - idealBlock) }

                val cut = candidate ?: ((lastIndex + idealBlock) / 2).coerceIn(lastIndex + 1, totalBlocks - 1)
                cuts.add(cut)
                lastIndex = cut
            }
            cuts.add(totalBlocks)
            return cuts
        }

        // 策略 3：纯段落内容，按 block 数量大致平分
        val step = totalBlocks / numVolumes
        for (v in 1 until numVolumes) {
            cuts.add(v * step)
        }
        cuts.add(totalBlocks)
        return cuts
    }

    fun getVolumeLabel(index: Int, total: Int): String {
        return when (total) {
            2 -> if (index == 0) "上册" else "下册"
            3 -> when (index) {
                0 -> "上册"
                1 -> "中册"
                else -> "下册"
            }
            else -> "第${index + 1}卷"
        }
    }

    /**
     * 将原始纯文本/Markdown 字节流在换行符边界处切分为多段（用于超大文本无法一次性全部解析为对象树的极端场景）。
     */
    fun splitTextBytes(bytes: ByteArray, targetVolumes: Int): List<ByteArray> {
        val numVolumes = targetVolumes.coerceAtLeast(1)
        if (numVolumes <= 1 || bytes.isEmpty()) return listOf(bytes)

        val totalLen = bytes.size
        val cuts = mutableListOf<Int>()
        cuts.add(0)

        val idealStep = totalLen.toFloat() / numVolumes
        var lastCut = 0
        for (v in 1 until numVolumes) {
            val ideal = (v * idealStep).toInt()
            // 在 ideal 附近向前/向后寻找换行符 '\n' (0x0A)
            var found = -1
            for (offset in 0 until (idealStep / 2).toInt()) {
                val fwd = ideal + offset
                if (fwd in (lastCut + 1) until totalLen && bytes[fwd] == 0x0A.toByte()) {
                    found = fwd + 1
                    break
                }
                val bwd = ideal - offset
                if (bwd in (lastCut + 1) until totalLen && bytes[bwd] == 0x0A.toByte()) {
                    found = bwd + 1
                    break
                }
            }
            val cut = if (found > lastCut) found else ideal.coerceIn(lastCut + 1, totalLen - 1)
            cuts.add(cut)
            lastCut = cut
        }
        cuts.add(totalLen)

        val result = mutableListOf<ByteArray>()
        for (i in 0 until numVolumes) {
            val start = cuts[i]
            val end = cuts[i + 1]
            if (start < end) {
                result.add(bytes.copyOfRange(start, end))
            }
        }
        return if (result.isEmpty()) listOf(bytes) else result
    }
}
