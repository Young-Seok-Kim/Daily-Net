package com.youngs.dailynet.data.calendar

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.network.CalendarBody
import com.youngs.dailynet.data.network.EventBody
import com.youngs.dailynet.data.network.EventDate
import com.youngs.dailynet.data.network.EventResource
import com.youngs.dailynet.data.network.ExtendedProperties
import com.youngs.dailynet.data.network.GoogleCalendarApi
import com.youngs.dailynet.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.IOException
import retrofit2.HttpException
import java.time.LocalDate
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 운동 기록을 구글 캘린더에 넣는다.
 *
 * ## 왜 보조 캘린더인가
 * 사용자의 기본 캘린더에 쓰는 범위(`calendar.events`)는 민감한 범위라 구글 심사(시연 영상 제출)를
 * 거쳐야 하고, 심사 전에는 사용자 100명 한도와 "확인되지 않은 앱" 경고가 뜬다.
 * `calendar.app.created`는 앱이 **직접 만든 보조 캘린더만** 다루는 범위라 민감하지 않은 범위로
 * 분류되어 심사가 없다. 일정은 "데일리넷 운동" 캘린더에 들어가고, 구글 캘린더 앱에서는
 * 기본 캘린더와 같은 화면에 겹쳐 보인다.
 *
 * ## 토큰
 * 로그인(Credential Manager)은 ID 토큰만 준다. 캘린더 API에는 접근 토큰이 필요해서
 * [AuthorizationRequest]로 따로 승인받는다. 처음 한 번만 동의 화면이 뜨고([Access.NeedsConsent]),
 * 그다음부터는 [requestAccess]가 화면 없이 토큰을 돌려준다. 토큰은 Play 서비스가 캐시하므로
 * 앱이 저장하지 않는다.
 *
 * ## 같은 날을 다시 정산하면
 * 일정마다 숨은 속성 `dailynet_date=yyyy-MM-dd`를 붙여 두고 그걸로 찾아 고친다.
 * 운동을 지우고 정산하면 일정도 지운다.
 */
