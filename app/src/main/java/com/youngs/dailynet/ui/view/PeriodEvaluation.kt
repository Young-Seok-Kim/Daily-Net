package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AiContext
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.data.model.NamedCount
import com.youngs.dailynet.data.model.PeriodEvalDay
import com.youngs.dailynet.data.model.PeriodEvalPeriod
import com.youngs.dailynet.data.model.PeriodEvalProfile
import com.youngs.dailynet.data.model.PeriodEvalRequest
import com.youngs.dailynet.data.model.PeriodEvaluation
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 정산을 묶는 단위. 목록의 구분선 세 층과 같다.
 *
 * [type]은 서버(evaluatePeriod)와 약속한 값이고, 저장 키([periodKeyOf])의 앞부분이기도 하다.
 */
enum class PeriodKind(val type: String) {
    WEEK("week"), MONTH("month"), YEAR("year")
}

/** Firestore 문서 ID. "week-202646", "month-2026-11", "year-2026" */
fun periodKeyOf(kind: PeriodKind, label: String): String = "${kind.type}-$label"

/** 식단·운동 텍스트를 요청에 실을 때 자르는 길이. 서버도 같은 길이로 다시 자른다 */
private const val DAY_TEXT_LIMIT = 80

/** "자주 먹은 음식" 목록 길이 */
private const val TOP_ITEMS = 12

/**
 * 그 기간의 시작·끝 날짜(yyyy-MM-dd).
 *
 * 주는 기록이 속한 월요일부터 일요일까지 — 목록의 [getWeekIdentifier]와 같은 규칙(월요일 시작)이다.
 * 월·연은 라벨("2026-11", "2026")에서 바로 나온다.
 * 주는 라벨(연도×100+주차)만으로는 날짜를 되짚기 번거로워 기록의 날짜 하나를 쓴다.
 */
fun periodRangeOf(kind: PeriodKind, label: String, records: List<DailyRecordModel>): Pair<String, String> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return when (kind) {
        PeriodKind.WEEK -> {
            val anyDate = records.firstOrNull()?.date ?: return "" to ""
            val parsed = runCatching { fmt.parse(anyDate) }.getOrNull() ?: return "" to ""
            val cal = Calendar.getInstance(Locale.US).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                time = parsed
            }
            // 월요일까지 되감는다. (일요일은 월요일 시작 기준으로 그 주의 마지막 날이다)
            val back = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            cal.add(Calendar.DAY_OF_MONTH, -back)
            val start = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_MONTH, 6)
            start to fmt.format(cal.time)
        }
        PeriodKind.MONTH -> {
            if (label.length < 7) return "" to ""
            val year = label.substring(0, 4).toIntOrNull() ?: return "" to ""
            val month = label.substring(5, 7).toIntOrNull() ?: return "" to ""
            val cal = Calendar.getInstance(Locale.US).apply { clear(); set(year, month - 1, 1) }
            val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            "$label-01" to String.format(Locale.US, "%s-%02d", label, last)
        }
        PeriodKind.YEAR -> "$label-01-01" to "$label-12-31"
    }
}

/**
 * 기록 묶음의 지문. 평가 뒤 기록이 바뀌었는지 보는 용도라 완벽할 필요는 없고,
 * 정산을 추가·수정·삭제했을 때 값이 달라지기만 하면 된다.
 */
fun recordsFingerprint(records: List<DailyRecordModel>): String {
    val net = records.sumOf { it.netCalories }
    val exercise = records.count { it.hasExercise }
    val weight = records.sumOf { (it.weight * 10).roundToInt() }
    val dates = records.sumOf { it.date.hashCode().toLong() }
    return "${records.size}:$net:$exercise:$weight:$dates"
}

/** 연 구분선 제목. "2026년" / "2026" */
fun getYearHeaderText(dateString: String): String {
    return try {
        val locale = Locale.getDefault()
        val date = SimpleDateFormat("yyyy-MM-dd", locale).parse(dateString) ?: return ""
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "y")
        SimpleDateFormat(pattern, locale).format(date)
    } catch (e: Exception) {
        dateString.take(4)
    }
}

private fun String.clip(limit: Int): String {
    val t = trim().replace(Regex("\\s+"), " ")
    return if (t.length > limit) t.take(limit) else t
}

/** 분석 결과의 항목 이름을 세어 많은 순으로. 이름은 공백 정리 뒤 소문자로 묶는다 */
private fun topNames(names: List<String>): List<NamedCount> {
    val counts = HashMap<String, Int>()
    val display = HashMap<String, String>()
    for (raw in names) {
        val shown = raw.clip(40)
        if (shown.isEmpty()) continue
        val key = shown.lowercase(Locale.ROOT)
        counts[key] = (counts[key] ?: 0) + 1
        display.putIfAbsent(key, shown)
    }
    return counts.entries
        .sortedByDescending { it.value }
        .take(TOP_ITEMS)
        .map { NamedCount(display.getValue(it.key), it.value) }
}

