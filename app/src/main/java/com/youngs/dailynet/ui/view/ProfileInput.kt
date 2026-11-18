package com.youngs.dailynet.ui.view

/*
 * 신체 정보 입력 칸의 규칙. 화면([ProfileSetupDialog]·[ProfileEditDialog])과 유닛 테스트가
 * 같은 함수를 보게 따로 뺐다 — 화면 안에 조건을 적어 두면 테스트가 그 조건을 베껴 쓰게 되고,
 * 그러면 둘이 갈라져도 아무도 모른다.
 */

/**
 * 생년월일은 [DnDatePickerDialog]로만 넣으므로 "고른 값이 있는지"만 본다
 * (빈 문자열은 null이 아니라 ""로 온다). 키·체중은 소수점을 허용하고 0보다 커야 한다.
 */
fun isProfileInputValid(heightText: String, weightText: String, birthDate: String): Boolean {
    val h = heightText.toFloatOrNull() ?: 0f
    val w = weightText.toFloatOrNull() ?: 0f
    return h > 0f && w > 0f && birthDate.contains("-")
}
