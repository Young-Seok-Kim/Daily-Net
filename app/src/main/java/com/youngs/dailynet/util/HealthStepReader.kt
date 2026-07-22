package com.youngs.dailynet.util

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
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

    /** 오늘(로컬 자정 ~ 현재) 총 걸음 수. 읽지 못하면 null */
    suspend fun getTodaySteps(): Long? {
        val client = clientOrNull() ?: return null
        return try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val end = Instant.now()
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[StepsRecord.COUNT_TOTAL]
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
