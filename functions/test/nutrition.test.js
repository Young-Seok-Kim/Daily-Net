/**
 * 권장 섭취량 계산 검증.
 *
 * 이 파일이 존재하는 이유는 b25에서 실제로 틀렸기 때문이다.
 * 권장 칼로리의 40%를 단백질로 배분해서, 체중을 전혀 보지 않고 2000kcal면
 * 누구나 200g이 나왔다 (70kg 기준 2.9g/kg). 화면은 멀쩡해 보였고 아무도 몰랐다.
 *
 * ⚠️ 앱의 WeekSummary.kt에 같은 공식이 복제되어 있다.
 *    여기 기대값을 바꾸면 그쪽도 반드시 함께 고쳐야 한다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { calculateBmr, recommendedIntake } = require("../nutrition");

test("기초대사량 - Mifflin-St Jeor 공식", async (t) => {
    await t.test("남성 70kg / 175cm / 30세", () => {
        // 10*70 + 6.25*175 - 5*30 + 5 = 1648.75 → 1649
        assert.equal(calculateBmr(70, 175, 30, true), 1649);
    });

    await t.test("여성은 같은 조건에서 166 낮다 (+5 대신 -161)", () => {
        assert.equal(calculateBmr(70, 175, 30, false), 1483);
        assert.equal(
            calculateBmr(70, 175, 30, true) - calculateBmr(70, 175, 30, false),
            166
        );
    });

    await t.test("나이가 많을수록 낮아진다", () => {
        assert.ok(calculateBmr(70, 175, 50, true) < calculateBmr(70, 175, 30, true));
    });
});

test("권장 섭취량", async (t) => {
    await t.test("CHANGELOG에 적힌 기준 예시 (70kg 남성, BMR 1649)", () => {
        // v1.7.7 상세 내역: 탄 220g · 단 112g · 지 49g
        const r = recommendedIntake(1649, 70);
        assert.deepEqual(r, { calories: 1767, carb: 220, protein: 112, fat: 49 });
    });

    await t.test("단백질은 칼로리가 아니라 체중을 따른다 (b25에서 틀렸던 부분)", () => {
        // 같은 BMR인데 체중만 다르면 단백질이 달라져야 한다.
        // 예전 공식(칼로리의 40%)이었다면 둘이 같은 값이 나온다.
        const light = recommendedIntake(1649, 50);
        const heavy = recommendedIntake(1649, 90);

        assert.notEqual(light.protein, heavy.protein);
        assert.equal(light.protein, 80);  // 50 * 1.6
        assert.equal(heavy.protein, 144); // 90 * 1.6
    });

    await t.test("단백질은 체중 1kg당 1.6g이다", () => {
        for (const kg of [45, 60, 70, 85, 100]) {
            assert.equal(recommendedIntake(1600, kg).protein, Math.round(kg * 1.6));
        }
    });

    await t.test("탄단지를 칼로리로 환산한 합이 권장 칼로리와 맞는다", () => {
        // 탄수화물을 마지막에 남은 칼로리로 채우는 이유가 이것이다.
        // 반올림 때문에 정확히 같지는 않으므로 오차 범위를 둔다.
        for (const [bmr, kg] of [[1400, 50], [1649, 70], [1900, 85], [2100, 100]]) {
            const r = recommendedIntake(bmr, kg);
            const sum = r.carb * 4 + r.protein * 4 + r.fat * 9;
            assert.ok(
                Math.abs(sum - r.calories) <= 10,
                `BMR ${bmr} / ${kg}kg: 합 ${sum} vs 권장 ${r.calories}`
            );
        }
    });

    await t.test("지방은 권장 칼로리의 25%를 차지한다", () => {
        const r = recommendedIntake(1649, 70);
        assert.equal(r.fat, Math.round((r.calories * 0.25) / 9));
    });

    await t.test("감량분 500kcal이 빠져 있다", () => {
        const r = recommendedIntake(1600, 70);
        assert.equal(r.calories, Math.round(1600 * 1.375) - 500);
    });
});

test("권장 섭취량 - 극단값에서도 깨지지 않는다", async (t) => {
    await t.test("체중이 매우 크면 탄수화물이 음수가 아니라 0이 된다", () => {
        // 단백질·지방만으로 권장 칼로리를 넘기는 경우
        const r = recommendedIntake(1200, 200);
        assert.ok(r.carb >= 0, `탄수화물이 음수다: ${r.carb}`);
        assert.equal(r.carb, 0);
    });

    await t.test("체중이 없거나 잘못된 값이면 단백질 0으로 떨어진다 (예외를 던지지 않는다)", () => {
        // 분석 자체가 실패하는 것보다 낫다
        for (const bad of [undefined, null, "", "abc", NaN]) {
            const r = recommendedIntake(1649, bad);
            assert.equal(r.protein, 0);
            assert.ok(Number.isFinite(r.carb));
        }
    });

    await t.test("문자열로 들어온 체중도 숫자로 다룬다", () => {
        assert.equal(recommendedIntake(1649, "70").protein, 112);
    });
});
