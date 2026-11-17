package com.youngs.dailynet.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import com.youngs.dailynet.ui.theme.CoralContainerLight
import com.youngs.dailynet.ui.theme.DeficitLight
import com.youngs.dailynet.ui.theme.Ink
import com.youngs.dailynet.ui.theme.MacroCarb
import com.youngs.dailynet.ui.theme.MacroFat
import com.youngs.dailynet.ui.theme.MacroProtein
import com.youngs.dailynet.ui.theme.MintContainerLight
import com.youngs.dailynet.ui.theme.OnCoralContainerLight
import com.youngs.dailynet.ui.theme.OutlineVariantLight
import com.youngs.dailynet.ui.theme.SubInk
import com.youngs.dailynet.ui.theme.SurfaceLight
import com.youngs.dailynet.ui.theme.SurfaceVariantLight
import com.youngs.dailynet.ui.theme.SurplusLight
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 하루 결산을 공유용 PNG 한 장으로 그린다.
 *
 * Compose가 아니라 android.graphics.Canvas로 직접 그리는 이유:
 * - 화면 밖에서 기기 밀도와 무관하게 1080px 폭 한 장으로 나와야 한다 (인스타 스토리·카톡)
 * - 공유 시트에 넘길 Bitmap 하나로 끝내야 한다 (뷰 계층을 캡처하지 않는다)
 *
 * 높이는 내용에 따라 달라진다 (끼니·운동 항목 수, AI 총평 유무, 두 줄로 접힌 메뉴 이름).
 * 그래서 한 번 재고(measure) 그 높이만큼 Bitmap을 만든 뒤 같은 순서로 다시 그린다.
 * 재는 단계에서는 canvas가 null이라 글자 폭·줄 수만 세고 아무것도 그리지 않는다.
 *
 * 색은 ui/theme/Color.kt의 라이트 팔레트를 그대로 쓴다. 영수증은 종이라서
 * 기기가 다크 모드여도 밝은 종이로 나간다 — 공유된 이미지가 읽히는 쪽이 우선이다.
 */

private const val RECEIPT_WIDTH = 1080
private const val SIDE_MARGIN = 64f
private const val TOP_BOTTOM_MARGIN = 72f
private const val PAPER_PADDING = 56f
private const val ZIGZAG = 26f
private const val TOOTH = 36f

object DailyReceiptImage {

    /** 공유용 카드의 고정 폭(px). 세로 길이는 내용에 따라 달라진다 */
    const val WIDTH = RECEIPT_WIDTH

    fun render(receipt: DailyReceipt, labels: ReceiptLabels): Bitmap {
        val painter = ReceiptPainter(receipt, labels)
        val contentHeight = painter.measureContent()

        val paperTop = TOP_BOTTOM_MARGIN
        val paperBottom = paperTop + ZIGZAG + PAPER_PADDING + contentHeight + PAPER_PADDING + ZIGZAG
        val height = (paperBottom + TOP_BOTTOM_MARGIN).roundToInt()

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(bitmap), paperTop, paperBottom)
        return bitmap
    }
}

