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

const { normalizeInbody, resolveBmr, inbodyPromptLine } = require("../inbody");
const { calculateBmr } = require("../nutrition");

test("범위 밖 값은 0으로, 전부 비면 null", () => {
    const ok = normalizeInbody({ bmr: "1650.4", skeletalMuscle: 34.26, bodyFat: 22.5, bodyFatPercent: 24.49, weight: 92.2, measuredAt: "2026-10-01" });
    assert.deepEqual(ok, { bmr: 1650, skeletalMuscle: 34.3, bodyFat: 22.5, bodyFatPercent: 24.5, weight: 92.2, measuredAt: "2026-10-01" });

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
