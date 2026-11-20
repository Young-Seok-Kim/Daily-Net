def rw(p, pairs):
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8", newline="\n").write(s)

# ---------------- app: AiAskContext ----------------
rw("app/src/main/java/com/youngs/dailynet/ui/view/AiAskContext.kt", [
("""    /** 압축 블록 상한. 한 줄 30자 × 730일에 여유. 서버 MAX_OLDER_CHARS(24000)보다 아래 */
    const val OLDER_MAX = 23000
""", """    /** 월별 요약 + 압축 블록 상한. 한 줄 30자 × 730일에 월별 줄 여유. 서버 MAX_OLDER_CHARS(28000)보다 아래 */
    const val OLDER_MAX = 26000
"""),
("""    /**
     * 최근 창 **이전**의 모든 날을 압축 한 줄("2026-05-22 -195 92.2kg food ex")로. 식단 글은 뺀다.
""", """    /**
     * 전체 기록 요약: **월별 한 줄**(모든 달) + 최근 창 **이전**의 모든 날을 압축 한 줄("2026-05-22 -195 92.2kg food ex")로.
     * 식단 글은 뺀다.
     *
     * 월별 줄은 월간 정산 카드와 같은 계산([buildPeriodSummary])으로 기록일수·평균 순칼로리·탄단지 평균과 권장량·운동일수·
     * 체중 변화를 적는다. "올해 탄수화물 알맞게 먹었어?"에 모델이 "이 화면엔 탄단지가 없다"고 답한 실기기 사례(2026-11-20) —
     * 탄단지는 정산 상세·월간 화면에만 가고 있었다.
"""),
("""     * @return 창 이전에 기록이 없으면 null (블록을 보내지 않는다)
     */
    fun olderDays(records: List<DailyRecordModel>, today: String): String? {
        val from = windowStart(today) ?: return null
        val older = records
            .filter { it.date < from }
            .distinctBy { it.date }
            .sortedByDescending { it.date }
        if (older.isEmpty()) return null
        val shown = older.take(OLDER_DAYS_MAX)
        val omitted = older.size - shown.size
        val head = "older days (${shown.last().date}..${shown.first().date}): ${shown.size} days, " +
            "format: date net(kcal) weight food(has meal text) ex(has exercise)" +
            if (omitted > 0) "; $omitted older days before ${shown.last().date} omitted (yearly summary on request)" else ""
        val lines = mutableListOf(head)
        shown.forEach { lines += compactLine(it) }
        return lines.joinToString("\\n").take(OLDER_MAX)
    }
""", """     * @return 오늘까지의 기록이 하나도 없으면 null (블록을 보내지 않는다)
     */
    fun olderDays(records: List<DailyRecordModel>, today: String): String? {
        val from = windowStart(today) ?: return null
        val past = records
            .filter { it.date <= today }
            .distinctBy { it.date }
            .sortedByDescending { it.date }
        if (past.isEmpty()) return null
        val lines = mutableListOf<String>()

        // 월별 요약. 권장 탄단지의 체중은 월간 카드와 같이 그 달 기록 체중, 없으면 최근 체중
        val latestWeight = past.firstOrNull { it.weight > 0f }?.weight ?: 0f
        val byMonth = past.groupBy { it.date.take(7) }.toSortedMap(compareByDescending { it })
        lines += "monthly (${byMonth.lastKey()}..${byMonth.firstKey()}): ${byMonth.size} months, " +
            "format: month days avg-net macros-avg(over analyzed days; rec = recommended daily) exercise weight-change"
        byMonth.forEach { (ym, recs) -> lines += monthLine(ym, buildPeriodSummary(recs, latestWeight)) }

        val older = past.filter { it.date < from }
        if (older.isNotEmpty()) {
            val shown = older.take(OLDER_DAYS_MAX)
            val omitted = older.size - shown.size
            lines += "older days (${shown.last().date}..${shown.first().date}): ${shown.size} days, " +
                "format: date net(kcal) weight food(has meal text) ex(has exercise)" +
                if (omitted > 0) "; $omitted older days before ${shown.last().date} omitted (yearly summary on request)" else ""
            shown.forEach { lines += compactLine(it) }
        }
        return lines.joinToString("\\n").take(OLDER_MAX)
    }

    /** "- 2026-11: 19 days, avg net -334 kcal, macros avg C172g P92g F48g over 15 days (rec C210g P112g F55g), exercise 8 days, weight -0.8kg" */
    private fun monthLine(ym: String, s: PeriodSummary): String {
        val sb = StringBuilder("- $ym: ${s.recordDays} days, avg net ${kcal(s.netAverage)}")
        if (s.nutritionDays > 0) {
            sb.append(", macros avg C${s.carbAvg.toInt()}g P${s.proteinAvg.toInt()}g F${s.fatAvg.toInt()}g over ${s.nutritionDays} days")
            sb.append(" (rec C${s.recCarb}g P${s.recProtein}g F${s.recFat}g)")
        }
        sb.append(", exercise ${s.exerciseDays} days")
        s.weightDiff?.let { sb.append(", weight ${signedKg(it)}") }
        return sb.toString()
    }
"""),
("""    private fun dayLine(record: DailyRecordModel): String {
        val parts = mutableListOf("net ${kcal(record.netCalories)}")
        if (record.weight > 0f) parts += "weight ${kg(record.weight)}"
""", """    private fun dayLine(record: DailyRecordModel): String {
        val parts = mutableListOf("net ${kcal(record.netCalories)}")
        if (record.weight > 0f) parts += "weight ${kg(record.weight)}"
        // 탄단지. "이번 주 단백질 충분했어?"는 식단 글만으로는 못 답한다
        if (record.hasNutritionData) {
            parts += "C${record.carbGram.toInt()}g P${record.proteinGram.toInt()}g F${record.fatGram.toInt()}g"
        }
"""),
])

