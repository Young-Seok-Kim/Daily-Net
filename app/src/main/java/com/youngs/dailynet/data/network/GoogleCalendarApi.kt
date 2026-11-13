package com.youngs.dailynet.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 구글 캘린더 REST API(v3) 중 운동 일정 등록에 필요한 만큼만.
 *
 * 공식 자바 클라이언트(google-api-client)는 크고 의존성이 많아 쓰지 않는다.
 * 이미 들어 있는 Retrofit으로 여섯 개 호출만 직접 적는다.
 * 캘린더 목록 조회(calendarList.list)는 이 범위로는 403이라 넣지 않았다. 캘린더 ID는
 * [com.youngs.dailynet.data.calendar.GoogleCalendarSync]가 기기와 Firestore에 기억한다.
 *
 * 접근 토큰은 AuthorizationClient가 준 것을 `Authorization: Bearer …` 헤더로 넘긴다.
 *
 * 범위는 `calendar.app.created` 하나다. 이 범위로는 **앱이 만든 보조 캘린더만** 다룰 수 있어서
 * 사용자의 기본 캘린더나 다른 일정에는 손댈 수 없다.
 */
interface GoogleCalendarApi {

    /**
     * 캘린더가 아직 있는지(그리고 토큰이 살아 있는지) 확인하는 용도. 지워졌으면 404.
     *
     * 사용자가 웹에서 지운 직후 몇 분은 이 호출도, 일정 추가도 정상 응답한다(삭제 전파가 느리다).
     * 더 정확한 `users/me/calendarList/{id}`(calendarList.get)는 문서에는 이 범위로 된다고 돼 있지만
     * 실제로는 401 Invalid Credentials가 난다(b50 폰·태블릿 모두 확인). 그래서 이걸로 간다.
     */
    @GET("calendars/{calendarId}")
    suspend fun getCalendar(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Query("fields") fields: String
    ): CalendarResource

    /** 캘린더 이름(summary)만 고친다. 앱의 캘린더 이름을 바꿨을 때 기존 사용자 것도 따라오게 */
    @PATCH("calendars/{calendarId}")
    suspend fun patchCalendar(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Body body: CalendarPatch
    ): CalendarResource

    @POST("calendars")
    suspend fun createCalendar(
        @Header("Authorization") auth: String,
        @Body body: CalendarBody
    ): CalendarResource

    @GET("calendars/{calendarId}/events")
    suspend fun listEvents(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Query("privateExtendedProperty") privateExtendedProperty: String,
        @Query("maxResults") maxResults: Int,
        @Query("pageToken") pageToken: String?,
        @Query("fields") fields: String
    ): EventListResponse

    @POST("calendars/{calendarId}/events")
    suspend fun insertEvent(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Body body: EventBody
    ): EventResource

    @PATCH("calendars/{calendarId}/events/{eventId}")
    suspend fun patchEvent(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
        @Body body: EventBody
    ): EventResource

    @DELETE("calendars/{calendarId}/events/{eventId}")
    suspend fun deleteEvent(
        @Header("Authorization") auth: String,
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String
    ): Response<Unit>
}

data class CalendarResource(val id: String = "", val summary: String? = null)

data class CalendarBody(val summary: String, val timeZone: String)

data class CalendarPatch(val summary: String)

data class EventListResponse(
    val items: List<EventResource> = emptyList(),
    val nextPageToken: String? = null
)

data class EventResource(
    val id: String = "",
    val summary: String? = null,
    val description: String? = null,
    val extendedProperties: ExtendedProperties? = null
)

/**
 * 하루 종일 일정 하나. 운동 기록에는 시각이 없어서 시간 일정은 만들 수 없다.
 * `start.date` / `end.date`만 채우고(`end`는 다음 날), `dateTime`은 쓰지 않는다.
 */
data class EventBody(
    val summary: String,
    val description: String?,
    val start: EventDate,
    val end: EventDate,
    val extendedProperties: ExtendedProperties,
    /** 하루 종일 일정이 바쁨 시간으로 잡히지 않도록 */
    val transparency: String = "transparent",
    /** 캘린더 기본 알림(전날 알림 등)이 운동 기록마다 울리지 않도록 끈다 */
    val reminders: Reminders = Reminders()
)

data class EventDate(val date: String)

data class Reminders(val useDefault: Boolean = false)

/**
 * 앱만 보는 숨은 속성. 어느 날짜 기록에서 나온 일정인지 여기에 적어 두고,
 * 나중에 같은 날을 다시 정산하면 새로 만들지 않고 이 값으로 찾아서 고친다.
 */
data class ExtendedProperties(
    @SerializedName("private") val privateProps: Map<String, String> = emptyMap()
)
