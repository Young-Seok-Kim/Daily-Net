package com.youngs.dailynet.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * 앱의 날짜 문자열("yyyy-MM-dd")과 Compose DatePicker의 millis 사이를 옮긴다.
 *
 * DatePicker의 selectedDateMillis는 "그 날의 UTC 자정"이다. 기기 시간대로 읽으면
 * 하루가 밀릴 수 있어서(예: UTC+9에서 UTC 자정은 그 전날 09시) 항상 UTC로 다룬다.
 * 이 변환이 틀리면 생년월일이 하루 앞당겨지는 식으로 조용히 어긋난다. 테스트: IsoDateTest
 */

private val IsoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/** "yyyy-MM-dd" → 그 날의 UTC 자정 millis. 비었거나 못 읽으면 null */
fun isoDateToUtcMillis(date: String): Long? =
    runCatching { LocalDate.parse(date.trim(), IsoDate) }
        .getOrNull()
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?.toEpochMilli()

/** UTC millis → "yyyy-MM-dd". DatePicker에서 고른 날을 앱 형식으로 되돌린다 */
fun utcMillisToIsoDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(IsoDate)

/** UTC millis가 가리키는 해. DatePicker 연도 범위 검사에 쓴다 */
fun utcMillisYear(millis: Long): Int =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).year

/** 기기 기준 오늘의 UTC 자정 millis. 날짜를 안 고른 칸은 오늘부터 고르게 둔다 */
fun todayUtcMillis(): Long =
    LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