# ---------------- app tests ----------------
rw("app/src/test/java/com/youngs/dailynet/AiAskContextTest.kt", [
("""        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        // 창(10-21~)보다 앞선 5-22 ~ 10-20 = 152일, 최신순
        assertEquals("older days (2026-05-22..2026-10-20): 152 days, format: date net(kcal) weight food(has meal text) ex(has exercise)", lines[0])
        assertEquals(153, lines.size)
        assertTrue(lines[1].startsWith("2026-10-20 "))
        assertEquals("2026-05-22 -195 92.2kg food ex", lines.last())
        // 5-23은 체중·식단·운동이 모두 없다
        assertEquals("2026-05-23 -194", lines[lines.size - 2])
        assertFalse(text.contains("식단 글"))
        assertFalse(text.contains("2026-10-21"))

        // 창 이전 기록이 없으면 null
        assertEquals(null, AiAskContext.olderDays(listOf(record(date = "2026-11-01")), today))
        assertEquals(null, AiAskContext.olderDays(records, "오늘"))
    }
""", """        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        // 앞에는 월별 요약(5월~11월 7달, 최신순). 탄단지가 없는 기록이라 macros 칸은 없다
        assertTrue(lines[0], lines[0].startsWith("monthly (2026-05..2026-11): 7 months, format: month days avg-net macros-avg"))
        assertTrue(lines[1], lines[1].startsWith("- 2026-11: 19 days, avg net "))
        assertTrue(lines[7], lines[7].startsWith("- 2026-05: 10 days, avg net "))
        assertFalse(text.contains("macros avg"))
        // 그 뒤 창(10-21~)보다 앞선 5-22 ~ 10-20 = 152일, 최신순
        val olderAt = lines.indexOfFirst { it.startsWith("older days") }
        assertEquals(8, olderAt)
        assertEquals("older days (2026-05-22..2026-10-20): 152 days, format: date net(kcal) weight food(has meal text) ex(has exercise)", lines[olderAt])
        assertEquals(olderAt + 153, lines.size)
        assertTrue(lines[olderAt + 1].startsWith("2026-10-20 "))
        assertEquals("2026-05-22 -195 92.2kg food ex", lines.last())
        // 5-23은 체중·식단·운동이 모두 없다
        assertEquals("2026-05-23 -194", lines[lines.size - 2])
        assertFalse(text.contains("식단 글"))
        assertFalse(text.contains("2026-10-21 "))

        // 창 안 기록뿐이면 월별 줄만 있고 older days는 없다. 기록이 아예 없거나 오늘 날짜가 깨졌으면 null
        val onlyRecent = AiAskContext.olderDays(listOf(record(date = "2026-11-01")), today)!!
        assertTrue(onlyRecent.startsWith("monthly (2026-11..2026-11): 1 months"))
        assertFalse(onlyRecent.contains("older days"))
        assertEquals(null, AiAskContext.olderDays(emptyList(), today))
        assertEquals(null, AiAskContext.olderDays(records, "오늘"))
    }

    @Test
    fun `탄단지는 최근 30일 줄과 월별 요약에 권장량과 함께 들어간다`() {
        val today = "2026-11-19"
        val analyzed = record(date = "2026-11-18", weight = 78.2f, bmr = 1750, carb = 180f, protein = 95f, fat = 45f)
        val plain = record(date = "2026-11-17")

        val recent = AiAskContext.recentDays(listOf(analyzed, plain), today, "")!!
        assertTrue(recent, recent.contains("- 2026-11-18 net -420 kcal, weight 78.2kg, C180g P95g F45g"))
        assertFalse(recent.contains("2026-11-17 net -420 kcal, C"))

        // 권장량은 월간 카드와 같은 계산(bmr 1750 × 1.375 − 500 = 1906kcal, 단백질 78.2×1.6)
        val monthly = AiAskContext.olderDays(listOf(analyzed, plain), today)!!
        assertTrue(monthly, monthly.contains("- 2026-11: 2 days, avg net -420 kcal, macros avg C180g P95g F45g over 1 days (rec C232g P125g F53g), exercise 0 days"))
    }
"""),
("""        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        assertEquals(AiAskContext.OLDER_DAYS_MAX + 1, lines.size)
        // 가장 오래된 줄은 2026-04-14 이전이 아니라, 최신 730일의 마지막
        val oldest = lines.last().substring(0, 10)
        assertTrue(lines[0], lines[0].contains("; ${1200 - AiAskContext.OLDER_DAYS_MAX} older days before $oldest omitted (yearly summary on request)"))
        assertTrue(text.length <= AiAskContext.OLDER_MAX)""",
 """        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        val olderAt = lines.indexOfFirst { it.startsWith("older days") }
        assertEquals(olderAt + 1 + AiAskContext.OLDER_DAYS_MAX, lines.size)
        // 가장 오래된 줄은 최신 730일의 마지막
        val oldest = lines.last().substring(0, 10)
        assertTrue(lines[olderAt], lines[olderAt].contains("; ${1200 - AiAskContext.OLDER_DAYS_MAX} older days before $oldest omitted (yearly summary on request)"))
        assertTrue(text.length <= AiAskContext.OLDER_MAX)"""),
])

