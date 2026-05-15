package com.youngs.dailynet.util

import android.content.Context

class PreferenceManager(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    fun saveProfile(height: Float, weight: Float, isMale: Boolean, birthDate: String) {
        prefs.edit().apply {
            putFloat("height", height)
            putFloat("weight", weight)
            putBoolean("is_male", isMale)
            putString("birth_date", birthDate)
            apply()
        }
    }

    // 데이터 불러오기 예시
    fun getHeight() = prefs.getFloat("height", 0f)
    fun getBirthDate() = prefs.getString("birth_date", "") ?: ""
}