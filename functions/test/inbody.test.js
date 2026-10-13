/**
 * 인바디 값 검증과 기초대사량 선택 규칙.
 *
 * 인바디에 적힌 기초대사량이 있으면 공식 대신 그 값을 쓴다. 그래서 확인할 것은
 *  - 범위 밖 숫자(결과지를 잘못 읽은 값)는 버린다
 *  - 인바디 기초대사량이 있으면 그것, 없으면 공식
 *  - 프롬프트 줄은 있는 값만 적는다
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { normalizeInbody, resolveBmr, inbodyPromptLine, ageFrom, bodyMetrics, inbodyReportText } = require("../inbody");
const { LABELS } = require("../labels");
const { calculateBmr } = require("../nutrition");

test("범위 밖 값은 0으로, 전부 비면 null", () => {
    const ok = normalizeInbody({ bmr: "1650.4", skeletalMuscle: 34.26, bodyFat: 22.5, bodyFatPercent: 24.49, weight: 92.2, measuredAt: "2026-10-01" });
    assert.deepEqual(ok, {
        bmr: 1650, skeletalMuscle: 34.3, bodyFat: 22.5, bodyFatPercent: 24.5, weight: 92.2,
        visceralFatLevel: 0, inbodyScore: 0, bodyWater: 0, measuredAt: "2026-10-01"
    });

    // b41 항목: 내장지방 레벨·인바디 점수는 정수, 체수분은 소수 첫째 자리
    const more = normalizeInbody({ bmr: 1600, visceralFatLevel: "12.4", inbodyScore: 67.6, bodyWater: 41.26 });
    assert.equal(more.visceralFatLevel, 12);
    assert.equal(more.inbodyScore, 68);
    assert.equal(more.bodyWater, 41.3);
    assert.equal(normalizeInbody({ bmr: 1600, visceralFatLevel: 45 }).visceralFatLevel, 0); // 범위 밖

    // 체지방률 자리에 체중이 들어온 경우: 그 칸만 버리고 나머지는 남는다
    const partly = normalizeInbody({ bmr: 1650, bodyFatPercent: 92.2 });
    assert.equal(partly.bodyFatPercent, 0);
    assert.equal(partly.bmr, 1650);

    assert.equal(normalizeInbody({ bmr: 0, skeletalMuscle: "x" }), null);
    assert.equal(normalizeInbody(null), null);
    assert.equal(normalizeInbody("문자열"), null);

    // 측정일은 형식이 맞을 때만
    assert.equal(normalizeInbody({ bmr: 1500, measuredAt: "10/01" }).measuredAt, "");
});

test("인바디 기초대사량이 있으면 공식 대신 쓴다", () => {
    const base = { weight: 92, height: 178, age: 40, isMale: true };
    const formula = calculateBmr(92, 178, 40, true);

    const withInbody = resolveBmr({ ...base, inbody: { bmr: 1900 } });
    assert.equal(withInbody.bmr, 1900);
    assert.equal(withInbody.source, "inbody");
    assert.equal(withInbody.formula, formula);

    const without = resolveBmr({ ...base, inbody: null });
    assert.equal(without.bmr, formula);
    assert.equal(without.source, "formula");

    // 골격근량만 있고 기초대사량이 없으면 공식
    const noBmr = resolveBmr({ ...base, inbody: { bmr: 0, skeletalMuscle: 34 } });
    assert.equal(noBmr.source, "formula");
});

test("프롬프트 줄은 있는 값만 적는다", () => {
    assert.equal(inbodyPromptLine(null), "");
    const line = inbodyPromptLine({ bmr: 1650, skeletalMuscle: 34.3, bodyFat: 0, bodyFatPercent: 24.5, weight: 92.2, measuredAt: "2026-10-01" });
    assert.match(line, /기초대사량 1650kcal\(실측\)/);
    assert.match(line, /골격근량 34\.3kg/);
    assert.match(line, /체지방률 24\.5%/);
    assert.doesNotMatch(line, /체지방량/);
    assert.match(line, /2026-10-01 측정/);
});

test("수치 판정: BMI·체지방 구간·골격근 비율·공식 차이", () => {
    const inbody = { bmr: 1630, skeletalMuscle: 32.8, bodyFat: 35.9, bodyFatPercent: 38.1, weight: 94.2, measuredAt: "2026-09-28" };
    const m = bodyMetrics({ inbody, height: 178, weight: 92.2, age: 40, isMale: true });
    assert.equal(m.weight, 94.2);                 // 측정 체중 우선
    assert.equal(m.bmi, 29.7);
    assert.equal(m.fatLevel, "high");             // 남성 표준 10~20%
    assert.equal(m.smmRatio, 34.8);               // 32.8 / 94.2
    assert.equal(m.muscleLevel, "low");           // 남성 표준 40~46%
    assert.equal(m.formulaBmr, calculateBmr(94.2, 178, 40, true));
    assert.equal(m.bmrDiff, 1630 - m.formulaBmr);
    assert.equal(m.proteinTarget, Math.round(94.2 * 1.6));
    assert.equal(m.visceralLevel, "unknown");       // 없으면 unknown
    assert.equal(bodyMetrics({ inbody: { ...inbody, visceralFatLevel: 12 }, height: 178, weight: 92.2, age: 40, isMale: true }).visceralLevel, "high");

    // 값이 없는 항목은 unknown, 측정 체중이 없으면 오늘 체중
    const m2 = bodyMetrics({ inbody: { bmr: 1500, skeletalMuscle: 0, bodyFat: 0, bodyFatPercent: 0, weight: 0 }, height: 165, weight: 60, age: 30, isMale: false });
    assert.equal(m2.weight, 60);
    assert.equal(m2.fatLevel, "unknown");
    assert.equal(m2.muscleLevel, "unknown");
});

test("나이: 생일 전후", () => {
    const today = new Date("2026-10-13");
    assert.equal(ageFrom("1986-10-14", today), 39);
    assert.equal(ageFrom("1986-10-13", today), 40);
    assert.equal(ageFrom("", today), 29);
    assert.equal(ageFrom("나중에", today), 29);
});

test("해석 글은 모델이 비운 칸과 없는 항목을 뺀다", () => {
    const inbody = { bmr: 1630, skeletalMuscle: 32.8, bodyFat: 0, bodyFatPercent: 38.1, weight: 94.2, measuredAt: "" };
    const metrics = bodyMetrics({ inbody, height: 178, weight: 94.2, age: 40, isMale: true });
    const text = inbodyReportText(
        { summary: "요약", bodyFat: "지방 설명", muscle: "", bmr: "대사 설명", actions: ["하나", "", "둘", "셋", "넷"] },
        metrics, inbody, LABELS.ko
    );
    assert.match(text, /^요약/);
    assert.match(text, /📏 측정값/);
    assert.match(text, /체지방률 38\.1% \(표준 이상, 표준 10~20%\)/);
    assert.match(text, /🧈 체지방\n지방 설명/);
    assert.doesNotMatch(text, /💪 골격근/);       // 모델이 비움
    assert.match(text, /🔥 기초대사량\n대사 설명/);
    assert.match(text, /1\. 하나\n2\. 둘\n3\. 셋/); // 빈 칸 빼고 3개까지
    assert.doesNotMatch(text, /넷/);

    // 응답이 깨져도 측정값 블록은 남는다
    assert.match(inbodyReportText(null, metrics, inbody, LABELS.en), /Measurements/);

    // 내장지방·점수·체수분은 있을 때만 측정값에 들어간다
    const rich = { ...inbody, visceralFatLevel: 12, inbodyScore: 68, bodyWater: 41.3 };
    const richText = inbodyReportText({}, bodyMetrics({ inbody: rich, height: 178, weight: 94.2, age: 40, isMale: true }), rich, LABELS.ko);
    assert.match(richText, /내장지방 레벨 12 \(표준 이상, 표준 1~9\)/);
    assert.match(richText, /인바디 점수 68/);
    assert.match(richText, /체수분 41\.3L/);
});
