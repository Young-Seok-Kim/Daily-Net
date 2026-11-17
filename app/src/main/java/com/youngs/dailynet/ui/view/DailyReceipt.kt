package com.youngs.dailynet.ui.view

import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.DailyRecordModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 하루 결산을 '영수증' 한 장으로 옮기는 산수.
 *
 * 화면(Compose)도 이미지(Canvas)도 이 결과만 보고 그린다. 그래서 여기에는 Android·Compose
 * 의존이 없다 — 숫자와 문구가 맞는지는 DailyReceiptTest로 JVM에서 못박는다.
 *
 * 문구는 언어마다 달라서 [ReceiptLabels]로 받는다 (화면에서 stringResource로 채운다).
 * 이 파일이 직접 문자열을 만들면 영어 사용자에게 한국어 영수증이 나간다.
 */

/** 영수증에 찍히는 고정 문구. 화면에서 stringResource로 채워 넣는다 */
data class ReceiptLabels(
    val title: String,
    /** 왼쪽 위 배지. 브랜드명이라 번역하지 않는다 */
    val badge: String,
    val serialPrefix: String,
    val columnItem: String,
    val columnKcal: String,
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val snack: String,
    val exercise: String,
    val bmr: String,
    val netLabel: String,
    val badgeDeficit: String,
    val badgeSurplus: String,
    val macroTitle: String,
    /** "%1$s" 자리에 총 섭취 kcal 문자열이 들어간다 */
    val intakeTotalFormat: String,
    val aiTitle: String,
    val footer: String,
    val macroCarb: String,
    val macroProtein: String,
    val macroFat: String,
    /** 운동 시간 표기 단위 ("시간" / "h") */
    val hourUnit: String,
    /** 운동 시간 표기 단위 ("분" / "m") */
    val minuteUnit: String
)

/**
 * 영수증의 한 줄.
 *
 * @param isBurn true면 소모(-kcal), false면 섭취(+kcal)
 */
data class ReceiptLine(
    val title: String,
    val kcal: Int,
    val isBurn: Boolean
)

/** 영수증 한 장을 그리는 데 필요한 값 전부. 계산은 [DailyReceiptBuilder]가 끝낸 상태다 */
data class DailyReceipt(
    /** "2026. 11. 17. (화)" */
    val dateText: String,
    /** "#20261117" */
    val serial: String,
    val lines: List<ReceiptLine>,
    /** 영수증에 찍힌 섭취 줄의 합. 끼니를 건너뛴 날에도 표와 숫자가 어긋나지 않게 여기서 센다 */
    val intakeKcal: Int,
    val netKcal: Int,
    val carbGram: Float,
    val proteinGram: Float,
    val fatGram: Float,
    /** AI 총평. 비어 있으면 영수증에서 상자를 통째로 뺀다 */
    val aiNote: String
) {
    /** 순칼로리 0은 적자 쪽으로 친다 (앱 전체 규칙, NetColors.of 참고) */
    val isDeficit: Boolean get() = netKcal <= 0

    val hasMacros: Boolean get() = carbGram > 0f || proteinGram > 0f || fatGram > 0f

    /** "-690" 처럼 부호를 붙인 순칼로리. 0이면 "0" */
    val netText: String
        get() = when {
            netKcal < 0 -> "-" + formatKcal(-netKcal)
            netKcal > 0 -> "+" + formatKcal(netKcal)
            else -> "0"
        }
}

/** 1,234 처럼 세 자리마다 쉼표. 영수증·목록이 같은 표기를 쓰도록 여기 하나만 둔다 */
fun formatKcal(value: Int): String = String.format(Locale.KOREA, "%,d", value)

/** "+320" / "-280". 앞의 부호는 소모·섭취를 가른다 */
fun signedKcalText(kcal: Int, isBurn: Boolean): String =
    (if (isBurn) "-" else "+") + formatKcal(kcal)

object DailyReceiptBuilder {

    /** 서버가 붙이는 걸음 항목 이름("걸음 9,420보" / "9,420 steps")을 알아보는 규칙 (functions/exerciseCheck.js와 같다) */
    private val STEPS = Regex("""(\d[\d,]*)\s*(?:보|steps?)""", RegexOption.IGNORE_CASE)

