package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.data.model.withInbody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인바디 값을 Firestore 맵과 Room 프로필 사이에서 옮기는 변환 검증.
 */
class InbodyDataTest {

    private val sample = InbodyData(
        bmr = 1650, skeletalMuscle = 34.3f, bodyFat = 22.5f, bodyFatPercent = 24.5f,
        weight = 92.2f, measuredAt = "2026-10-01",
        analysis = "요약 글", analyzedAt = "2026-10-13"
    )

    @Test
    fun mapRoundTrip() {
        // Firestore는 숫자를 Long/Double로 돌려준다
        val raw = sample.toMap().mapValues { (_, v) ->
            when (v) {
                is Int -> v.toLong()
                is Float -> v.toDouble()
                else -> v
            }
        }
        assertEquals(sample, InbodyData.fromMap(raw))
    }

    @Test
    fun emptyOrBrokenMapIsNull() {
        assertNull(InbodyData.fromMap(null))
        assertNull(InbodyData.fromMap("문자열"))
        assertNull(InbodyData.fromMap(mapOf("bmr" to 0L, "measuredAt" to "2026-10-01")))
        assertFalse(InbodyData().hasData)
        assertTrue(InbodyData(bmr = 1500).hasData)
    }

    @Test
    fun profileKeepsOtherFieldsWhenInbodyChanges() {
        val profile = UserProfileEntity(height = 178f, initialWeight = 95f, birthDate = "1986-01-01", isSubscribed = true)
        val with = profile.withInbody(sample)
        assertEquals(1650, with.inbodyBmr)
        assertEquals("2026-10-01", with.inbodyMeasuredAt)
        assertEquals(178f, with.height)
        assertTrue(with.isSubscribed)
        assertEquals(sample, InbodyData.fromProfile(with))

        // null이면 지운다
        val cleared = with.withInbody(null)
        assertFalse(InbodyData.fromProfile(cleared).hasData)
        assertEquals(178f, cleared.height)
    }
}
