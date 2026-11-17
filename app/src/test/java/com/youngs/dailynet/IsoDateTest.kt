package com.youngs.dailynet

import com.youngs.dailynet.util.isoDateToUtcMillis
import com.youngs.dailynet.util.todayUtcMillis
import com.youngs.dailynet.util.utcMillisToIsoDate
import com.youngs.dailynet.util.utcMillisYear
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * DatePicker의 UTC millis와 앱 날짜 문자열("yyyy-MM-dd") 사이 변환.
 *
 * 여기가 틀리면 생년월일이 하루 밀린 채로 저장된다 (1997-01-24를 골랐는데 1997-01-23).
 * Compose DatePicker는 "그 날의 UTC 자정"을 주고받으므로, 기기 시간대가 무엇이든
 * 같은 날짜로 돌아와야 한다.
 */
class IsoDateTest {

    private lateinit var original: TimeZone

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
        // 기기가 UTC+9여도 결과가 같은지 보려고 서울로 바꾼다
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun dateToMillisIsUtcMidnight() {
        assertEquals(0L, isoDateToUtcMillis("1970-01-01"))
        // 2026-11-17 00:00 UTC
        assertEquals(1_794_873_600_000L, isoDateToUtcMillis("2026-11-17"))
    }

    @Test
    fun millisToDateKeepsTheSameDay() {
        // 1997-01-24 00:00 UTC
        assertEquals("1997-01-24", utcMillisToIsoDate(854_064_000_000L))
    }

    @Test
    fun roundTripSurvivesTheDeviceTimeZone() {
        // 서울에서 UTC 자정은 그 날 09시다. 시간대로 읽었으면 하루가 밀렸을 값들
        val dates = listOf(
            "1997-01-24",   // 생일 예시
            "2000-02-29",   // 윤년
            "2026-01-01",   // 연 경계
            "2026-12-31",   // 연 끝
            "2026-11-01"    // 월 경계 (서머타임 없는 달이라도)
        )
        dates.forEach { date ->
            val millis = isoDateToUtcMillis(date)
            assertEquals(date, millis?.let { utcMillisToIsoDate(it) })
        }
    }

    @Test
    fun unreadableInputIsNull() {
        // 비었거나 못 읽는 값은 "오늘부터 시작"으로 처리하므로 null이어야 한다
        assertNull(isoDateToUtcMillis(""))
        assertNull(isoDateToUtcMillis("   "))
        assertNull(isoDateToUtcMillis("2026"))
        assertNull(isoDateToUtcMillis("2026-13-01"))
        assertNull(isoDateToUtcMillis("1997/01/24"))
    }

    @Test
    fun yearComesFromTheUtcDay() {
        assertEquals(1997, utcMillisYear(isoDateToUtcMillis("1997-01-24")!!))
        assertEquals(2026, utcMillisYear(isoDateToUtcMillis("2026-12-31")!!))
    }

    @Test
    fun todayIsTheDeviceDay() {
        // 날짜를 안 고른 칸은 오늘에서 시작한다. 기기 날짜와 같은 날이어야 한다
        val today = LocalDate.now()
        assertEquals(today.toString(), utcMillisToIsoDate(todayUtcMillis()))
        assertEquals(today.year, utcMillisYear(todayUtcMillis()))
    }
}