# ---------------- server ----------------
rw("functions/askAiCoach.js", [
("""/**
 * 최근 창 이전의 압축 블록(olderData)의 상한. 앱이 "날짜 순칼로리 체중 운동" 한 줄(30자쯤) × 최대 730일을 보낸다.
 * "가장 심했던 날", "언제부터" 같은 전 기간 질문을 저장소 읽기 없이 한 번에 답하기 위한 것이다.
 */
const MAX_OLDER_CHARS = 24000;""",
 """/**
 * 전체 기록 요약 블록(olderData)의 상한. 앱이 월별 한 줄(탄단지 평균·권장량 포함)과 최근 창 이전의 "날짜 순칼로리 체중 운동"
 * 한 줄(30자쯤) × 최대 730일을 보낸다. "가장 심했던 날", "언제부터", "올해 탄수화물" 같은 전 기간 질문을 저장소 읽기 없이 답한다.
 */
const MAX_OLDER_CHARS = 28000;"""),
(""" *   - olderData: 그 이전 모든 날의 압축 줄 "날짜 순칼로리 체중 운동" (없으면 "")""",
 """ *   - olderData: 전체 기록 요약 — 월별 한 줄 + 그 이전 모든 날의 압축 줄 "날짜 순칼로리 체중 운동" (없으면 "")"""),
("""            `[${recentFrom} 이전의 전체 기록 — 하루 한 줄 "날짜 순칼로리(kcal) 체중 food ex". food는 식단 글이, ex는 운동 글이 기록돼 있다는 표시일 뿐 내용은 여기 없습니다]`,""",
 """            `[전체 기록 요약 (월별 한 줄 + ${recentFrom} 이전 하루 한 줄) — 월별 줄은 기록일수·평균 순칼로리·탄단지 평균(macros avg)과 권장량(rec)·운동일수·체중 변화. 하루 줄은 "날짜 순칼로리(kcal) 체중 food ex"이고 food는 식단 글이, ex는 운동 글이 기록돼 있다는 표시일 뿐 내용은 여기 없습니다]`,"""),
("""        `   [${recentFrom} 이전의 전체 기록]을 합쳐 모든 줄을 훑어 고르십시오. 그 블록이 있으면 규칙 8로 전체를 다시 요청하지 마십시오.`,""",
 """        "   [전체 기록 요약]을 합쳐 모든 줄을 훑어 고르십시오. 그 블록이 있으면 규칙 8로 전체를 다시 요청하지 마십시오.",
        `   탄수화물·단백질·지방 질문은 [최근 ${RECENT_DAYS}일 기록] 줄의 C/P/F g과 [전체 기록 요약] 월별 줄의 macros avg를 rec(권장량)와 견주어 답하십시오.`,
        "   \\"올해\\"·\\"지난달\\"처럼 기간이 길면 월별 줄로 답하고, 두 곳 모두에 탄단지가 없을 때만 없다고 하십시오.","""),
])

