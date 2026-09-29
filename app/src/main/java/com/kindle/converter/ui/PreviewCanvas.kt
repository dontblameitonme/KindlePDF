package com.kindle.converter.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kindle.converter.data.LayoutElement
import com.kindle.converter.data.PageLayout
import androidx.compose.ui.unit.IntSize

/**
 * Renders a PageLayout on a Compose Canvas.
 * Uses the same PageLayout data that feeds the PDF generator,
 * ensuring "what you see is what you get".
 *
 * Pinch-to-zoom is supported for closer inspection.
 *
 * 关键点（v10）：
 * 1) 不再用 drawColor(WHITE) 把整张画布涂白——改为在页面区域内填充白色，
 *    页面外保持透明，这样页面边框与底色对比鲜明，永远能看到页边界。
 * 2) 页面边框加深到 3f 灰黑色，避免在白底上"看不见"。
 * 3) 默认使用 Typeface.DEFAULT + 加粗 fallback，即使 PDF 字体未加载到 Compose Paint 也能显示文字。
 */
@Composable
fun PreviewCanvas(
    page: PageLayout?,
    typeface: Typeface?,
    modifier: Modifier = Modifier
) {
    if (page == null) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No preview available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 兜底诊断：页内若无元素
    if (page.elements.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "[PreviewCanvas] 该页无元素",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val paint = remember(typeface) {
        Paint().apply {
            isAntiAlias = true
            this.typeface = typeface ?: Typeface.DEFAULT
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset += pan
                }
            }
    ) {
        // Calculate fit-to-contain scale
        val fitScale = minOf(
            size.width / page.width,
            size.height / page.height
        )
        val finalScale = fitScale * scale

        // Page metrics
        val pageW = page.width * finalScale
        val pageH = page.height * finalScale
        val startX = (size.width - pageW) / 2 + offset.x
        val startY = (size.height - pageH) / 2 + offset.y

        drawIntoCanvas { canvas ->
            // 只在页面区域内画白底；外层保持透明，让 surfaceVariant（深色）显出页面边界
            val pageBgPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.nativeCanvas.drawRect(
                startX, startY, startX + pageW, startY + pageH,
                pageBgPaint
            )

            // 加深页面边框（3f 深灰），确保在白底上肉眼可见
            val borderPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.rgb(80, 80, 80)
            }
            canvas.nativeCanvas.drawRect(
                startX, startY, startX + pageW, startY + pageH,
                borderPaint
            )

            // Draw text elements
            for (element in page.elements) {
                when (element) {
                    is LayoutElement.TextLine -> {
                        for (seg in element.segments) {
                            val x = startX + seg.x * finalScale
                            // baseline: topY + ascent ≈ topY + fontSize * 0.8
                            val y = startY + (element.topY + seg.fontSize * 0.8f) * finalScale

                            paint.textSize = seg.fontSize * finalScale
                            paint.isFakeBoldText = seg.bold
                            paint.isUnderlineText = false
                            paint.color = android.graphics.Color.BLACK

                            // v7：JUSTIFY 字符间距。paint.letterSpacing 是相对 EM 倍率。
                            paint.letterSpacing = if (seg.charSpacing != 0f) {
                                seg.charSpacing / seg.fontSize
                            } else 0f

                            try {
                                canvas.nativeCanvas.drawText(seg.text, x, y, paint)
                            } catch (e: Exception) {
                                // Skip if rendering fails
                            }
                        }
                    }

                    is LayoutElement.RuleLine -> {
                        val linePaint = Paint().apply {
                            color = android.graphics.Color.rgb(90, 90, 90)
                            strokeWidth = (element.strokeWidth * finalScale).coerceAtLeast(1f)
                        }
                        val x1 = startX + element.x * finalScale
                        val y1 = startY + element.y * finalScale
                        val x2 = startX + (element.x + element.width) * finalScale
                        val y2 = startY + (element.y + element.height) * finalScale
                        canvas.nativeCanvas.drawLine(x1, y1, x2, y2, linePaint)
                    }

                    is LayoutElement.ImageElement -> {
                        // 真实绘制：BitmapFactory 解码后按页面缩放比例绘制
                        try {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false }
                            val bmp = BitmapFactory.decodeByteArray(
                                element.bytes, 0, element.bytes.size, opts
                            )
                            if (bmp != null) {
                                val left = startX + element.x * finalScale
                                val top  = startY + element.y * finalScale
                                val right = left + element.width * finalScale
                                val bottom = top + element.height * finalScale
                                canvas.nativeCanvas.drawBitmap(
                                    bmp, null,
                                    android.graphics.RectF(left, top, right, bottom),
                                    null
                                )
                                bmp.recycle()
                            } else {
                                // 解码失败：画一个占位矩形
                                drawImagePlaceholder(startX, startY, element, finalScale, canvas)
                            }
                        } catch (e: Exception) {
                            drawImagePlaceholder(startX, startY, element, finalScale, canvas)
                        }
                    }
                }
            }
        }
    }
}

private fun drawImagePlaceholder(
    startX: Float,
    startY: Float,
    element: LayoutElement.ImageElement,
    finalScale: Float,
    canvas: androidx.compose.ui.graphics.Canvas
) {
    val imgPaint = Paint().apply {
        color = android.graphics.Color.LTGRAY
        style = Paint.Style.FILL
    }
    canvas.nativeCanvas.drawRect(
        startX + element.x * finalScale,
        startY + element.y * finalScale,
        startX + (element.x + element.width) * finalScale,
        startY + (element.y + element.height) * finalScale,
        imgPaint
    )
    val txtPaint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        textSize = 12f
    }
    canvas.nativeCanvas.drawText(
        "图片",
        startX + element.x * finalScale + 4f,
        startY + element.y * finalScale + 16f,
        txtPaint
    )
}
