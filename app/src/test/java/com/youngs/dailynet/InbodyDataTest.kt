package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
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
        visceralFatLevel = 12, inbodyScore = 68, bodyWater = 41.3f,
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
        // 프로필 컬럼은 b41 항목(내장지방·점수·체수분)을 담지 못한다
        assertEquals(sample.copy(visceralFatLevel = 0, inbodyScore = 0, bodyWater = 0f), InbodyData.fromProfile(with))

        // null이면 지운다
        val cleared = with.withInbody(null)
        assertFalse(InbodyData.fromProfile(cleared).hasData)
        assertEquals(178f, cleared.height)
    }

    @Test
    fun recordEntityRoundTrip() {
        val entity = InbodyRecordEntity.fromData(sample, createdAt = 7L)
        assertEquals("2026-10-01", entity.measuredAt)
        assertEquals(12, entity.visceralFatLevel)
        assertEquals(sample, entity.toData())
    }

    @Test
    fun profileMirrorDropsFieldsItCannotHold() {
        // 프로필 컬럼에는 b41 항목(내장지방 등)이 없다. 최근 측정 복사본은 그 셋만 비고 나머지는 같다
        val mirrored = InbodyData.fromProfile(UserProfileEntity(initialWeight = 90f, birthDate = "1990-01-01").withInbody(sample))
        assertEquals(sample.copy(visceralFatLevel = 0, inbodyScore = 0, bodyWater = 0f), mirrored)
    }
}
