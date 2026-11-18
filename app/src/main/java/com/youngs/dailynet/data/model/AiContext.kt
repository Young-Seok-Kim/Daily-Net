package com.youngs.dailynet.data.model

import com.youngs.dailynet.data.local.entity.UserProfileEntity

/**
 * 설정 > AI 분석 참고 정보.
 *
 * 하루 분석(analyzeDiet)과 주·월·연 평가(evaluatePeriod)의 프롬프트에 함께 실려
 * "이 사람은 무엇을 목표로 하고, 평소 얼마나 움직이며, AI가 알아야 할 사정이 무엇인지"를 알린다.
 * 한 번 정해 두면 매번 입력하지 않아도 되므로 **모든 리포트에 항상 붙는다.**
 *
 * 값은 코드 문자열로 저장한다(sedentary, lose …). 화면에 보이는 말은 언어에 따라 달라지므로
 * 저장·전송에는 쓰지 않는다 (라벨은 [com.youngs.dailynet.ui.view.AiGoal.labelRes] 참고).
 */
enum class AiGoal(val code: String) {
    LOSE("lose"),
    MAINTAIN("maintain"),
    GAIN("gain");

    companion object {
        /** 서버·Firestore에서 읽은 코드. 모르는 값이면 null(미선택) */
        fun fromCode(code: String?): AiGoal? = entries.firstOrNull { it.code == code }
    }
}

/** 평소 활동량. 목표와 같은 규칙으로 코드로 저장한다 */
enum class AiActivity(val code: String) {
    SEDENTARY("sedentary"),
    ACTIVE("active"),
    LABOR("labor");

    companion object {
        fun fromCode(code: String?): AiActivity? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 저장·전송에 쓰는 한 묶음.
 *
 * 셋 다 비어 있으면 [isEmpty]가 true이고, 그때는 요청에 싣지 않는다(null).
 * 서버는 받은 값이 하나도 없으면 프롬프트에 아무 줄도 넣지 않는다.
 */
data class AiContext(
    val goal: AiGoal? = null,
    val activity: AiActivity? = null,
    val note: String = ""
) {
    val isEmpty: Boolean get() = goal == null && activity == null && note.isBlank()

    /**
     * Firestore users/{uid} 에 실을 필드.
     *
     * 고르지 않은 값도 **빈 문자열로 써서** 지운다. 필드를 빼고 쓰면 예전 값이 그대로 남아
     * 다른 기기에서 다시 켜질 때 지운 목표가 되살아난다.
     */
    fun toProfileFields(): Map<String, Any> = mapOf(
        FIELD_GOAL to (goal?.code ?: ""),
        FIELD_ACTIVITY to (activity?.code ?: ""),
        FIELD_NOTE to normalizeNote(note)
    )

    companion object {
        /** 메모 길이 상한. 화면의 카운터(N / 200)와 서버의 자르는 길이가 같아야 한다 */
        const val NOTE_MAX = 200

        const val FIELD_GOAL = "aiGoal"
        const val FIELD_ACTIVITY = "aiActivity"
        const val FIELD_NOTE = "aiNote"

        /**
         * 입력 중인 글을 상한에 맞춰 자른다. **입력 칸이 이걸 쓴다** — 200자를 넘겨 적으면
         * 커서가 튀지 않고 그 자리에서 더 이상 들어가지 않는다.
         * 앞뒤 공백은 아직 지우지 않는다(적는 중에 공백을 칠 수 없다).
         */
        fun limitNote(raw: String): String = if (raw.length <= NOTE_MAX) raw else raw.take(NOTE_MAX)

        /**
         * 저장·전송 전 정리. 앞뒤 공백만 지우고 200자로 자른다.
         *
         * 줄바꿈은 남긴다 — 사용자가 줄로 나눠 적은 그대로 보여야 하고, 서버가 프롬프트에
         * 넣을 때 한 줄로 눌러 놓기 때문이다(functions/aiContext.js).
         */
        fun normalizeNote(raw: String): String = limitNote(raw.trim())

        /** Firestore 필드·프로필에서 읽은 코드들. 모르는 코드는 미선택으로 떨어진다 */
        fun of(goalCode: String?, activityCode: String?, note: String?): AiContext = AiContext(
            goal = AiGoal.fromCode(goalCode),
            activity = AiActivity.fromCode(activityCode),
            note = normalizeNote(note.orEmpty())
        )

        /** 프로필에 저장된 값. 셋 다 비어 있으면 null — 요청에 싣지 않는다 */
        fun of(profile: UserProfileEntity?): AiContext? {
            if (profile == null) return null
            return of(profile.aiGoal, profile.aiActivity, profile.aiNote).takeIf { !it.isEmpty }
        }
    }
}
