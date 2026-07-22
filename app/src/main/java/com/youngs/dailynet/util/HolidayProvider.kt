package com.youngs.dailynet.util

import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 기기 캘린더(구글 캘린더의 "대한민국의 휴일" 등 휴일 캘린더)에서 공휴일 날짜를 읽어온다.
 * READ_CALENDAR 권한이 필요하며, 휴일 캘린더가 없거나 권한이 없으면 빈 집합을 반환한다.
 */
class HolidayProvider(private val context: Context) {

    /** [startMillis, endMillis] 기간의 공휴일 날짜(yyyy-MM-dd) 집합 */
    fun getHolidays(startMillis: Long, endMillis: Long): Set<String> {
        val result = mutableSetOf<String>()
        try {
            // 1) 휴일 캘린더 ID 수집 (이름에 휴일/공휴일/Holiday 포함)
            val holidayCalIds = mutableListOf<Long>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    if (name.contains("휴일") || name.contains("공휴일") ||
                        name.contains("Holiday", ignoreCase = true)
                    ) {
                        holidayCalIds.add(id)
                    }
                }
            }
            if (holidayCalIds.isEmpty()) return emptySet()

            // 2) 해당 캘린더의 이벤트(Instances)에서 날짜 수집
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startMillis.toString())
                .appendPath(endMillis.toString())
                .build()
            val selection =
                "${CalendarContract.Instances.CALENDAR_ID} IN (${holidayCalIds.joinToString(",")})"
            // 종일 이벤트는 UTC 자정 기준이므로 UTC로 포맷해야 날짜가 밀리지 않음
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.BEGIN),
                selection, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    result.add(fmt.format(Date(c.getLong(0))))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
