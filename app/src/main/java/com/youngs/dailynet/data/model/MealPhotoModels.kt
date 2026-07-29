package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/** 음식 사진에서 메뉴를 읽어달라는 요청 */
data class MealPhotoRequest(
    /** Base64로 인코딩한 JPEG */
    val image: String,
    val mimeType: String = "image/jpeg",
    /** 메뉴명을 어떤 언어로 받을지 (BCP-47) */
    val language: String
)

data class MealPhotoResponse(
    /** 입력창에 그대로 넣을 수 있는 형태. 예: "돌솥 제육볶음, 밑반찬" */
    @SerializedName("text")
    val text: String = "",

    /** 메뉴별 상세. 지금은 쓰지 않지만 나중에 칼로리 미리보기 등에 쓸 수 있다. */
    @SerializedName("items")
    val items: List<AnalysisItem> = emptyList()
)
