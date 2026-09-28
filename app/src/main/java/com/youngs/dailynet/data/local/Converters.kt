package com.youngs.dailynet.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.youngs.dailynet.data.model.AnalysisDetail

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType)
    }

    @TypeConverter
    fun fromMapList(value: List<Map<String, Any>>?): String? = gson.toJson(value)

    @TypeConverter
    fun toMapList(value: String?): List<Map<String, Any>>? {
        val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
        return gson.fromJson(value, listType)
    }

    // 분석 결과 구조는 항목 목록이 여러 개 중첩되어 있어 컬럼으로 펼 수 없다. JSON 한 칸에 넣는다.
    // 읽을 때 깨진 값이 있으면 null로 떨어뜨린다. 그러면 화면은 텍스트 리포트로 물러난다.
    @TypeConverter
    fun fromAnalysisDetail(value: AnalysisDetail?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun toAnalysisDetail(value: String?): AnalysisDetail? =
        value?.let { runCatching { gson.fromJson(it, AnalysisDetail::class.java) }.getOrNull() }
}