rw("functions/test/askAiCoach.test.js", [
("""        const older = `older days (2026-05-22..${shiftDate(from, -1)}): 152 days, format: date net(kcal) weight exercise\\n2026-05-22 -195 92.2kg ex`;
        const withOlder = buildPrompt({ ...base, recentData: recent, olderData: older, askedBlock: "[질문이 가리킨 날짜의 기록 — 서버가 저장소에서 읽어 온 값]\\nx" });
        const olderAt = withOlder.indexOf(`[${from} 이전의 전체 기록`);
        assert.ok(withOlder.indexOf(`[최근 ${RECENT_DAYS}일 기록`) < olderAt && olderAt < withOlder.indexOf("[질문이 가리킨 날짜의 기록"));
        assert.match(withOlder, /2026-05-22 -195 92.2kg ex/);
        assert.ok(withOlder.includes(`[${from} 이전의 전체 기록]을 합쳐 모든 줄을 훑어 고르십시오`));
        assert.doesNotMatch(buildPrompt({ ...base, recentData: recent }), /이전의 전체 기록 — 하루 한 줄/);""",
 """        const older = `monthly (2026-05..2026-11): 7 months\\n- 2026-11: 19 days, avg net -334 kcal, macros avg C172g P92g F48g over 15 days (rec C210g P112g F55g)\\nolder days (2026-05-22..${shiftDate(from, -1)}): 152 days, format: date net(kcal) weight exercise\\n2026-05-22 -195 92.2kg ex`;
        const withOlder = buildPrompt({ ...base, recentData: recent, olderData: older, askedBlock: "[질문이 가리킨 날짜의 기록 — 서버가 저장소에서 읽어 온 값]\\nx" });
        const olderAt = withOlder.indexOf(`[전체 기록 요약 (월별 한 줄 + ${from} 이전 하루 한 줄)`);
        assert.ok(olderAt > 0);
        assert.ok(withOlder.indexOf(`[최근 ${RECENT_DAYS}일 기록`) < olderAt && olderAt < withOlder.indexOf("[질문이 가리킨 날짜의 기록"));
        assert.match(withOlder, /2026-05-22 -195 92.2kg ex/);
        assert.match(withOlder, /macros avg C172g P92g F48g/);
        assert.ok(withOlder.includes("[전체 기록 요약]을 합쳐 모든 줄을 훑어 고르십시오"));
        // 탄단지 질문은 최근 줄의 C/P/F와 월별 macros avg·rec로 답하라고 지시 (2026-11-20 "올해 탄수화물" 실기기 사례)
        assert.match(withOlder, /탄수화물·단백질·지방 질문은 \\[최근 \\d+일 기록\\] 줄의 C\\/P\\/F g과 \\[전체 기록 요약\\] 월별 줄의 macros avg를 rec\\(권장량\\)와 견주어/);
        assert.doesNotMatch(buildPrompt({ ...base, recentData: recent }), /\\[전체 기록 요약 \\(월별 한 줄/);"""),
])

