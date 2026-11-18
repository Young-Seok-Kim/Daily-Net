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
import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.network.CalendarBody
import com.youngs.dailynet.data.network.CalendarPatch
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

        /**
         * 일정 종류. 값이 [KIND_EXERCISE] 같은 단어라서, 나중에 식단 등 다른 종류를 넣어도
         * 같은 날짜 키([PROP_DATE])를 쓰면서 종류로 가를 수 있다.
         * 예전(b60 이하)에는 운동 일정에만 "1"을 넣어 "앱이 만든 일정" 표시로 썼다([KIND_LEGACY]).
         */
        private const val PROP_KIND = "dailynet"
        const val KIND_EXERCISE = "exercise"
        const val KIND_PASS = "pass"
        /** b60 이하가 운동 일정에 넣던 값. 동기화 때 만나면 [KIND_EXERCISE]로 고쳐 쓴다 */
        private const val KIND_LEGACY = "1"
        private const val PROP_DATE = "dailynet_date"
        /** 운동 이용권에서 나온 일정. 값은 이용권 id. 운동 일정(PROP_DATE)과 섞이지 않게 따로 둔다 */
        private const val PROP_PASS = "dailynet_pass"
        /** 이용권 일정의 조각. span(기간 전체 띠) / start(시작일) / end(종료일) */
        private const val PROP_PASS_PART = "dailynet_pass_part"

        /** 이용권을 시작일·종료일 두 날짜에만 */
        const val PASS_MODE_EDGES = "edges"
        /** 이용권을 시작일~종료일 띠 하나로 */
        const val PASS_MODE_SPAN = "span"

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

    /** 이용권을 캘린더에 어떻게 넣을지. 기본은 시작일·종료일만 (띠는 한 달 내내 눈에 걸린다) */
    fun passMode(): String =
        prefs.getString(Constants.KEY_CALENDAR_PASS_MODE, PASS_MODE_EDGES) ?: PASS_MODE_EDGES

    fun setPassMode(mode: String) {
        prefs.edit().putString(Constants.KEY_CALENDAR_PASS_MODE, mode).apply()
    }

    fun setFixedTitle(title: String) {
        prefs.edit().putString(Constants.KEY_CALENDAR_FIXED_TITLE, title.trim()).apply()
    }

    /**
     * 로그아웃·탈퇴 때. 연동 설정은 비우되 캘린더 ID는 남긴다.
     *
     * ID는 계정 이메일별로 저장하므로([calendarIdKey]) 다른 계정이 로그인해도 섞이지 않는다.
     * 남겨 두는 이유: 탈퇴하면 Firestore의 ID 사본이 지워지고 다시 가입하면 uid도 바뀐다.
     * 이 범위(calendar.app.created)로는 캘린더 목록을 못 읽어 이름으로 되찾을 수도 없다.
     * 그래서 기기에 남은 ID가 유일한 단서다. 같은 계정으로 다시 가입하면 기존 "데일리넷"
     * 캘린더에 그대로 이어서 쓰고, 하나 더 만들지 않는다.
     */
    fun clear() {
        prefs.edit()
            .remove(Constants.KEY_CALENDAR_SYNC_ENABLED)
            .remove(Constants.KEY_CALENDAR_TITLE_MODE)
            .remove(Constants.KEY_CALENDAR_FIXED_TITLE)
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
        // 로그인이 안 돼 있으면 어느 계정의 ID인지도, Firestore 의 캘린더 ID도 모른다. 그 상태에서
        // 새로 만들면 같은 계정에 "데일리넷 운동"이 하나 더 생긴다(실제로 났다). 만들지 않고 멈춘다.
        if (auth.currentUser == null) throw IllegalStateException(context.getString(R.string.calendar_not_signed_in))

        storedCalendarId()?.let { id ->
            // 탈퇴 뒤 재가입처럼 기기에는 있는데 Firestore 에 없는 경우를 메운다. 다른 기기가 이 ID를
            // 찾을 수 있게. 한 프로세스에서 한 번이면 충분하다(매번 쓰면 쓰기 비용만 든다).
            if (!cloudBackfilled) {
                cloudBackfilled = true
                runCatching { cloudDoc()?.set(mapOf(CLOUD_FIELD to id), SetOptions.merge())?.await() }
                    .onFailure { Log.w(TAG, "cloud calendarId backfill failed", it) }
            }
            return@withLock id
        }

        // Firestore 읽기가 실패하면(오프라인·권한 오류) 모른다는 뜻이지 없다는 뜻이 아니다. 만들지 않는다.
        val cloudId = cloudDoc()!!.get().await().getString(CLOUD_FIELD)
        if (!cloudId.isNullOrBlank()) {
            Log.d(TAG, "calendarId from cloud")
            prefs.edit().putString(calendarIdKey()!!, cloudId).apply()
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

    /** 이 프로세스에서 기기의 캘린더 ID를 Firestore 에 한 번 맞춰 뒀는지 */
    private var cloudBackfilled = false

    /**
     * 기기에 캘린더 ID를 두는 자리. 계정 이메일별로 나눈다 (calendar_id_<email>).
     * 로그아웃·탈퇴 때 지우지 않아도 다른 계정과 섞이지 않게 하려는 것이다. 로그인 전엔 null.
     */
    private fun calendarIdKey(): String? =
        auth.currentUser?.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?.let { "${Constants.KEY_CALENDAR_ID}_$it" }

    /**
     * 기기에 저장된 이 계정의 캘린더 ID.
     * b61 전에는 계정 구분 없이 calendar_id 한 칸에 뒀고 로그아웃 때마다 지웠으므로,
     * 그 칸에 값이 남아 있으면 지금 로그인한 계정 것이다. 계정별 칸으로 옮기고 옛 칸은 비운다.
     */
    private fun storedCalendarId(): String? {
        val key = calendarIdKey() ?: return null
        prefs.getString(key, null)?.let { return it }
        val legacy = prefs.getString(Constants.KEY_CALENDAR_ID, null) ?: return null
        prefs.edit().putString(key, legacy).remove(Constants.KEY_CALENDAR_ID).apply()
        return legacy
    }

    private suspend fun saveCalendarId(id: String) {
        calendarIdKey()?.let { prefs.edit().putString(it, id).apply() }
        runCatching { cloudDoc()?.set(mapOf(CLOUD_FIELD to id), SetOptions.merge())?.await() }
            .onFailure { Log.w(TAG, "cloud calendarId write failed", it) }
    }

    /** 캘린더가 지워져 404가 났을 때. 기기와 Firestore 양쪽을 비워 다음에 새로 만든다. */
    private suspend fun forgetCalendarId() {
        prefs.edit()
            .apply { calendarIdKey()?.let { remove(it) } }
            .remove(Constants.KEY_CALENDAR_ID)
            .apply()
        runCatching { cloudDoc()?.delete()?.await() }
    }

    /**
     * 토큰·캘린더 ID를 준비하고, 캘린더가 아직 있는지 한 번 읽어 본 뒤 [block]을 돌린다.
     * 401이면 캐시된 토큰을 버리고 한 번 더, 404(캘린더가 지워짐)면 다시 만들고 한 번 더.
     *
     * 매번 한 번 더 읽는 비용이 들지만, 지워진 캘린더에 조용히 쓰는 것보다 낫다.
     * 그렇게 쓴 일정은 사용자 눈에 영영 안 보인다. 단, 지운 직후 몇 분은 구글이 아직 살아 있다고
     * 답하므로 그 사이에 쓴 일정은 잃는다. 더 정확한 calendarList.get은 이 범위로 401이 나서 못 쓴다.
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
                val calendar = api.getCalendar(bearer(currentToken), calendarId, "id,summary")
                // 앱의 캘린더 이름이 바뀌었으면(예: "데일리넷 운동" → "데일리넷") 기존 사용자 것도 이름만 따라오게 한다.
                // 캘린더는 ID 로 찾으므로 이름이 달라도 새로 만들지는 않는다.
                val wanted = context.getString(R.string.calendar_name)
                if (calendar.summary != null && calendar.summary != wanted) {
                    runCatching { api.patchCalendar(bearer(currentToken), calendarId, CalendarPatch(wanted)) }
                        .onFailure { Log.w(TAG, "calendar rename failed", it) }
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
                        Log.w(TAG, "calendar not found; recreating")
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
                // 날짜 키는 종류가 늘면 공유되므로 종류는 코드에서 가른다. 옛 "1" 표식도 운동으로 본다.
                val existing = api.listEvents(
                    bearer, calendarId, "$PROP_DATE=${record.date}", 5, null, "items(id,extendedProperties)"
                ).items.firstOrNull { isExercise(it) }
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

    // ── 운동 이용권 ───────────────────────────────────────────────

    /**
     * 이용권 한 장을 캘린더에 하루 종일 일정(기간 전체)으로 넣거나 고친다. 연동이 꺼져 있으면 아무것도 안 한다.
     * 운동 일정과 달리 키는 날짜가 아니라 이용권 id다. 실패해도 저장 흐름은 막지 않는다.
     */
    suspend fun mirrorPass(pass: ExercisePassEntity) {
        if (!isEnabled()) return
        val token = tokenOrNull() ?: run {
            Log.w(TAG, "no access token; skip mirror for pass ${pass.id}")
            return
        }
        try {
            withCalendar(token) { bearer, calendarId -> upsertPass(bearer, calendarId, pass) }
        } catch (e: Exception) {
            Log.w(TAG, "mirrorPass failed for ${pass.id}", e)
        }
    }

    /** 이용권을 지울 때 그 일정(조각 전부)도 지운다 */
    suspend fun removePass(passId: String) {
        if (!isEnabled()) return
        val token = tokenOrNull() ?: return
        try {
            withCalendar(token) { bearer, calendarId ->
                listPassEvents(bearer, calendarId, passId).forEach { api.deleteEvent(bearer, calendarId, it.id) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "removePass failed for $passId", e)
        }
    }

    /** "지금 동기화"와 표시 방식 변경에서 이용권 전부를 맞춘다. 없는 건 넣고 달라진 건 고치고 남는 조각은 지운다 */
    suspend fun syncAllPasses(token: String, passes: List<ExercisePassEntity>): Int {
        if (passes.isEmpty()) return 0
        return withCalendar(token) { bearer, calendarId ->
            var changed = 0
            passes.forEach { pass ->
                if (retryOnNetworkError { upsertPass(bearer, calendarId, pass) }) changed++
            }
            changed
        }
    }

    private suspend fun listPassEvents(bearer: String, calendarId: String, passId: String): List<EventResource> =
        api.listEvents(
            bearer, calendarId, "$PROP_PASS=$passId", 10, null, "items(id,summary,description,extendedProperties)"
        ).items

    /**
     * 이용권 하나의 일정 조각들을 지금 설정대로 맞춘다. 넣거나 고치거나 지운 게 있으면 true.
     *
     * 표시 방식을 바꾸면 조각 구성이 달라진다(띠 하나 ↔ 시작·종료 둘). 그래서 있는 조각을 전부 읽어
     * 원하는 구성에 없는 건 지우고, 있어야 할 건 넣거나 고친다. 예전 빌드가 조각 표식 없이 넣은 띠는 span으로 본다.
     */
    private suspend fun upsertPass(bearer: String, calendarId: String, pass: ExercisePassEntity): Boolean {
        if (pass.startDate.isBlank()) return false
        val desired = passEventBodies(pass)
        val existing = listPassEvents(bearer, calendarId, pass.id)
        val byPart = existing.groupBy { it.extendedProperties?.privateProps?.get(PROP_PASS_PART) ?: "span" }
        var changed = false

        // 원하지 않는 조각과 중복은 지운다
        byPart.forEach { (part, events) ->
            val keep = if (part in desired) events.firstOrNull() else null
            events.filter { it.id != keep?.id }.forEach {
                api.deleteEvent(bearer, calendarId, it.id)
                changed = true
            }
        }
        // 있어야 할 조각은 넣거나, 내용이 달라졌으면 고친다
        desired.forEach { (part, body) ->
            val event = byPart[part]?.firstOrNull()
            when {
                event == null -> { api.insertEvent(bearer, calendarId, body); changed = true }
                event.summary != body.summary || event.description.orEmpty() != body.description.orEmpty() -> {
                    api.patchEvent(bearer, calendarId, event.id, body); changed = true
                }
            }
        }
        return changed
    }

    /**
     * 이용권 일정 조각들. 설정에 따라
     * - span: 시작일~종료일 하루 종일 띠 하나 (종료일이 없으면 시작일 하루)
     * - edges: 시작일에 "○○ 시작", 종료일에 "○○ 종료" 두 개 (종료일이 없거나 같은 날이면 하나)
     * 구글은 종료일을 배타로 받아 하루를 더한다.
     */
    private fun passEventBodies(pass: ExercisePassEntity): Map<String, EventBody> {
        val title = if (pass.isCount) {
            context.getString(R.string.calendar_pass_title_count, pass.name, pass.totalCount)
        } else {
            context.getString(R.string.calendar_pass_title_period, pass.name)
        }
        val lastDay = pass.endDate.ifBlank { pass.startDate }
        val days = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(pass.startDate), LocalDate.parse(lastDay)).toInt() + 1
        }.getOrDefault(1)
        val description = listOfNotNull(
            context.getString(R.string.calendar_pass_desc_period, pass.startDate, lastDay, days),
            pass.keyword.takeIf { pass.isCount && it.isNotBlank() }
                ?.let { context.getString(R.string.calendar_pass_desc_keyword, it) }
        ).joinToString("\n")

        fun body(part: String, summary: String, from: String, to: String): EventBody {
            val endExclusive = runCatching { LocalDate.parse(to).plusDays(1).toString() }.getOrDefault(to)
            return EventBody(
                summary = summary,
                description = description,
                start = EventDate(from),
                end = EventDate(endExclusive),
                extendedProperties = ExtendedProperties(
                    mapOf(PROP_KIND to KIND_PASS, PROP_PASS to pass.id, PROP_PASS_PART to part)
                )
            )
        }

        return if (passMode() == PASS_MODE_SPAN || lastDay == pass.startDate) {
            mapOf("span" to body("span", title, pass.startDate, lastDay))
        } else {
            mapOf(
                "start" to body("start", context.getString(R.string.calendar_pass_start, title), pass.startDate, pass.startDate),
                "end" to body("end", context.getString(R.string.calendar_pass_end, title), lastDay, lastDay)
            )
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
            // 옛 빌드가 "1"로 넣은 일정도 함께 모은다. 안 그러면 같은 날짜에 하나 더 만든다.
            // 옛 표식 조회는 다 옮겨진 뒤엔 빈 결과라 요청 한 번만 더 드는 셈이다.
            val existing = HashMap<String, EventResource>()
            listOf(KIND_EXERCISE, KIND_LEGACY).forEach { kind ->
                var pageToken: String? = null
                do {
                    val page = api.listEvents(
                        bearer, calendarId, "$PROP_KIND=$kind", 2500, pageToken,
                        "items(id,summary,description,extendedProperties),nextPageToken"
                    )
                    page.items.forEach { event ->
                        event.extendedProperties?.privateProps?.get(PROP_DATE)?.let { existing[it] = event }
                    }
                    pageToken = page.nextPageToken
                } while (pageToken != null)
            }
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
                    event.summary != body.summary || event.description.orEmpty() != body.description.orEmpty()
                        // 옛 "1" 표식은 내용이 같아도 한 번 고쳐 써서 새 종류 값으로 옮긴다
                        || kindOf(event) != KIND_EXERCISE -> {
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
        // 2026-11-17 폰의 DNS(AdGuard)가 www.googleapis.com 조회를 몇 초씩 못 하는 일이 있었다.
        // 1.5·3·4.5초로는 못 넘겨서 2·4·8·12초, 네 번 더(총 26초) 기다린다. DNS가 풀리면 바로 이어진다.
        val waits = longArrayOf(2000L, 4000L, 8000L, 12000L)
        repeat(waits.size + 1) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "network error (attempt ${attempt + 1}): ${e.message}")
                if (attempt < waits.size) delay(waits[attempt])
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
                mapOf(PROP_KIND to KIND_EXERCISE, PROP_DATE to record.date)
            )
        )
    }

    private fun kindOf(event: EventResource) = event.extendedProperties?.privateProps?.get(PROP_KIND)

    /** 운동 일정인가. 옛 빌드의 "1" 표식도 운동으로 친다 (그때는 운동만 있었다) */
    private fun isExercise(event: EventResource) = kindOf(event).let { it == KIND_EXERCISE || it == KIND_LEGACY }

    private fun bearer(token: String) = "Bearer $token"
}