    /**
     * 기록 한 건을 영수증 값으로 옮긴다.
     *
     * 분석 구조([AnalysisDetail])가 있으면 그 항목을 그대로 줄로 세우고, 그 이전(b33 이전) 기록은
     * 사용자가 적은 글과 저장된 집계 컬럼으로 채운다. 아무 숫자도 없는 기록이면 줄이 비고
     * 순칼로리만 남는다 — 없는 kcal을 지어내지 않는다.
     */
    fun build(
        record: DailyRecordModel,
        labels: ReceiptLabels,
        locale: Locale = Locale.getDefault()
    ): DailyReceipt {
        // 구조가 온전한 기록만 항목을 그린다. b33 이전 기록은 항목·탄단지가 비어 있어 그리면 빈 표가 된다
        val detail = record.analysisDetail?.takeIf { it.isRenderable }
        val lines = mutableListOf<ReceiptLine>()

        fun addMeal(
            label: String,
            items: List<AnalysisItem>,
            detailKcal: Int,
            recordKcal: Int,
            rawText: String
        ) {
            val kcal = if (detailKcal > 0) detailKcal else recordKcal
            // 먹은 게 없으면 줄을 만들지 않는다. 영수증은 0kcal 줄로 채우지 않는다
            if (kcal <= 0) return
            val menu = menuText(items, rawText)
            lines += ReceiptLine(
                title = if (menu.isEmpty()) label else "$label · $menu",
                kcal = kcal,
                isBurn = false
            )
        }

        addMeal(
            labels.breakfast, detail?.meals?.breakfast.orEmpty(),
            detail?.calories?.breakfast ?: 0, record.breakfastKcal, record.breakfast
        )
        addMeal(
            labels.lunch, detail?.meals?.lunch.orEmpty(),
            detail?.calories?.lunch ?: 0, record.lunchKcal, record.lunch
        )
        addMeal(
            labels.dinner, detail?.meals?.dinner.orEmpty(),
            detail?.calories?.dinner ?: 0, record.dinnerKcal, record.dinner
        )
        addMeal(
            labels.snack, detail?.meals?.snack.orEmpty(),
            detail?.calories?.snack ?: 0, record.snackKcal, record.snack
        )

        // 기초대사는 끼니 다음, 운동 앞. 시안과 같은 순서다
        val bmr = if (record.bmr > 0) record.bmr else detail?.bmr ?: 0
        if (bmr > 0) lines += ReceiptLine(labels.bmr, bmr, isBurn = true)

        if (detail != null) {
            // 걸음 항목은 서버가 운동 목록에 넣어 준다. "운동 ·"을 붙이면 걸음이 운동처럼 보인다.
            // 시안처럼 기초대사 바로 뒤에 오도록 걸음 먼저, 그다음 실제 운동을 둔다
            val (steps, workouts) = detail.exercises.partition { STEPS.containsMatchIn(it.name) }
            (steps + workouts).forEach { item ->
                val name = item.name.trim()
                if (item.kcal <= 0 || name.isEmpty()) return@forEach
                lines += ReceiptLine(
                    title = if (STEPS.containsMatchIn(name)) name else exerciseTitle(labels, name, item.minutes),
                    kcal = item.kcal,
                    isBurn = true
                )
            }
        } else {
            val exerciseKcal = record.exerciseKcal
            val text = firstLine(record.exercise)
            if (exerciseKcal > 0) {
                lines += ReceiptLine(
                    title = if (text.isEmpty()) labels.exercise else "${labels.exercise} · $text",
                    kcal = exerciseKcal,
                    isBurn = true
                )
            }
        }

        val intake = lines.filterNot { it.isBurn }.sumOf { it.kcal }
        val net = if (record.netCalories != 0) record.netCalories else detail?.totals?.net ?: 0

        return DailyReceipt(
            dateText = receiptDateText(record.date, locale),
            serial = receiptSerial(record.date),
            lines = lines,
            intakeKcal = intake,
            netKcal = net,
            // 탄단지는 화면과 같은 기준(집계 컬럼)을 먼저 본다. 0g도 뜻이 있는 값이라
            // "0이면 구조에서"처럼 값으로 갈라내지 않고, 집계값이 있는 기록인지로 가른다
            carbGram = if (record.hasNutritionData) record.carbGram else detail?.macros?.carb ?: 0f,
            proteinGram = if (record.hasNutritionData) record.proteinGram else detail?.macros?.protein ?: 0f,
            fatGram = if (record.hasNutritionData) record.fatGram else detail?.macros?.fat ?: 0f,
            aiNote = detail?.evaluation?.trim().orEmpty()
        )
    }

    /** "운동 · 웨이트 트레이닝 50분". 걸음 항목에는 쓰지 않는다 */
    private fun exerciseTitle(labels: ReceiptLabels, name: String, minutes: Int): String {
        val duration = formatMinutes(minutes, labels.hourUnit, labels.minuteUnit)
        val head = "${labels.exercise} · $name"
        return if (duration.isEmpty()) head else "$head $duration"
    }

    /**
     * 끼니의 메뉴 이름을 한 줄로 잇는다. "그릭요거트, 그래놀라, 블루베리".
     * 구조가 없는 기록은 사용자가 적은 글의 첫 줄을 쓴다. 길이 제한은 그리는 쪽(Canvas)이 건다.
     */
    private fun menuText(items: List<AnalysisItem>, rawText: String): String {
        val names = items.map { it.name.trim() }.filter { it.isNotEmpty() }
        if (names.isNotEmpty()) return names.joinToString(", ")
        return firstLine(rawText)
    }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** "2026-11-17" → "2026. 11. 17. (화)". 기기 언어의 짧은 요일 이름을 쓴다 */
    internal fun receiptDateText(date: String, locale: Locale): String {
        val parsed = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return date.trim()
        val weekday = parsed.format(DateTimeFormatter.ofPattern("E", locale))
        return "${parsed.year}. ${parsed.monthValue}. ${parsed.dayOfMonth}. ($weekday)"
    }

    /** "2026-11-17" → "#20261117". 날짜가 깨졌으면 숫자만 남기고, 그것도 없으면 빈 문자열 */
    internal fun receiptSerial(date: String): String {
        val digits = date.filter { it.isDigit() }
        return if (digits.isEmpty()) "" else "#$digits"
    }
}
