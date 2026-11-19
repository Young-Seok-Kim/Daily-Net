package com.youngs.dailynet.util

import com.youngs.dailynet.data.model.DailyRecordModel

/**
 * 정산 화면의 입력값이 마지막으로 불러오거나 분석·저장한 기록과 달라졌는지.
 *
 * AI 질문 버튼은 화면의 기록을 요약해 넘긴다. 글은 고쳤는데 아직 분석을 안 돌렸으면
 * 새 글과 예전 칼로리·리포트가 한 묶음으로 넘어가 어긋난 답이 나온다. 그래서 버튼을 누르기 전에
 * 이 값으로 걸러 "먼저 분석/저장"을 안내한다.
 *
 * 비교 대상은 사용자가 손으로 고치는 칸뿐이다. 분석 결과(칼로리·리포트)는 기준점과 함께 바뀌므로
 * 보지 않는다. 걸음수는 Health Connect가 오늘 값을 수시로 갱신하므로, 직접 입력한 경우에만
 * 변경으로 친다 — 자동 갱신까지 세면 오늘 화면은 늘 "수정됨"이 된다.
 */
object RecordDirtyCheck {

    /**
     * @param baseline 마지막으로 불러온(또는 분석·저장 완료된) 기록. null이면 아직 아무것도
     *   불러오지 않은 것이라 비교할 수 없어 변경 없음으로 본다.
     */
    fun isDirty(baseline: DailyRecordModel?, current: DailyRecordModel): Boolean {
        if (baseline == null) return false
        // 다른 날짜의 기록이 올라와 있으면 비교 자체가 의미 없다 (로드 중 잠깐 스치는 상태)
        if (baseline.date != current.date) return false
        return baseline.breakfast != current.breakfast ||
            baseline.lunch != current.lunch ||
            baseline.dinner != current.dinner ||
            baseline.snack != current.snack ||
            baseline.exercise != current.exercise ||
            baseline.remark != current.remark ||
            baseline.weight != current.weight ||
            (current.stepsManual && baseline.steps != current.steps)
    }
}