@Singleton
class GoogleCalendarSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GoogleCalendarApi,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        private const val TAG = "GoogleCalendarSync"
        const val SCOPE = "https://www.googleapis.com/auth/calendar.app.created"

        const val TITLE_MODE_CONTENT = "content"
        const val TITLE_MODE_FIXED = "fixed"

        private const val PROP_APP = "dailynet"
        private const val PROP_DATE = "dailynet_date"

        /** Firestore에 캘린더 ID를 두는 자리: users/{uid}/settings/calendar */
        private const val CLOUD_SUB = "settings"
        private const val CLOUD_DOC = "calendar"
        private const val CLOUD_FIELD = "calendarId"
    }

    sealed class Access {
        data class Granted(val token: String) : Access()
        /** 사용자가 동의 화면을 거쳐야 한다. [pendingIntent]를 IntentSender로 띄운다. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Access()
        data class Failed(val error: Throwable) : Access()
    }

    data class SyncResult(val added: Int, val updated: Int, val total: Int)

    /**
     * 캐시된 토큰이 죽어서(웹에서 권한을 지웠거나 만료) 다시 동의를 받아야 할 때.
     * 조용히 실패시키지 않고 화면이 [pendingIntent]로 동의 화면을 띄우게 한다.
     */
    class ConsentRequiredException(val pendingIntent: PendingIntent) : Exception("consent required")

    private val prefs
        get() = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 캘린더 ID를 찾거나 만드는 구간은 한 번에 하나만.
     * 토글을 연달아 누르면 연결이 여러 개 동시에 돌고, 각자 "ID가 없네" 하고 캘린더를 하나씩 만든다
     * (b49 태블릿에서 1.4초 간격 세 번 눌러 캘린더 3개가 생겼다).
     */
    private val calendarMutex = Mutex()

    // ── 설정 ──────────────────────────────────────────────────────

    fun isEnabled(): Boolean = prefs.getBoolean(Constants.KEY_CALENDAR_SYNC_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.KEY_CALENDAR_SYNC_ENABLED, enabled).apply()
    }

    fun titleMode(): String =
        prefs.getString(Constants.KEY_CALENDAR_TITLE_MODE, TITLE_MODE_CONTENT) ?: TITLE_MODE_CONTENT

    fun setTitleMode(mode: String) {
        prefs.edit().putString(Constants.KEY_CALENDAR_TITLE_MODE, mode).apply()
    }

    fun fixedTitle(): String =
        prefs.getString(Constants.KEY_CALENDAR_FIXED_TITLE, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.calendar_default_title)

    fun setFixedTitle(title: String) {
        prefs.edit().putString(Constants.KEY_CALENDAR_FIXED_TITLE, title.trim()).apply()
    }

    /** 로그아웃·탈퇴 때. 다른 계정의 캘린더 ID가 남으면 엉뚱한 곳에 쓴다. */
    fun clear() {
        prefs.edit()
            .remove(Constants.KEY_CALENDAR_SYNC_ENABLED)
            .remove(Constants.KEY_CALENDAR_TITLE_MODE)
            .remove(Constants.KEY_CALENDAR_FIXED_TITLE)
            .remove(Constants.KEY_CALENDAR_ID)
            .apply()
    }

    // ── 승인 ──────────────────────────────────────────────────────

    /**
     * 접근 토큰을 받는다. 이미 승인된 계정이면 화면 없이 바로 [Access.Granted].
     *
     * 계정은 **앱에 로그인한 구글 계정(Firebase 사용자 이메일)** 으로 못 박는다.
     * 비워 두면 Play 서비스가 기본 계정을 모를 때 계정 선택 화면을 띄우는데,
     * 이미 로그인한 사용자에게 계정을 또 고르게 할 이유가 없다.
     */
    suspend fun requestAccess(): Access {
        return try {
            val email = auth.currentUser?.email
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE)))
                .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
                .build()
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            val pending = result.pendingIntent
            val token = result.accessToken
            Log.d(TAG, "authorize: resolution=${result.hasResolution()} pending=${pending != null} token=${!token.isNullOrBlank()} scopes=${result.grantedScopes}")
            when {
                result.hasResolution() && pending != null -> Access.NeedsConsent(pending)
                !token.isNullOrBlank() -> Access.Granted(token)
                else -> Access.Failed(IllegalStateException("empty access token"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed", e)
            Access.Failed(e)
        }
    }

    /** 동의 화면이 닫힌 뒤 돌아온 Intent에서 토큰을 꺼낸다. */
    fun accessFromConsent(data: Intent?): Access {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token.isNullOrBlank()) Access.Failed(IllegalStateException("empty access token"))
            else Access.Granted(token)
        } catch (e: Exception) {
            Log.w(TAG, "consent result failed", e)
            Access.Failed(e)
        }
    }

    private suspend fun tokenOrNull(): String? = (requestAccess() as? Access.Granted)?.token

    private suspend fun clearToken(token: String) {
        runCatching {
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .await()
        }
    }

    // ── 캘린더 ────────────────────────────────────────────────────

    /**
     * 앱 전용 캘린더 ID. 기기(prefs) → Firestore → 새로 만들기 순으로 찾는다.
     *
     * `calendar.app.created` 범위로는 **캘린더 목록 조회(calendarList.list)가 403**이라
     * 이름으로 되찾을 수 없다. 그래서 만든 ID를 Firestore(users/{uid}/settings/calendar)에도
     * 적어 두고, 재설치나 기기 교체 뒤에는 거기서 가져온다. 안 그러면 켤 때마다 캘린더가 하나씩 늘어난다.
     * 켜자마자 불러서 사용자가 구글 캘린더 앱에서 바로 확인할 수 있게 한다.
     */
    suspend fun ensureCalendar(token: String): String = calendarMutex.withLock {
        prefs.getString(Constants.KEY_CALENDAR_ID, null)?.let { return@withLock it }

        val cloudId = runCatching { cloudDoc()?.get()?.await()?.getString(CLOUD_FIELD) }
            .onFailure { Log.w(TAG, "cloud calendarId read failed", it) }
            .getOrNull()
        if (!cloudId.isNullOrBlank()) {
            Log.d(TAG, "calendarId from cloud")
            prefs.edit().putString(Constants.KEY_CALENDAR_ID, cloudId).apply()
            return@withLock cloudId
        }

        val id = api.createCalendar(
            bearer(token),
            CalendarBody(
                summary = context.getString(R.string.calendar_name),
                timeZone = TimeZone.getDefault().id
            )
        ).id
        Log.d(TAG, "calendar created")
        saveCalendarId(id)
        id
    }

    private fun cloudDoc() = auth.currentUser?.uid?.let { uid ->
        firestore.collection("users").document(uid).collection(CLOUD_SUB).document(CLOUD_DOC)
    }

    private suspend fun saveCalendarId(id: String) {
        prefs.edit().putString(Constants.KEY_CALENDAR_ID, id).apply()
        runCatching { cloudDoc()?.set(mapOf(CLOUD_FIELD to id), SetOptions.merge())?.await() }
            .onFailure { Log.w(TAG, "cloud calendarId write failed", it) }
    }

    /** 캘린더가 지워져 404가 났을 때. 기기와 Firestore 양쪽을 비워 다음에 새로 만든다. */
    private suspend fun forgetCalendarId() {
        prefs.edit().remove(Constants.KEY_CALENDAR_ID).apply()
        runCatching { cloudDoc()?.delete()?.await() }
    }

    /**
     * 토큰·캘린더 ID를 준비하고, 캘린더가 사용자 목록에 아직 있는지 확인한 뒤 [block]을 돌린다.
     * 401이면 캐시된 토큰을 버리고 한 번 더, 캘린더가 지워졌으면(404 또는 deleted) 다시 만들고 한 번 더.
     *
     * 매번 목록 항목을 한 번 더 읽는 비용이 들지만, 지워진 캘린더에 조용히 쓰는 것보다 낫다.
     * 그렇게 쓴 일정은 사용자 눈에 영영 안 보인다.
     */
    private suspend fun <T> withCalendar(
        token: String,
        block: suspend (bearer: String, calendarId: String) -> T
    ): T {
        var currentToken = token
        var retriedAuth = false
        var retriedCalendar = false
        while (true) {
            val calendarId = ensureCalendar(currentToken)
            try {
                val entry = api.getCalendarListEntry(bearer(currentToken), calendarId, "id,deleted")
                if (entry.deleted == true) {
                    if (retriedCalendar) throw IllegalStateException("calendar deleted")
                    Log.w(TAG, "calendar was deleted by user; recreating")
                    retriedCalendar = true
                    forgetCalendarId()
                    continue
                }
                return block(bearer(currentToken), calendarId)
            } catch (e: HttpException) {
                when {
                    e.code() == 401 && !retriedAuth -> {
                        retriedAuth = true
                        clearToken(currentToken)
                        currentToken = when (val access = requestAccess()) {
                            is Access.Granted -> access.token
                            is Access.NeedsConsent -> throw ConsentRequiredException(access.pendingIntent)
                            is Access.Failed -> throw e
                        }
                    }
                    e.code() == 404 && !retriedCalendar -> {
                        retriedCalendar = true
                        forgetCalendarId()
                    }
                    else -> throw e
                }
            }
        }
    }

    /**
     * 토글을 켤 때. 캘린더를 준비하고 사용자 목록에 실제로 있는지 확인한다([withCalendar]가 한다).
     * 기억해 둔 ID만 믿고 "연결됨"이라고 하면 토큰이 죽었거나 캘린더가 지워져도 켜진 것처럼 보인다.
     */
    suspend fun verifyCalendar(token: String): String =
        withCalendar(token) { _, calendarId -> calendarId }

    // ── 일정 ──────────────────────────────────────────────────────

    /**
     * 정산 한 건을 캘린더에 반영한다. 설정이 꺼져 있거나 승인이 안 돼 있으면 조용히 넘어간다.
     * 분석 흐름 뒤에서 부르므로 여기서 예외를 밖으로 던지지 않는다.
     */
    suspend fun mirror(record: DailyRecordModel) {
        if (!isEnabled()) return
        val token = tokenOrNull() ?: run {
            Log.w(TAG, "no access token; skip mirror for ${record.date}")
            return
        }
        try {
            withCalendar(token) { bearer, calendarId ->
                val existing = api.listEvents(
                    bearer, calendarId, "$PROP_DATE=${record.date}", 5, null, "items(id)"
                ).items.firstOrNull()
                if (record.exercise.isBlank()) {
                    existing?.let { api.deleteEvent(bearer, calendarId, it.id) }
                } else {
                    val body = eventBody(record)
                    if (existing == null) api.insertEvent(bearer, calendarId, body)
                    else api.patchEvent(bearer, calendarId, existing.id, body)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mirror failed for ${record.date}", e)
        }
    }

    /**
     * 지금까지 기록한 운동을 캘린더와 맞춘다.
     * 없는 날짜는 넣고, 이미 있는 일정은 **지금 설정(제목 방식·열량)대로 다시 만든 내용과 다를 때만** 고친다.
     * 그래서 제목 방식을 바꾼 뒤 동기화하면 과거 일정의 제목·메모도 그 방식으로 바뀐다.
     */
    suspend fun syncAll(token: String, records: List<DailyRecordModel>): SyncResult {
        val targets = records.filter { it.exercise.isNotBlank() }
        return withCalendar(token) { bearer, calendarId ->
            val existing = HashMap<String, EventResource>()
            var pageToken: String? = null
            do {
                val page = api.listEvents(
                    bearer, calendarId, "$PROP_APP=1", 2500, pageToken,
                    "items(id,summary,description,extendedProperties),nextPageToken"
                )
                page.items.forEach { event ->
                    event.extendedProperties?.privateProps?.get(PROP_DATE)?.let { existing[it] = event }
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            Log.d(TAG, "syncAll: local=${targets.size} remote=${existing.size}")

            var added = 0
            var updated = 0
            targets.forEach { record ->
                val body = eventBody(record)
                val event = existing[record.date]
                when {
                    event == null -> {
                        retryOnNetworkError { api.insertEvent(bearer, calendarId, body) }
                        added++
                    }
                    event.summary != body.summary || event.description.orEmpty() != body.description.orEmpty() -> {
                        retryOnNetworkError { api.patchEvent(bearer, calendarId, event.id, body) }
                        updated++
                    }
                }
            }
            Log.d(TAG, "syncAll: added=$added updated=$updated")
            SyncResult(added = added, updated = updated, total = targets.size)
        }
    }

    /**
     * 수십 건을 연달아 보내는 동안 회선이 잠깐 끊기면(Wi-Fi↔LTE 전환 등) DNS 오류로 전체가 실패한다.
     * 네트워크 오류(IOException)에 한해 잠깐 쉬고 두 번까지 더 시도한다. HTTP 오류는 재시도하지 않는다.
     */
    private suspend fun <T> retryOnNetworkError(block: suspend () -> T): T {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "network error (attempt ${attempt + 1}): ${e.message}")
                delay(1500L * (attempt + 1))
            }
        }
        throw lastError!!
    }

    private fun eventBody(record: DailyRecordModel): EventBody {
        val content = record.exercise.trim()
        val kcalLine = record.exerciseKcal.takeIf { it > 0 }
            ?.let { context.getString(R.string.calendar_event_kcal, it) }
        val (summary, description) = if (titleMode() == TITLE_MODE_FIXED) {
            fixedTitle() to listOfNotNull(content, kcalLine).joinToString("\n")
        } else {
            content to kcalLine
        }
        val nextDay = LocalDate.parse(record.date).plusDays(1).toString()
        return EventBody(
            summary = summary,
            description = description,
            start = EventDate(record.date),
            end = EventDate(nextDay),
            extendedProperties = ExtendedProperties(
                mapOf(PROP_APP to "1", PROP_DATE to record.date)
            )
        )
    }

    private fun bearer(token: String) = "Bearer $token"
}
