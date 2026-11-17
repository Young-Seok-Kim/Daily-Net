/**
 * 기간(주·월·연) 평가의 집계 규칙.
 *
 * 모델은 숫자를 못 믿으니 서버가 센다. 그래서 확인할 것은
 *  - 앱이 보낸 일별 기록에서 형식이 틀린 줄·중복은 걸러진다
 *  - 합계·평균·적자 일수·체중 변화·지방 환산이 맞다
 *  - 진행 중인 기간은 오늘까지만 "지난 날"로 센다
 *  - 연은 달별, 월은 주별로 흐름을 묶는다
 *  - 모델 응답이 비면 null로 떨어져 실패로 다뤄진다
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const {
    normalizeDays, normalizeTopItems, periodStats, factLines, dayLines, normalizeEvaluation,
    isoWeekKey, daysBetween, MAX_TEXT
} = require("../periodEval");

function day(date, net, extra = {}) {
    return { date, net, bmr: 1600, breakfastKcal: 400, lunchKcal: 600, dinnerKcal: 500, snackKcal: 0, ...extra };
}

test("날짜가 깨진 줄은 버리고 중복은 뒤의 것, 오름차순으로", () => {
    const days = normalizeDays([
        day("2026-11-03", -300),
        { date: "11/02", net: 100 },
        { net: 50 },
        day("2026-11-01", 200),
        day("2026-11-03", -500, { exercise: "   달리기   30분 " })
    ]);
    assert.deepEqual(days.map((d) => d.date), ["2026-11-01", "2026-11-03"]);
    assert.equal(days[1].net, -500);
    assert.equal(days[1].exercise, "달리기 30분");
    assert.equal(normalizeDays(null).length, 0);
});

test("식단 텍스트는 앞부분만 남긴다", () => {
    const long = "김치찌개 ".repeat(40);
    const [d] = normalizeDays([day("2026-11-01", 0, { breakfast: long })]);
    assert.ok(d.breakfast.length <= MAX_TEXT + 1);
    assert.ok(d.breakfast.endsWith("…"));
});

test("집계: 합계·평균·적자 일수·체중 변화·지방 환산", () => {
    const days = normalizeDays([
        day("2026-11-01", -500, { weight: 80, hasExercise: true, exerciseKcal: 300, carb: 200, protein: 100, fat: 50, steps: 8000 }),
        day("2026-11-02", 300, { carb: 300, protein: 60, fat: 80 }),
        day("2026-11-03", -700, { weight: 79.2, hasExercise: true, exerciseKcal: 400, carb: 180, protein: 120, fat: 60, steps: 12000 })
    ]);
    const stats = periodStats(days, { type: "month", start: "2026-11-01", end: "2026-11-30" }, "2026-12-05", 80);

    assert.equal(stats.recordDays, 3);
    assert.equal(stats.exerciseDays, 2);
    assert.equal(stats.netTotal, -900);
    assert.equal(stats.netAvg, -300);
    assert.equal(stats.deficitDays, 2);
    assert.equal(stats.surplusDays, 1);
    assert.equal(stats.weightFirst, 80);
    assert.equal(stats.weightLast, 79.2);
    assert.equal(stats.weightDiff, -0.8);
    assert.equal(stats.estFatKg, -0.1);
    assert.equal(stats.intakeAvg, 1500);
    assert.equal(stats.burnAvg, Math.round(700 / 3));
    assert.equal(stats.stepsAvg, 10000);
    assert.equal(stats.proteinAvg, 93.3);
    assert.equal(stats.inProgress, false);
    assert.equal(stats.totalDays, 30);
    assert.equal(stats.elapsedDays, 30);
    assert.deepEqual(stats.bestDay, { date: "2026-11-03", net: -700 });
    assert.deepEqual(stats.worstDay, { date: "2026-11-02", net: 300 });
    // 권장은 평균 기초대사량과 마지막 체중으로. 단백질 1.6g/kg
    assert.equal(stats.avgBmr, 1600);
    assert.equal(stats.recommended.protein, Math.round(79.2 * 1.6));
});

test("진행 중인 기간은 오늘까지만 지난 날로 센다", () => {
    const days = normalizeDays([day("2026-11-01", -100), day("2026-11-05", -100)]);
    const stats = periodStats(days, { type: "month", start: "2026-11-01", end: "2026-11-30" }, "2026-11-10", 70);
    assert.equal(stats.inProgress, true);
    assert.equal(stats.elapsedDays, 10);
    assert.equal(stats.totalDays, 30);

    // 시작·끝을 못 읽어도 기록 일수보다 작아지지는 않는다
    const broken = periodStats(days, { type: "week" }, "2026-11-10", 70);
    assert.equal(broken.elapsedDays, 2);
});

test("체중이 하루만 있으면 변화는 null, 전부 흑자면 최악의 날은 따로 내지 않는다", () => {
    const days = normalizeDays([day("2026-11-01", 100, { weight: 80 }), day("2026-11-02", 200)]);
    const stats = periodStats(days, { type: "week", start: "2026-10-27", end: "2026-11-02" }, "2026-11-20", 80);
    assert.equal(stats.weightDiff, null);
    assert.equal(stats.weightLast, 80);
    assert.deepEqual(stats.bestDay, { date: "2026-11-01", net: 100 });
    assert.equal(stats.worstDay.date, "2026-11-02");

    // 하루뿐이거나 전부 같은 값이면 최고와 최악이 같은 날이라 최악은 내지 않는다
    const single = periodStats(normalizeDays([day("2026-11-01", 100)]), { type: "week" }, "", 0);
    assert.equal(single.worstDay, null);
    const same = periodStats(normalizeDays([day("2026-11-01", 100), day("2026-11-02", 100)]), { type: "week" }, "", 0);
    assert.equal(same.worstDay, null);
});

test("영양 집계가 없는 날(bmr 0)은 탄단지 평균에서 빠지고 권장은 없다", () => {
    const days = normalizeDays([
        { date: "2026-11-01", net: -200, bmr: 0, carb: 0, protein: 0, fat: 0 },
        day("2026-11-02", -100, { carb: 100, protein: 80, fat: 40 })
    ]);
    const stats = periodStats(days, { type: "week" }, "", 70);
    assert.equal(stats.nutritionDays, 1);
    assert.equal(stats.proteinAvg, 80);

    const none = periodStats(normalizeDays([{ date: "2026-11-01", net: -200, bmr: 0 }]), { type: "week" }, "", 70);
    assert.equal(none.recommended, null);
    assert.equal(none.nutritionDays, 0);
});

test("연은 달별, 월은 주별로 묶고 주는 묶지 않는다", () => {
    const days = normalizeDays([
        day("2026-01-10", -100, { weight: 80 }),
        day("2026-01-20", -300, { weight: 79 }),
        day("2026-03-01", 200)
    ]);
    const year = periodStats(days, { type: "year", start: "2026-01-01", end: "2026-12-31" }, "2026-12-31", 80);
    assert.deepEqual(year.buckets.map((b) => b.label), ["2026-01", "2026-03"]);
    assert.equal(year.buckets[0].days, 2);
    assert.equal(year.buckets[0].netAvg, -200);
    assert.equal(year.buckets[0].weightLast, 79);

    const month = periodStats(
        normalizeDays([day("2026-11-02", -100), day("2026-11-03", -100), day("2026-11-09", 500)]),
        { type: "month", start: "2026-11-01", end: "2026-11-30" }, "2026-12-01", 80
    );
    assert.deepEqual(month.buckets.map((b) => b.label), ["2026-W45", "2026-W46"]);

    assert.deepEqual(periodStats(days, { type: "week" }, "", 80).buckets, []);
});

test("ISO 주차: 월요일 시작, 연말·연초 경계", () => {
    assert.equal(isoWeekKey("2026-11-02"), "2026-W45"); // 월
    assert.equal(isoWeekKey("2026-11-08"), "2026-W45"); // 일 — 같은 주
    assert.equal(isoWeekKey("2026-11-09"), "2026-W46");
    assert.equal(isoWeekKey("2027-01-01"), "2026-W53"); // 2027-01-01은 금요일이라 2026년 마지막 주
    assert.equal(isoWeekKey("bad"), "");
    assert.equal(daysBetween("2026-11-01", "2026-11-30"), 30);
    assert.equal(daysBetween("2026-11-30", "2026-11-01"), 0);
});

test("사실 줄에는 있는 값만, 없는 값은 줄이 없다", () => {
    const days = normalizeDays([day("2026-11-01", -500), day("2026-11-02", -400)]);
    const period = { type: "week", label: "202645", title: "11월 1주차", start: "2026-10-27", end: "2026-11-02" };
    const stats = periodStats(days, period, "2026-11-20", 70);
    const lines = factLines(stats, period, [{ name: "닭가슴살", count: 5 }], []);
    const joined = lines.join("\n");

    assert.match(joined, /11월 1주차/);
    assert.match(joined, /순칼로리 합계 -900 kcal, 하루 평균 -450 kcal/);
    assert.match(joined, /닭가슴살\(5회\)/);
    assert.doesNotMatch(joined, /체중/);          // 체중 기록 없음
    assert.doesNotMatch(joined, /걸음/);          // 걸음 없음
    assert.doesNotMatch(joined, /자주 한 운동/);   // 빈 목록
    assert.doesNotMatch(joined, /진행 중/);        // 끝난 기간
});

test("일별 줄은 연에는 없고, 빈 끼니는 적지 않는다", () => {
    const days = normalizeDays([
        day("2026-11-01", -500, { breakfast: "계란 2개", dinner: "삼겹살", exercise: "걷기 1시간", weight: 80 }),
        day("2026-11-02", 100, { hasExercise: true })
    ]);
    const lines = dayLines(days, "month");
    assert.equal(lines.length, 2);
    assert.match(lines[0], /아침: 계란 2개 · 저녁: 삼겹살/);
    assert.doesNotMatch(lines[0], /점심/);
    assert.match(lines[0], /80kg/);
    assert.match(lines[0], /운동: 걷기 1시간/);
    assert.match(lines[1], /\(식단 없음\)/);
    assert.match(lines[1], /운동 있음/);
    assert.deepEqual(dayLines(days, "year"), []);
});

test("자주 먹은 음식 목록은 이름 없는 것·0회를 버리고 많은 순", () => {
    const items = normalizeTopItems([
        { name: "라면", count: 2 }, { name: "", count: 9 }, { name: "닭가슴살", count: 7 }, { name: "물", count: 0 }, null
    ]);
    assert.deepEqual(items, [{ name: "닭가슴살", count: 7 }, { name: "라면", count: 2 }]);
});

test("모델 응답 정리: 점수는 0~100 정수, 할 일은 3개까지, 비면 null", () => {
    const ok = normalizeEvaluation({
        score: "87.6", headline: " 잘했습니다 ", summary: "총평", diet: "식단", exercise: "운동",
        actions: ["하나", "", "둘", "셋", "넷"]
    });
    assert.equal(ok.score, 88);
    assert.equal(ok.headline, "잘했습니다");
    assert.deepEqual(ok.actions, ["하나", "둘", "셋"]);

    assert.equal(normalizeEvaluation({ score: 150, summary: "x" }).score, 100);
    assert.equal(normalizeEvaluation({ score: -5, summary: "x" }).score, 0);
    assert.equal(normalizeEvaluation({ score: "abc", summary: "x" }).score, 0);
    assert.equal(normalizeEvaluation({ score: 90, headline: "제목만" }), null);
    assert.equal(normalizeEvaluation(null), null);
});
