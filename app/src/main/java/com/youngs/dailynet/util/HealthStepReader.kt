package com.youngs.dailynet.util

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Health Connect를 통해 "오늘(자정~지금) 총 걸음 수"를 읽는다.
 * 삼성헬스 등 걸음 데이터 제공자가 Health Connect에 기록한 값을 사용하므로,
 * 앱을 방금 설치했더라도(설치 전 걸음 포함) 오늘 걸은 총량을 가져올 수 있다.
 */
class HealthStepReader(private val context: Context) {

    // 읽기 권한: 걸음 수
    val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    /**
     * 30일보다 오래된 데이터를 읽기 위한 추가 권한.
     *
     * 이 권한이 없으면 Health Connect는 "앱에 최초로 권한을 준 시점 기준 30일 이전" 데이터를
     * 돌려주지 않는다. 지난 날짜 걸음수를 채우려면 필요하다.
     */
    val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** 이 기기에서 Health Connect를 사용할 수 있는지 */
    fun sdkAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun clientOrNull(): HealthConnectClient? =
        if (sdkAvailable()) HealthConnectClient.getOrCreate(context) else null

    /** READ_STEPS 권한이 이미 부여됐는지 */
    suspend fun hasPermission(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 이 기기의 Health Connect 버전이 과거 데이터 읽기를 지원하는지.
     * 지원하지 않는 기기에 권한을 요청하면 안 되므로 반드시 먼저 확인한다.
     */
    fun historySupported(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    /** 과거 데이터 읽기 권한이 이미 부여됐는지 */
    suspend fun hasHistoryPermission(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            client.permissionController.getGrantedPermissions().contains(historyPermission)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 권한 요청에 사용할 집합.
     * 기기가 지원하면 과거 데이터 읽기 권한도 함께 요청해 팝업을 한 번으로 끝낸다.
     */
    fun permissionsToRequest(): Set<String> =
        if (historySupported()) permissions + historyPermission else permissions

    /**
     * 기본 조회 창(최근 30일)을 벗어난 날짜인지 → historyPermission이 있어야 읽을 수 있다.
     *
     * 정확한 기준은 "앱에 최초로 권한을 준 시점 기준 30일 전"이라 앱에서 알 수 없으므로
     * 오늘 기준 30일로 근사한다. (경계 근처에서 권한을 한 번 더 물어보는 정도의 오차)
     */
    fun isBeyondDefaultWindow(date: String): Boolean = try {
        LocalDate.parse(date).isBefore(LocalDate.now(ZoneId.systemDefault()).minusDays(30))
    } catch (e: Exception) {
        false
    }

    /**
     * 특정 날짜(yyyy-MM-dd)의 총 걸음 수. 읽지 못하면 null.
     *
     * 오늘이면 자정~현재, 과거면 그날 자정~다음날 자정 구간을 집계한다.
     * 미래 날짜는 조회하지 않는다.
     *
     * 주의: Health Connect는 별도 권한(READ_HEALTH_DATA_HISTORY) 없이는
     * 최근 30일치만 읽을 수 있다. 그보다 오래된 날짜는 null 또는 0이 나올 수 있다.
     */
    suspend fun getStepsForDate(date: String): Long? {
        val client = clientOrNull() ?: return null
        return try {
            val zone = ZoneId.systemDefault()
            val day = LocalDate.parse(date) // yyyy-MM-dd
            val start = day.atStartOfDay(zone).toInstant()
            // 오늘이면 '지금'까지만 (하루 전체를 잡으면 미래 구간이 포함된다)
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(Instant.now())
            if (!start.isBefore(end)) return null // 미래 날짜
            val range = TimeRangeFilter.between(start, end)

            // 출처(앱·기기)별로 따로 더해 가장 큰 쪽을 쓴다.
            //
            // Health Connect 의 합계(aggregate)는 겹치는 기록 중 사용자 우선순위 목록의 출처만 센다.
            // 삼성 폰은 시스템이 센서 값을 조각으로 넣는 출처("OO의 S26 Ultra")가 기본 1순위이고
            // 삼성 헬스가 넣는 하루 합계는 목록에 없어 무시된다. 그런데 조각 기록이 산책을 통째로 빠뜨려
            // 삼성 헬스 4,966걸음이 앱에서는 2,560으로 나왔다. 출처들은 같은 걸음을 각자 센 것이라
            // 합치면 안 되고, 가장 많이 잡은 출처가 가장 완전하다. 이러면 사용자가 Health Connect
            // 우선순위를 손대지 않아도 된다.
            var pageToken: String? = null
            val byOrigin = HashMap<String, Long>()
            do {
                val page = client.readRecords(
                    ReadRecordsRequest(StepsRecord::class, range, pageSize = 1000, pageToken = pageToken)
                )
                page.records.forEach { r ->
                    val origin = r.metadata.dataOrigin.packageName
                    byOrigin[origin] = (byOrigin[origin] ?: 0L) + r.count
                }
                pageToken = page.pageToken
            } while (pageToken != null)
            val best = byOrigin.values.maxOrNull()
            if (best != null) return best

            // 기록을 못 읽으면(권한이 집계만 허용되는 등) 예전처럼 합계로
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = range
                )
            )
            response[StepsRecord.COUNT_TOTAL]
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
