package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.model.InbodyAnalysisDetail
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.data.model.InbodyMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인바디 해석 구조가 Firestore 맵과 Room 엔티티를 오가며 깨지지 않는지.
 * b68 이전 기록(구조 없음)은 null로 남아 화면이 글로 물러나야 한다.
 */
class InbodyAnalysisDetailTest {

    private val detail = InbodyAnalysisDetail(
        summary = "체지방이 우선 과제입니다.",
        bodyFat = "표준보다 18%p 높습니다.",
        muscle = "",
        bmr = "공식보다 80kcal 높습니다.",
        actions = listOf("단백질 하루 150g", "주 3회 근력"),
        metrics = InbodyMetrics(
            bmi = 29.7f, fatLevel = InbodyMetrics.LEVEL_HIGH, fatStandard = "10~20%",
            smmRatio = 34.8f, muscleLevel = InbodyMetrics.LEVEL_LOW, muscleStandard = "체중의 40~45%",
            formulaBmr = 1890, bmrDiff = -260, visceralLevel = InbodyMetrics.LEVEL_HIGH, visceralStandard = "1~9",
            proteinTarget = 151
        )
    )

    /** Firestore는 숫자를 Long/Double로 돌려준다 */
    private fun firestoreLike(map: Map<String, Any>): Map<String, Any> = map.mapValues { (_, v) ->
        when (v) {
            is Int -> v.toLong()
            is Float -> v.toDouble()
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") firestoreLike(v as Map<String, Any>)
            else -> v
        }
    }

    @Test
    fun `Firestore 맵 왕복`() {
        assertEquals(detail, InbodyAnalysisDetail.fromMap(firestoreLike(detail.toMap())))
    }

    @Test
    fun `맵이 아니거나 비면 null, 칸이 빠지면 기본값`() {
        assertNull(InbodyAnalysisDetail.fromMap(null))
        assertNull(InbodyAnalysisDetail.fromMap("글"))
        val sparse = InbodyAnalysisDetail.fromMap(mapOf("summary" to "요약"))!!
        assertEquals("요약", sparse.summary)
        assertEquals(InbodyMetrics.LEVEL_UNKNOWN, sparse.metrics.fatLevel)
        assertTrue(sparse.actions.isEmpty())
    }

    @Test
    fun `할 일의 빈 줄과 글자가 아닌 값은 버린다`() {
        val d = InbodyAnalysisDetail.fromMap(mapOf("actions" to listOf("하나", "", 3, null, "둘")))!!
        assertEquals(listOf("하나", "둘"), d.actions)
    }

    @Test
    fun `그릴 내용이 하나도 없으면 renderable 아님`() {
        assertTrue(detail.isRenderable)
        assertFalse(InbodyAnalysisDetail().isRenderable)
        assertTrue(InbodyAnalysisDetail(actions = listOf("하나")).isRenderable)
    }

    @Test
    fun `InbodyData 맵과 엔티티를 거쳐도 구조가 남는다`() {
        val data = InbodyData(bmr = 1630, bodyFatPercent = 38.1f, weight = 94.2f, measuredAt = "2026-11-19",
            analysis = "글", analyzedAt = "2026-11-19", analysisDetail = detail)
        assertEquals(data, InbodyData.fromMap(firestoreLike(data.toMap())))
        assertEquals(data, InbodyRecordEntity.fromData(data).toData())
    }

    @Test
    fun `구조 없는 옛 기록은 null로 남는다`() {
        val legacy = InbodyData.fromMap(mapOf("bmr" to 1630L, "measuredAt" to "2026-01-01", "analysis" to "옛 글"))!!
        assertNull(legacy.analysisDetail)
        assertEquals("옛 글", legacy.analysis)
    }
}