/**
 * 서버에 보낼 요청. 숫자는 집계 컬럼 그대로, 글은 앞부분만.
 *
 * @param fallbackWeight 기간 안에 체중 기록이 없을 때 쓸 값 (가장 최근 체중, 없으면 시작 체중)
 */
fun buildPeriodEvalRequest(
    kind: PeriodKind,
    label: String,
    title: String,
    records: List<DailyRecordModel>,
    profile: UserProfileEntity,
    inbody: InbodyData?,
    today: String,
    fallbackWeight: Float
): PeriodEvalRequest {
    val sorted = records.sortedBy { it.date }
    val (start, end) = periodRangeOf(kind, label, sorted)

    val days = sorted.map { r ->
        PeriodEvalDay(
            date = r.date,
            net = r.netCalories,
            breakfastKcal = r.breakfastKcal,
            lunchKcal = r.lunchKcal,
            dinnerKcal = r.dinnerKcal,
            snackKcal = r.snackKcal,
            exerciseKcal = r.exerciseKcal,
            carb = r.carbGram,
            protein = r.proteinGram,
            fat = r.fatGram,
            bmr = r.bmr,
            weight = r.weight,
            steps = r.steps,
            hasExercise = r.hasExercise,
            // 연은 서버가 일별 줄을 쓰지 않는다. 365일치 글을 보내 봐야 버려지니 비워 보낸다
            breakfast = if (kind == PeriodKind.YEAR) "" else r.breakfast.clip(DAY_TEXT_LIMIT),
            lunch = if (kind == PeriodKind.YEAR) "" else r.lunch.clip(DAY_TEXT_LIMIT),
            dinner = if (kind == PeriodKind.YEAR) "" else r.dinner.clip(DAY_TEXT_LIMIT),
            snack = if (kind == PeriodKind.YEAR) "" else r.snack.clip(DAY_TEXT_LIMIT),
            exercise = if (kind == PeriodKind.YEAR) "" else r.exercise.clip(DAY_TEXT_LIMIT)
        )
    }

    val foods = sorted.flatMap { r ->
        val m = r.analysisDetail?.meals ?: return@flatMap emptyList<String>()
        (m.breakfast + m.lunch + m.dinner + m.snack).map { it.name }
    }
    val exercises = sorted.flatMap { r -> r.analysisDetail?.exercises?.map { it.name }.orEmpty() }

    val weight = sorted.lastOrNull { it.weight > 0f }?.weight
        ?: fallbackWeight.takeIf { it > 0f }
        ?: profile.initialWeight

    return PeriodEvalRequest(
        period = PeriodEvalPeriod(type = kind.type, label = label, title = title, start = start, end = end),
        today = today,
        profile = PeriodEvalProfile(
            height = profile.height,
            weight = weight,
            isMale = profile.isMale,
            birthDate = profile.birthDate
        ),
        inbody = inbody?.takeIf { it.hasData },
        days = days,
        topFoods = topNames(foods),
        topExercises = topNames(exercises),
        language = Locale.getDefault().toLanguageTag(),
        appVersion = BuildConfig.VERSION_CODE,
        // 설정에서 정해 둔 목표·활동량·메모. 서버가 점수의 무게와 평가의 초점을 여기에 맞춘다
        aiContext = AiContext.of(profile)
    )
}

/**
 * 목록의 주·월·연 요약 패널에 붙는 **한 줄짜리** AI 평가. 누르면 [onOpen]으로 상세 화면에 간다.
 *
 * 평가 전이면 "AI 평가 받기 ›", 평가 뒤면 점수 pill과 한 줄 제목만 보인다. 평가 전문(총평·식단·운동·할 일)은
 * 열 줄이 넘어 목록 패널 안에 두면 기록이 한참 밀린다. 그래서 받는 것도 보는 것도 상세 화면 몫이고,
 * 여기는 "있다/없다/몇 점"만 알린다. 기록이 바뀐 뒤면 제목 대신 바뀌었다는 안내를 붉게 보인다.
 */