private class ReceiptPainter(
    private val receipt: DailyReceipt,
    private val labels: ReceiptLabels
) {
    private var canvas: Canvas? = null
    private var y = 0f

    private val left = SIDE_MARGIN + PAPER_PADDING
    private val right = RECEIPT_WIDTH - SIDE_MARGIN - PAPER_PADDING
    private val contentWidth = (right - left).roundToInt()

    private val ink = Ink.toArgb()
    private val subInk = SubInk.toArgb()
    private val paper = SurfaceLight.toArgb()
    private val pageBackground = Ink.toArgb()
    private val mintContainer = MintContainerLight.toArgb()
    private val coralContainer = CoralContainerLight.toArgb()
    private val onCoralContainer = OnCoralContainerLight.toArgb()
    private val surfaceVariant = SurfaceVariantLight.toArgb()
    private val outlineVariant = OutlineVariantLight.toArgb()
    private val deficit = DeficitLight.toArgb()
    private val surplus = SurplusLight.toArgb()
    private val carb = MacroCarb.toArgb()
    private val protein = MacroProtein.toArgb()
    private val fat = MacroFat.toArgb()

    /** 내용만 재서 높이를 돌려준다 (아무것도 그리지 않는다) */
    fun measureContent(): Float {
        canvas = null
        y = 0f
        content()
        return y
    }

    fun draw(target: Canvas, paperTop: Float, paperBottom: Float) {
        target.drawColor(pageBackground)
        target.drawPath(paperPath(paperTop, paperBottom), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paper
            style = Paint.Style.FILL
        })
        canvas = target
        y = paperTop + ZIGZAG + PAPER_PADDING
        content()
    }

    /** 위·아래가 톱니인 종이 한 장 */
    private fun paperPath(top: Float, bottom: Float): Path {
        val paperLeft = SIDE_MARGIN
        val paperRight = RECEIPT_WIDTH - SIDE_MARGIN
        val width = paperRight - paperLeft
        val teeth = max(1, (width / TOOTH).roundToInt())
        val step = width / teeth
        val path = Path()
        path.moveTo(paperLeft, top + ZIGZAG)
        for (i in 0 until teeth) {
            val x = paperLeft + i * step
            path.lineTo(x + step / 2f, top)
            path.lineTo(x + step, top + ZIGZAG)
        }
        path.lineTo(paperRight, bottom - ZIGZAG)
        for (i in teeth - 1 downTo 0) {
            val x = paperRight - (teeth - i) * step
            path.lineTo(x + step / 2f, bottom)
            path.lineTo(x, bottom - ZIGZAG)
        }
        path.close()
        return path
    }

    // ── 내용 ──────────────────────────────────────────────────────

    private fun content() {
        badgeRow()
        y += 30f

        val titlePaint = paint(58f, ink, bold = true)
        val titleLayout = layout(labels.title, titlePaint, contentWidth, maxLines = 2)
        drawLayout(titleLayout, left, y)
        y += titleLayout.height + 8f

        if (receipt.serial.isNotEmpty()) {
            val serialPaint = paint(26f, subInk)
            drawTop("${labels.serialPrefix} ${receipt.serial}", serialPaint, left, y)
            y += lineHeight(serialPaint)
        }
        y += 24f
        dashed()
        y += 26f

        val headerPaint = paint(26f, subInk, medium = true)
        drawTop(labels.columnItem, headerPaint, left, y)
        drawRight(labels.columnKcal, headerPaint, y)
        y += lineHeight(headerPaint) + 16f

        receipt.lines.forEach { line ->
            lineRow(line)
            y += 18f
        }
        if (receipt.lines.isNotEmpty()) y -= 18f

        y += 22f
        dashed()
        y += 28f

        netCard()
        y += 34f

        macroSection()
        y += 32f

        if (receipt.aiNote.isNotBlank()) {
            aiNoteBox()
            y += 32f
        }

        y += 2f
        dashed()
        y += 30f

        barcode()
        y += 22f

        val footerPaint = paint(24f, subInk, tracking = 0.02f)
        drawCentered(labels.footer, footerPaint, RECEIPT_WIDTH / 2f, y)
        y += lineHeight(footerPaint)
    }

    private fun badgeRow() {
        val badgePaint = paint(24f, ink, bold = true, tracking = 0.12f)
        val badgeWidth = badgePaint.measureText(labels.badge) + 44f
        val badgeHeight = 46f
        fillRound(left, y, left + badgeWidth, y + badgeHeight, badgeHeight / 2f, mintContainer)
        drawCentered(
            labels.badge, badgePaint,
            left + badgeWidth / 2f,
            y + (badgeHeight - lineHeight(badgePaint)) / 2f
        )

        val datePaint = paint(26f, subInk)
        drawRight(receipt.dateText, datePaint, y + (badgeHeight - lineHeight(datePaint)) / 2f)
        y += badgeHeight
    }

    private fun lineRow(line: ReceiptLine) {
        val namePaint = paint(33f, ink)
        val kcalPaint = paint(33f, if (line.isBurn) deficit else ink, bold = true)
        val unitPaint = paint(23f, if (line.isBurn) deficit else subInk, medium = true)

        val kcalText = signedKcalText(line.kcal, line.isBurn)
        val unitText = " kcal"
        val kcalWidth = kcalPaint.measureText(kcalText)
        val unitWidth = unitPaint.measureText(unitText)

        // 이름은 남은 폭 안에서 두 줄까지. 넘치면 말줄임 — 옆의 숫자를 밀지 않는다
        val nameWidth = (contentWidth - kcalWidth - unitWidth - 28f).roundToInt().coerceAtLeast(1)
        val nameLayout = layout(line.title, namePaint, nameWidth, maxLines = 2)
        drawLayout(nameLayout, left, y)

        val baseline = y + nameLayout.getLineBaseline(0)
        drawText(kcalText, kcalPaint, right - unitWidth - kcalWidth, baseline)
        drawText(unitText, unitPaint, right - unitWidth, baseline)

        y += max(nameLayout.height.toFloat(), lineHeight(kcalPaint)) + 2f
    }

    private fun netCard() {
        val pad = 34f
        val labelPaint = paint(28f, subInk, medium = true)
        val badgeText = if (receipt.isDeficit) labels.badgeDeficit else labels.badgeSurplus
        val badgeTextPaint = paint(
            25f,
            if (receipt.isDeficit) deficit else onCoralContainer,
            bold = true
        )
        val badgeWidth = badgeTextPaint.measureText(badgeText) + 40f
        val badgeHeight = 42f

        val netColor = if (receipt.isDeficit) deficit else surplus
        val netPaint = paint(84f, netColor, bold = true)
        val unitPaint = paint(40f, netColor, medium = true)
        val netText = receipt.netText
        val unitText = " kcal"
        val netWidth = netPaint.measureText(netText)
        val unitWidth = unitPaint.measureText(unitText)

        val badgeTop = pad + lineHeight(labelPaint) + 14f
        val leftBlockHeight = badgeTop + badgeHeight
        val valueHeight = lineHeight(netPaint)
        val cardHeight = max(leftBlockHeight, valueHeight) + pad * 2f

        fillRound(left, y, right, y + cardHeight, 20f, surfaceVariant)

        drawTop(labels.netLabel, labelPaint, left + 30f, y + pad)
        fillRound(
            left + 30f, y + badgeTop,
            left + 30f + badgeWidth, y + badgeTop + badgeHeight,
            badgeHeight / 2f,
            if (receipt.isDeficit) mintContainer else coralContainer
        )
        drawCentered(
            badgeText, badgeTextPaint,
            left + 30f + badgeWidth / 2f,
            y + badgeTop + (badgeHeight - lineHeight(badgeTextPaint)) / 2f
        )

        // 오른쪽: 큰 숫자 + 단위. 카드 안에서 세로 가운데
        val valueTop = y + (cardHeight - valueHeight) / 2f
        val baseline = valueTop - netPaint.ascent()
        drawText(netText, netPaint, right - 30f - unitWidth - netWidth, baseline)
        drawText(unitText, unitPaint, right - 30f - unitWidth, baseline)

        y += cardHeight
    }

    private fun macroSection() {
        val titlePaint = paint(30f, ink, medium = true)
        val intakePaint = paint(25f, subInk)
        drawTop(labels.macroTitle, titlePaint, left, y)
        drawRight(
            String.format(labels.intakeTotalFormat, formatKcal(receipt.intakeKcal)),
            intakePaint, y
        )
        y += max(lineHeight(titlePaint), lineHeight(intakePaint)) + 16f

        val barHeight = 22f
        val total = receipt.carbGram + receipt.proteinGram + receipt.fatGram
        val current = canvas
        if (total <= 0f) {
            fillRound(left, y, right, y + barHeight, barHeight / 2f, surfaceVariant)
        } else if (current != null) {
            // 둥근 막대 하나로 잘라 놓고 세 구간을 이어 그린다 (구간마다 둥글면 이음매가 보인다)
            val save = current.save()
            val clip = Path().apply {
                addRoundRect(
                    RectF(left, y, right, y + barHeight),
                    barHeight / 2f, barHeight / 2f, Path.Direction.CW
                )
            }
            current.clipPath(clip)
            var x = left
            listOf(
                receipt.carbGram to carb,
                receipt.proteinGram to protein,
                receipt.fatGram to fat
            ).forEach { (grams, color) ->
                val width = (right - left) * (grams / total)
                if (width > 0f) {
                    rect(x, y, x + width, y + barHeight, color)
                    x += width
                }
            }
            current.restoreToCount(save)
        }
        y += barHeight + 18f

        // 범례: ● 탄수화물 142g   ● 단백질 118g   ● 지방 44g — 폭에 맞춰 고르게 벌린다
        val labelPaint = paint(25f, subInk)
        val valuePaint = paint(25f, ink, bold = true)
        val dot = 15f
        val entries = listOf(
            Triple(labels.macroCarb, receipt.carbGram, carb),
            Triple(labels.macroProtein, receipt.proteinGram, protein),
            Triple(labels.macroFat, receipt.fatGram, fat)
        )
        val segments = entries.map { (name, grams, _) ->
            dot + 10f + labelPaint.measureText(name) + 6f + valuePaint.measureText(formatGrams(grams) + "g")
        }
        val spacing = if (entries.size > 1) {
            ((contentWidth - segments.sum()) / (entries.size - 1)).coerceAtLeast(0f)
        } else {
            0f
        }
        var x = left
        entries.forEachIndexed { index, (name, grams, color) ->
            fillRound(
                x, y + (lineHeight(labelPaint) - dot) / 2f,
                x + dot, y + (lineHeight(labelPaint) + dot) / 2f,
                dot / 2f, color
            )
            x += dot + 10f
            drawTop(name, labelPaint, x, y)
            x += labelPaint.measureText(name) + 6f
            drawTop(formatGrams(grams) + "g", valuePaint, x, y)
            x += valuePaint.measureText(formatGrams(grams) + "g")
            if (index < entries.size - 1) x += spacing
        }
        y += lineHeight(labelPaint)
    }

    private fun aiNoteBox() {
        val pad = 30f
        val titlePaint = paint(26f, deficit, bold = true)
        val bodyPaint = paint(30f, ink)
        val bodyLayout = layout(
            receipt.aiNote, bodyPaint,
            (contentWidth - pad * 2f).roundToInt(), maxLines = 4
        )
        val boxHeight = pad + lineHeight(titlePaint) + 12f + bodyLayout.height + pad

        fillRound(left, y, right, y + boxHeight, 18f, surfaceVariant)
        strokeRound(left, y, right, y + boxHeight, 18f, outlineVariant, 2f)
        drawTop(labels.aiTitle, titlePaint, left + pad, y + pad)
        drawLayout(bodyLayout, left + pad, y + pad + lineHeight(titlePaint) + 12f)
        y += boxHeight
    }

    /**
     * 가운데 정렬 바코드. 실제 바코드가 아니라 장식이지만 날짜·순칼로리에서 만들어 내므로
     * 같은 날 영수증은 늘 같은 무늬가 나온다. 시안에서 왼쪽으로 치우쳐 있던 것을 가운데로 둔다.
     */
    private fun barcode() {
        val barsHeight = 104f
        var state = "${receipt.serial}|${receipt.netKcal}|${receipt.lines.size}".hashCode().toLong() or 1L
        val widths = mutableListOf<Float>()
        repeat(52) {
            state = state * 6364136223846793005L + 1442695040888963407L
            widths += 2f + (abs(state ushr 33) % 7L).toFloat()   // 막대 2~8px
            state = state * 6364136223846793005L + 1442695040888963407L
            widths += 3f + (abs(state ushr 33) % 4L).toFloat()   // 간격 3~6px
        }
        val rawWidth = widths.sum()
        val scale = if (rawWidth > contentWidth) contentWidth / rawWidth else 1f
        var x = left + (contentWidth - rawWidth * scale) / 2f
        widths.forEachIndexed { index, width ->
            val scaled = width * scale
            if (index % 2 == 0) rect(x, y, x + scaled, y + barsHeight, ink)
            x += scaled
        }
        y += barsHeight
    }

    // ── 그리기 도구 (canvas가 null이면 재는 단계라 아무것도 하지 않는다) ──

    private fun paint(
        size: Float,
        textColor: Int,
        bold: Boolean = false,
        medium: Boolean = false,
        tracking: Float = 0f
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = textColor
        typeface = when {
            bold -> Typeface.create("sans-serif", Typeface.BOLD)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        isSubpixelText = true
        letterSpacing = tracking
    }

    private fun layout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int = Int.MAX_VALUE
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.15f)
        .setMaxLines(maxLines)
        .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
        .build()

    private fun lineHeight(paint: TextPaint): Float = paint.descent() - paint.ascent()

    private fun drawText(text: String, paint: TextPaint, x: Float, baseline: Float) {
        canvas?.drawText(text, x, baseline, paint)
    }

    /** [top]을 글자의 위 끝으로 보고 그린다 */
    private fun drawTop(text: String, paint: TextPaint, x: Float, top: Float) {
        drawText(text, paint, x, top - paint.ascent())
    }

    private fun drawRight(text: String, paint: TextPaint, top: Float) {
        drawText(text, paint, right - paint.measureText(text), top - paint.ascent())
    }

    private fun drawCentered(text: String, paint: TextPaint, centerX: Float, top: Float) {
        drawText(text, paint, centerX - paint.measureText(text) / 2f, top - paint.ascent())
    }

    private fun drawLayout(layout: StaticLayout, x: Float, top: Float) {
        canvas?.let {
            it.save()
            it.translate(x, top)
            layout.draw(it)
            it.restore()
        }
    }

    private fun fillRound(startX: Float, startY: Float, endX: Float, endY: Float, radius: Float, fill: Int) {
        canvas?.drawRoundRect(
            startX, startY, endX, endY, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                style = Paint.Style.FILL
            }
        )
    }

    private fun strokeRound(
        startX: Float, startY: Float, endX: Float, endY: Float,
        radius: Float, stroke: Int, width: Float
    ) {
        canvas?.drawRoundRect(
            startX, startY, endX, endY, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                strokeWidth = width
                style = Paint.Style.STROKE
            }
        )
    }

    private fun rect(startX: Float, startY: Float, endX: Float, endY: Float, fill: Int) {
        canvas?.drawRect(
            startX, startY, endX, endY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        )
    }

    /** 절취선. 기기 밀도와 무관하게 같은 간격으로 보이게 px 고정 */
    private fun dashed() {
        val current = canvas ?: return
        current.drawLine(
            left, y, right, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = outlineVariant
                strokeWidth = 3f
                pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f)
            }
        )
    }
}
