package com.youngs.dailynet.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 하드웨어 걸음 센서(TYPE_STEP_COUNTER)를 이용해 "오늘 걸은 걸음 수"를 계산한다.
 *
 * TYPE_STEP_COUNTER 값은 기기 부팅 이후의 누적 걸음 수이므로,
 * 하루 첫 조회 시점의 값을 기준선(baseline)으로 저장하고 (현재값 - 기준선)을 오늘 걸음 수로 계산한다.
 * (기기에 걸음 센서가 없으면 null 을 반환한다 — 예: 일부 태블릿)
 */
class StepCounterManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)

    /** 이 기기에 걸음 센서가 있는지 여부 */
    fun hasStepSensor(): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    /**
     * 오늘(today: "yyyy-MM-dd") 걸은 걸음 수를 반환. 센서가 없거나 값을 읽지 못하면 null.
     */
    suspend fun getTodaySteps(today: String): Int? {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        val total = readSensorOnce(sensorManager, stepSensor) ?: return null

        val baselineDate = prefs.getString("baseline_date", null)
        val baselineValue = prefs.getFloat("baseline_value", -1f)

        // 날짜가 바뀌었거나 최초 조회거나, 재부팅으로 누적값이 기준선보다 작아진 경우 → 기준선 갱신
        if (baselineDate != today || baselineValue < 0f || total < baselineValue) {
            prefs.edit()
                .putString("baseline_date", today)
                .putFloat("baseline_value", total)
                .apply()
            return 0
        }
        return (total - baselineValue).toInt().coerceAtLeast(0)
    }

    private suspend fun readSensorOnce(sm: SensorManager, sensor: Sensor): Float? =
        withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (!ok && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
}