@Composable
fun PeriodEvaluationRow(
    mainViewModel: MainViewModel,
    kind: PeriodKind,
    label: String,
    records: List<DailyRecordModel>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val key = remember(kind, label) { periodKeyOf(kind, label) }
    LaunchedEffect(key) { mainViewModel.loadPeriodEvaluation(key) }

    val evaluations by mainViewModel.periodEvaluations.collectAsState()
    val evaluation = evaluations[key]
    val fingerprint = remember(records) { recordsFingerprint(records) }
    val outdated = evaluation != null && evaluation.fingerprint.isNotEmpty() && evaluation.fingerprint != fingerprint

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DnDimens.RadiusTile))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.period_eval_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (evaluation == null) {
            Text(
                text = stringResource(R.string.period_eval_button),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        } else {
            ScoreChip(evaluation.score)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (outdated) stringResource(R.string.period_eval_outdated) else evaluation.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (outdated) DnTheme.net.coral else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 상세 화면(월 정산·주/연 정산)에 붙는 AI 평가 카드 전문.
 *
 * 처음 보이면 저장된 평가를 한 번 읽어 오고(없으면 버튼만), 버튼을 누르면 서버에 평가를 받아
 * 저장한다. 그 뒤 기록이 바뀌면 "기록이 바뀌었습니다"와 함께 다시 받을 수 있게 둔다.
 * 자동으로 다시 받지 않는다 — 정산 한 번에 주·월·연 평가가 세 번 나가면 비용이 그만큼이다.
 *
 * @param label 저장 키의 뒷부분. 주 202646 / 월 2026-11 / 연 2026
 * @param title 화면 제목 그대로. 프롬프트에 "이 기간"의 이름으로 들어간다
 */
@Composable
fun PeriodEvaluationSection(
    mainViewModel: MainViewModel,
    kind: PeriodKind,
    label: String,
    title: String,
    records: List<DailyRecordModel>,
    modifier: Modifier = Modifier
) {
    val key = remember(kind, label) { periodKeyOf(kind, label) }
    LaunchedEffect(key) { mainViewModel.loadPeriodEvaluation(key) }

    val evaluations by mainViewModel.periodEvaluations.collectAsState()
    val evaluating by mainViewModel.evaluatingPeriods.collectAsState()
    val evaluation = evaluations[key]
    val fingerprint = remember(records) { recordsFingerprint(records) }
    val outdated = evaluation != null && evaluation.fingerprint.isNotEmpty() && evaluation.fingerprint != fingerprint

    PeriodEvaluationCard(
        evaluation = evaluation,
        loading = key in evaluating,
        outdated = outdated,
        canEvaluate = records.isNotEmpty(),
        onEvaluate = { mainViewModel.evaluatePeriod(kind, label, title, records) },
        modifier = modifier
    )
}

@Composable
private fun PeriodEvaluationCard(
    evaluation: PeriodEvaluation?,
    loading: Boolean,
    outdated: Boolean,
    canEvaluate: Boolean,
    onEvaluate: () -> Unit,
    modifier: Modifier = Modifier
) {
    DnCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.period_eval_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (evaluation != null) {
                ScoreChip(evaluation.score)
            }
        }

        when {
            loading -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.period_eval_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            evaluation == null -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.period_eval_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(onClick = onEvaluate, enabled = canEvaluate) {
                    Text(stringResource(R.string.period_eval_button))
                }
            }

            else -> {
                Spacer(modifier = Modifier.height(10.dp))
                if (evaluation.headline.isNotBlank()) {
                    Text(
                        text = evaluation.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (evaluation.summary.isNotBlank()) {
                    QuoteBlock(text = evaluation.summary)
                }

                EvalParagraph(
                    label = stringResource(R.string.period_eval_diet),
                    text = evaluation.diet,
                    color = DnTheme.net.carb
                )
                EvalParagraph(
                    label = stringResource(R.string.period_eval_exercise),
                    text = evaluation.exercise,
                    color = DnTheme.net.protein
                )

                if (evaluation.actions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.period_eval_actions),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        evaluation.actions.forEachIndexed { i, action ->
                            Row {
                                Text(
                                    text = "${i + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = action,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (evaluation.evaluatedAt.isNotBlank()) {
                            Text(
                                text = stringResource(
                                    R.string.period_eval_footer,
                                    evaluation.evaluatedAt,
                                    evaluation.recordDays
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (outdated) {
                            Text(
                                text = stringResource(R.string.period_eval_outdated),
                                style = MaterialTheme.typography.labelSmall,
                                color = DnTheme.net.coral
                            )
                        }
                    }
                    TextButton(onClick = onEvaluate, enabled = canEvaluate) {
                        Text(stringResource(R.string.period_eval_again))
                    }
                }
            }
        }
    }
}

/** "식단" / "운동" 소제목 + 글 한 단락. 글이 비면 통째로 뺀다 */
@Composable
private fun EvalParagraph(label: String, text: String, color: Color) {
    if (text.isBlank()) return
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * 점수 pill. 80 이상은 적자색(잘함), 60 미만은 흑자색(경고), 사이는 중립.
 * 앱 전체에서 초록=좋음, 붉음=나쁨 규칙과 같다.
 */
@Composable
private fun ScoreChip(score: Int) {
    val net = DnTheme.net
    val (container, content) = when {
        score >= 80 -> net.deficit.copy(alpha = 0.15f) to net.deficit
        score < 60 -> net.surplus.copy(alpha = 0.15f) to net.surplus
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }
    DnChip(
        text = stringResource(R.string.period_eval_score, score),
        containerColor = container,
        contentColor = content
    )
}

/** 패널 안쪽에서 카드 위에 두는 간격. 요약 숫자와 카드가 붙어 보이지 않게 */
val PeriodEvalCardSpacing = DnDimens.SpaceMd
