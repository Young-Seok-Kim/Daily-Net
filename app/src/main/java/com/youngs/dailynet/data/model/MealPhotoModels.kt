package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 음식 사진에서 메뉴를 읽어달라는 요청.
 *
 * 갤러리에서 여러 장을 고르면 [images]에 전부 담고, [image]에는 첫 장을 넣는다.
 * 서버는 images가 있으면 그것을 쓰고 없으면 image를 본다. image를 비워 두지 않는 이유는
 * 새 앱이 아직 배포되지 않은 서버와 만나도 첫 장만으로는 동작하게 하기 위해서다.
 */
data class MealPhotoRequest(
    /** Base64로 인코딩한 JPEG. 여러 장이면 첫 장 */
    val image: String,
    /** 같은 끼니를 찍은 사진 전부 (Base64 JPEG). 한 장이면 그 한 장 */
    val images: List<String>,
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
