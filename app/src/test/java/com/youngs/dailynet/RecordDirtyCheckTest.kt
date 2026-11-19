package com.youngs.dailynet

import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.util.RecordDirtyCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정산 화면 "수정됨" 판정.
 *
 * AI 질문 버튼은 이 값이 true면 시트 대신 "먼저 분석/저장" 안내를 띄운다. 너무 자주 true가 되면
 * (걸음수 자동 갱신 등) 오늘 화면에서 질문을 아예 못 하게 되고, 너무 드물면 고친 글과
 * 예전 리포트가 어긋난 채 AI에 넘어간다.
 */
class RecordDirtyCheckTest {

    private val saved = DailyRecordModel(
        date = "2026-11-19",
        breakfast = "현미밥",
        lunch = "샐러드",
        dinner = "김치찌개",
        snack = "",
        exercise = "러닝 30분",
        remark = "",
        weight = 72.3f,
        steps = 8000,
        stepsManual = false,
        netCalories = -300,
        analysisResult = "리포트",
        finalized = true
    )

    @Test
    fun `기준점이 없으면 변경 없음`() {
        assertFalse(RecordDirtyCheck.isDirty(null, saved))
    }

    @Test
    fun `불러온 그대로면 변경 없음`() {
        assertFalse(RecordDirtyCheck.isDirty(saved, saved))
    }

    @Test
    fun `식단·운동·메모·체중을 고치면 변경`() {
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(breakfast = "현미밥, 계란")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(lunch = "")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(dinner = "삼겹살")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(snack = "아메리카노")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(exercise = "")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(remark = "야근")))
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(weight = 72.0f)))
    }

    @Test
    fun `걸음수는 직접 입력했을 때만 변경으로 친다`() {
        // Health Connect가 오늘 값을 올려 준 것은 사용자의 수정이 아니다
        assertFalse(RecordDirtyCheck.isDirty(saved, saved.copy(steps = 9500, stepsManual = false)))
        // 손으로 고친 값은 변경
        assertTrue(RecordDirtyCheck.isDirty(saved, saved.copy(steps = 9500, stepsManual = true)))
        // 손으로 쳤지만 결국 같은 값이면 변경 없음
        assertFalse(RecordDirtyCheck.isDirty(saved, saved.copy(stepsManual = true)))
    }

    @Test
    fun `분석 결과 쪽 값만 달라진 것은 변경이 아니다`() {
        // 분석이 끝나면 기준점도 함께 바뀌므로, 결과 컬럼은 비교 대상이 아니다
        assertFalse(
            RecordDirtyCheck.isDirty(
                saved,
                saved.copy(netCalories = 0, analysisResult = "", finalized = false, analyzing = true)
            )
        )
    }

    @Test
    fun `다른 날짜가 올라와 있으면 비교하지 않는다`() {
        assertFalse(RecordDirtyCheck.isDirty(saved, saved.copy(date = "2026-11-18", breakfast = "다름")))
    }

    @Test
    fun `분석을 마친 기록을 새 기준점으로 삼으면 다시 변경 없음`() {
        val edited = saved.copy(dinner = "삼겹살")
        assertTrue(RecordDirtyCheck.isDirty(saved, edited))
        val analyzed = edited.copy(netCalories = 200, analysisResult = "새 리포트")
        assertFalse(RecordDirtyCheck.isDirty(analyzed, analyzed))
    }
}