# ---------------- version + changelog ----------------
rw("app/build.gradle.kts", [
('        versionCode = 72\n        versionName = "1.18.4"', '        versionCode = 73\n        versionName = "1.18.5"'),
])
p = "CHANGELOG.md"
s = open(p, encoding="utf-8").read()
marker = "---\n\n## v1.18.4 (b72) — 2026-11-20"
assert marker in s
entry = """---

## v1.18.5 (b73) — 2026-11-20

### 스토어 문구

AI 질문이 탄수화물·단백질·지방까지 봅니다. "올해 탄수화물 알맞게 먹었어?", "이번 주 단백질 충분했어?"에 월별 평균과 권장량을 근거로 답합니다.

### 상세 내역

- **AI 질문에 탄단지 전달** (`ui/view/AiAskContext.kt`)
  - 최근 30일 하루 줄(`dayLine`)에 분석된 날의 `C180g P95g F45g`. 목록·기간 요약의 하루 줄도 같은 함수라 함께 붙는다.
  - 전체 기록 요약 블록(`olderDays`, `olderData`) 앞에 **월별 한 줄**: 기록일수·평균 순칼로리·탄단지 평균과 권장량(`buildPeriodSummary`,
    월간 카드와 같은 계산)·운동일수·체중 변화. 모든 달을 담고, 그 뒤에 전처럼 창 이전 하루 압축 줄.
  - 홈에서 "올해 탄수화물은 알맞게 먹은거냐"에 모델이 "이 화면엔 탄단지가 없다"고 답한 실기기 사례 — 탄단지는 정산 상세와 월간 화면에만 가고 있었다.
  - 블록이 null인 조건이 "창 이전 기록 없음"에서 "오늘까지 기록 없음"으로 바뀜. `OLDER_MAX` 23000→26000.
- 테스트 `AiAskContextTest`: 월별 요약·탄단지 테스트 추가, 기존 압축 블록 테스트 갱신(22개).

### 서버 (`functions/askAiCoach.js`, 같은 날 수동 배포)

- 블록 제목을 `[전체 기록 요약 (월별 한 줄 + yyyy-MM-dd 이전 하루 한 줄) — …]`로 바꾸고 월별 줄의 뜻을 적음. `MAX_OLDER_CHARS` 24000→28000.
- 지침: 탄단지 질문은 최근 줄의 C/P/F와 월별 macros avg를 rec(권장량)와 견주어 답하고, 긴 기간은 월별 줄로, 둘 다 없을 때만 없다고.

"""
s = s.replace(marker, entry + marker, 1)
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
