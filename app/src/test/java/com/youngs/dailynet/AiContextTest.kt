package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AiActivity
import com.youngs.dailynet.data.model.AiContext
import com.youngs.dailynet.data.model.AiGoal
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.view.PeriodKind
import com.youngs.dailynet.ui.view.buildPeriodEvalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 설정 > AI 분석 참고 정보(목표·활동량·메모).
 *
 * 확인할 것은 세 가지다.
 *  - 저장·복원: 코드로 저장하고, 모르는 코드는 미선택으로 떨어진다
 *  - 길이: 메모는 200자에서 잘리고 앞뒤 공백은 저장 전에 지워진다
 *  - 전송: 하루 분석·기간 평가 요청에 프로필 값이 그대로 실리고, 비어 있으면 실리지 않는다
 */
class AiContextTest {

    private fun profile(goal: String = "", activity: String = "", note: String = "") = UserProfileEntity(
        height = 175f,
        initialWeight = 85f,
        isMale = true,
        birthDate = "1997-01-24",
        aiGoal = goal,
        aiActivity = activity,
        aiNote = note
    )

    // ── 코드 ↔ 화면 값 ─────────────────────────────────────────

    @Test
    fun codesRoundTrip() {
        AiGoal.entries.forEach { assertEquals(it, AiGoal.fromCode(it.code)) }
        AiActivity.entries.forEach { assertEquals(it, AiActivity.fromCode(it.code)) }
    }

    @Test
    fun unknownCodesBecomeUnselected() {
        assertNull(AiGoal.fromCode("muscle"))
        assertNull(AiGoal.fromCode(""))
        assertNull(AiGoal.fromCode(null))
        // 서버·Firestore에서 이상한 값이 와도 화면이 죽지 않아야 한다
        assertNull(AiActivity.fromCode("toString"))
    }

    // ── 길이와 정리 ────────────────────────────────────────────

    @Test
    fun noteIsCappedAtTwoHundred() {
        val exactly = "ㄱ".repeat(AiContext.NOTE_MAX)
        assertEquals(AiContext.NOTE_MAX, AiContext.limitNote(exactly).length)
        assertEquals(exactly, AiContext.limitNote(exactly))

        val over = "ㄴ".repeat(AiContext.NOTE_MAX + 50)
        assertEquals(AiContext.NOTE_MAX, AiContext.limitNote(over).length)
        assertEquals(AiContext.NOTE_MAX, AiContext.normalizeNote(over).length)
    }

    @Test
    fun noteIsTrimmedButLineBreaksStay() {
        assertEquals("유당불내증", AiContext.normalizeNote("  유당불내증  "))
        assertEquals("첫 줄\n둘째 줄", AiContext.normalizeNote(" 첫 줄\n둘째 줄 "))
        // 입력 중에는 공백을 지우지 않는다 (적는 도중 커서가 튀면 안 된다)
        assertEquals(" 가", AiContext.limitNote(" 가"))
    }

    // ── 저장・복원 ────────────────────────────────────────────

    @Test
    fun savedFieldsCarryCodesAndClearedValues() {
        val fields = AiContext(AiGoal.GAIN, AiActivity.LABOR, "  PT 주 3회 ").toProfileFields()
        assertEquals("gain", fields[AiContext.FIELD_GOAL])
        assertEquals("labor", fields[AiContext.FIELD_ACTIVITY])
        assertEquals("PT 주 3회", fields[AiContext.FIELD_NOTE])

        // 지운 값도 빈 문자열로 써야 다른 기기에서 예전 값이 되살아나지 않는다
        val cleared = AiContext().toProfileFields()
        assertEquals("", cleared[AiContext.FIELD_GOAL])
        assertEquals("", cleared[AiContext.FIELD_ACTIVITY])
        assertEquals("", cleared[AiContext.FIELD_NOTE])
    }

    @Test
    fun profileContextIsNullWhenNothingIsSet() {
        assertNull(AiContext.of(profile()))
        assertNull(AiContext.of(null as UserProfileEntity?))
        // 공백만 적은 메모도 미설정으로 본다 - 요청에 빈 문자열을 실을 이유가 없다
        assertNull(AiContext.of(profile(note = "   ")))

        assertNotNull(AiContext.of(profile(note = "채식")))
        assertEquals(AiGoal.LOSE, AiContext.of(profile(goal = "lose"))!!.goal)
    }

    // ── 요청 매핑 ────────────────────────────────────────────

    private val record = DailyRecordModel(date = "2026-11-10", weight = 84f)

    private fun requestOf(p: UserProfileEntity) = buildPeriodEvalRequest(
        kind = PeriodKind.MONTH,
        label = "2026-11",
        title = "2026년 11월",
        records = listOf(record),
        profile = p,
        inbody = null,
        today = "2026-11-18",
        fallbackWeight = 84f
    )

    @Test
    fun periodEvalRequestCarriesAiContext() {
        val request = requestOf(profile(goal = "maintain", activity = "active", note = "  단백질 위주 "))
        val ctx = request.aiContext
        assertEquals(AiGoal.MAINTAIN, ctx?.goal)
        assertEquals(AiActivity.ACTIVE, ctx?.activity)
        assertEquals("단백질 위주", ctx?.note)
    }

    @Test
    fun periodEvalRequestOmitsEmptyAiContext() {
        // null이면 Gson이 필드를 빼고 보낸다 — 구버전 서버와도 그대로 통한다
        val request = requestOf(profile())
        assertNull(request.aiContext)
    }
}
