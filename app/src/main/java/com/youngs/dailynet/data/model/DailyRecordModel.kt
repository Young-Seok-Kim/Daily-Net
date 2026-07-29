package com.youngs.dailynet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_records")
data class DailyRecordModel(
    @PrimaryKey
    val date: String = "",           // yyyy-MM-dd
    val title: String = "",

    // 식사 및 입력 항목
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snack: String = "",

    // 💡 Firestore 필드명(exercise)과 일치시킴
    val exercise: String = "",
    val remark: String = "",
    val steps: Int = 0,              // 걸음수 (오늘이면 자동 기입, 과거면 저장값)
    val stepsManual: Boolean = false, // 사용자가 직접 입력했는지 (true면 자동 갱신으로 덮어쓰지 않음)
    val isMale: Boolean = true,

    val netCalories: Int = 0,
    val hasExercise: Boolean = false,
    val weight: Float = 0f,
    val tags: List<String> = listOf(),
    val note: String = "",           // 제미나이 피드백
    val details: List<Map<String, Any>> = listOf(),

    val finalized: Boolean = false,
    val analyzing: Boolean = false,

    val analysisResult: String = "",

    // ── 분석 결과 집계값 (b24~) ──────────────────────────────────────
    // 예전에는 analysisResult 텍스트 안에만 있어서 차트도 기간별 집계도 불가능했다.
    // 이제 서버가 구조화해 내려주는 값을 컬럼으로 남겨 데이터가 쌓이게 한다.
    //
    // b24 이전에 분석한 기록에는 이 값들이 없어 전부 0이다.
    // 화면에서는 [hasNutritionData]로 구분해서 "데이터 없음"으로 다뤄야 한다.
    val breakfastKcal: Int = 0,
    val lunchKcal: Int = 0,
    val dinnerKcal: Int = 0,
    val snackKcal: Int = 0,
    /** 운동으로 소모한 칼로리 (양수) */
    val exerciseKcal: Int = 0,

    /** 하루 총 탄수화물(g) */
    val carbGram: Float = 0f,
    val proteinGram: Float = 0f,
    val fatGram: Float = 0f,

    /** 기초대사량 */
    val bmr: Int = 0
) {
    /**
     * 영양 집계값이 있는 기록인지.
     *
     * b24 이전에 분석된 기록은 값이 전부 0이라 차트에 그리면 바닥에 붙은 가짜 점이 된다.
     * 기초대사량은 분석이 성공하면 항상 0보다 크므로 이걸로 판별한다.
     */
    val hasNutritionData: Boolean
        get() = bmr > 0
}