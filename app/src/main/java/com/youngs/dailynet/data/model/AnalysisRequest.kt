package com.youngs.dailynet.data.model

data class AnalysisRequest(
    val weight: Float,
    val height: Float,
    val isMale: Boolean, // 추가: 남성이면 true, 여성이면 false
    // 서버(functions/index.js)가 나이와 기초대사량을 계산할 때 쓴다.
    // 이걸 안 보내면 서버가 기본값 29세로 계산해 BMR이 실제와 어긋난다.
    val birthDate: String,
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val snack: String,
    val exercise: String,
    val remark: String,
    val steps: Int = 0,
    // 서버가 요청을 보낸 앱 버전을 알 수 있게 함께 보낸다.
    // 지금 서버는 쓰지 않지만, 나중에 응답 형식을 바꿀 때 구버전에 맞춰 분기하려면 필요하다.
    val appVersion: Int
)
