package com.youngs.dailynet.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
}