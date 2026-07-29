package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.DailyRecordModel
import kotlin.math.roundToInt

/**
 * 한 주치 기록을 요약한 값.
 *
 * 두 부분으로 나뉜다.
 * - 순칼로리·기록 일수: [DailyRecordModel.netCalories]는 예전부터 저장돼 있어 **지난 주도 나온다**
 * - 탄단지: b24부터 저장하기 시작해서 그 이후 분석한 날만 집계된다
 *
 * 그래서 [nutritionDays]를 함께 들고 다닌다. 7일 중 3일만 분석한 주의 평균을
 * 그냥 "하루 평균"이라고 보여주면 사용자가 한 주 전체로 오해하기 때문이다.
 */
data class WeekSummary(
    val recordDays: Int,
    val exerciseDays: Int,
    val netTotal: Int,
    val netAverage: Int,
    /** 영양 데이터가 있는 날 수. 0이면 탄단지 구간을 아예 보여주지 않는다. */
    val nutritionDays: Int,
    val carbAvg: Float,
    val proteinAvg: Float,
    val fatAvg: Float,
    val recCarb: Int,
    val recProtein: Int,
    val recFat: Int
)

/**
 * 권장 탄단지는 저장하지 않고 기초대사량과 체중에서 계산한다.
 *
 * ⚠️ 서버 `functions/nutrition.js`가 이 계산의 **기준**이다.
 * 한쪽만 고치면 AI 리포트에 적힌 권장량과 이 화면의 권장량이 어긋난다. 반드시 함께 고칠 것.
 *
 * 단백질은 칼로리 비율이 아니라 **체중 기준**이다. 예전에는 권장 칼로리의 40%를 단백질로
 * 배분해서 체중과 무관하게 2000kcal면 무조건 200g이 나왔다(70kg 기준 2.9g/kg).
 * 단백질과 지방을 먼저 정하고 남은 칼로리를 탄수화물로 채워야 합이 권장 칼로리와 맞는다.
 */
private const val ACTIVITY_FACTOR = 1.375
private const val DEFICIT_KCAL = 500
private const val PROTEIN_PER_KG = 1.6f
private const val FAT_CALORIE_RATIO = 0.25

private fun recommendedMacros(bmr: Int, weightKg: Float): Triple<Int, Int, Int> {
    val calories = (bmr * ACTIVITY_FACTOR).roundToInt() - DEFICIT_KCAL

    val protein = (weightKg * PROTEIN_PER_KG).roundToInt()
    val fat = (calories * FAT_CALORIE_RATIO / 9).roundToInt()
    val carb = ((calories - protein * 4 - fat * 9) / 4).coerceAtLeast(0)

    return Triple(carb, protein, fat)
}

/**
 * 그 주에 속한 기록들로 요약을 만든다.
 *
 * @param fallbackWeightKg 그 주 기록에 체중이 하나도 없을 때 쓸 값 (보통 가장 최근 체중)
 */
fun buildWeekSummary(records: List<DailyRecordModel>, fallbackWeightKg: Float): WeekSummary {
    val withNutrition = records.filter { it.hasNutritionData }
    val avgBmr = if (withNutrition.isEmpty()) 0
    else withNutrition.sumOf { it.bmr } / withNutrition.size

    // 단백질 기준이 되는 체중은 그 주에 실제로 기록된 값을 우선한다.
    // 몇 달 전 주를 볼 때 지금 체중으로 계산하면 그때 기준과 어긋난다.
    val weightKg = records.firstOrNull { it.weight > 0f }?.weight ?: fallbackWeightKg
    val (recCarb, recProtein, recFat) = recommendedMacros(avgBmr, weightKg)

    val netTotal = records.sumOf { it.netCalories }

    return WeekSummary(
        recordDays = records.size,
        exerciseDays = records.count { it.hasExercise },
        netTotal = netTotal,
        netAverage = if (records.isEmpty()) 0 else netTotal / records.size,
        nutritionDays = withNutrition.size,
        carbAvg = withNutrition.averageOf { it.carbGram },
        proteinAvg = withNutrition.averageOf { it.proteinGram },
        fatAvg = withNutrition.averageOf { it.fatGram },
        recCarb = recCarb,
        recProtein = recProtein,
        recFat = recFat
    )
}

private fun List<DailyRecordModel>.averageOf(pick: (DailyRecordModel) -> Float): Float =
    if (isEmpty()) 0f else map(pick).sum() / size

/** 주차 구분선을 눌렀을 때 아래로 펼쳐지는 요약 패널 */
@Composable
fun WeekSummaryPanel(summary: WeekSummary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(
                R.string.week_summary_days,
                summary.recordDays,
                summary.exerciseDays
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.week_summary_net_avg, summary.netAverage),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // 적자(음수)가 초록 — 앱 전체 규칙과 동일
                color = if (summary.netAverage <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }

        // 영양 데이터가 한 날도 없으면 이 구간은 통째로 숨긴다.
        // "0g / 0g" 같은 빈 막대를 보여주는 것보다 없는 편이 낫다.
        if (summary.nutritionDays > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.week_summary_macro_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = " " + stringResource(
                        R.string.week_summary_macro_basis,
                        summary.nutritionDays
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            MacroBar(stringResource(R.string.macro_carb), summary.carbAvg, summary.recCarb)
            MacroBar(stringResource(R.string.macro_protein), summary.proteinAvg, summary.recProtein)
            MacroBar(stringResource(R.string.macro_fat), summary.fatAvg, summary.recFat)
        }
    }
}

/** "탄 88g / 200g" + 권장 대비 막대 한 줄 */
@Composable
private fun MacroBar(label: String, value: Float, recommended: Int) {
    // 권장을 넘으면 막대가 꽉 찬 상태로 멈춘다. 넘친 정도는 숫자로 읽으면 된다.
    val ratio = if (recommended <= 0) 0f else (value / recommended).coerceIn(0f, 1f)
    val over = recommended > 0 && value > recommended

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = "${value.roundToInt()}g / ${recommended}g",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(6.dp)
                    .background(
                        if (over) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                    )
            )
        }
    }
}
