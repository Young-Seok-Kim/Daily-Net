/**
 * 하루 섭취 합계(끼니 칼로리·탄단지) 서버 합산 검증.
 *
 * 2026-09-15 같은 입력을 3초 간격으로 두 번 분석했을 때
 *   1) macros.carb가 숫자 아닌 값으로 와 `totalCarb.toFixed is not a function`으로 500,
 *   2) 항목 kcal은 멀쩡한데 calories 칸이 스키마를 벗어나 섭취 합계 0
 * 이 났다. 합계는 항목에서 세고, 모델의 calories·macros는 숫자로 강제해야 한다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { mealCalories, mealMacros } = require("../dailyTotals");

/** 그날 두 번째 요청: 점심 215, 저녁 850 */
function meals() {
    return {
        lunch: [{ name: "실온 닭가슴살 100g", kcal: 110 }, { name: "복숭아 1.5개 300g", kcal: 105 }],
        dinner: [{ name: "밥 210g", kcal: 300 }, { name: "제육 250g", kcal: 550 }]
    };
}

test("mealCalories - 섭취 합계는 항목에서 센다", async (t) => {
    await t.test("정상 응답: 항목 합 = 모델 합, 어긋남 없음", () => {
        const r = mealCalories({ calories: { breakfast: 0, lunch: 215, dinner: 850, snack: 0 }, meals: meals() });
        assert.deepEqual(r.perMeal, { breakfast: 0, lunch: 215, dinner: 850, snack: 0 });
        assert.equal(r.total, 1065);
        assert.deepEqual(r.mismatches, []);
    });

    await t.test("calories가 단위 붙은 문자열이어도 항목 합으로 결산한다 (섭취 0 사례)", () => {
        const r = mealCalories({ calories: { lunch: "215kcal", dinner: "850 kcal" }, meals: meals() });
        assert.equal(r.total, 1065);
        assert.equal(r.mismatches.length, 2);
        assert.match(r.mismatches[0], /lunch: 항목합 215 ≠ 모델 "215kcal"/);
    });

    await t.test("calories가 객체 대신 총합 숫자 하나로 와도 결산은 항목 합이다", () => {
        const r = mealCalories({ calories: 1065, meals: meals() });
        assert.equal(r.total, 1065);
        assert.equal(r.mismatches.length, 2);
    });

    await t.test("calories가 아예 없어도 결산은 항목 합이다", () => {
        const r = mealCalories({ meals: meals() });
        assert.equal(r.total, 1065);
    });

    await t.test("항목 kcal이 문자열이면 접합이 아니라 덧셈이다", () => {
        const r = mealCalories({
            meals: { lunch: [{ name: "a", kcal: "110" }, { name: "b", kcal: "105" }] }
        });
        assert.equal(r.perMeal.lunch, 215);
        assert.equal(r.total, 215);
    });

    await t.test("항목이 없는 끼니만 모델의 calories를 쓴다", () => {
        const r = mealCalories({ calories: { breakfast: 300, lunch: 999, dinner: 850 }, meals: meals() });
        assert.equal(r.perMeal.breakfast, 300);
        assert.equal(r.perMeal.lunch, 215);
        assert.equal(r.total, 300 + 215 + 850);
        assert.deepEqual(r.mismatches, ["lunch: 항목합 215 ≠ 모델 999"]);
    });

    await t.test("모델 값과 1kcal 차이는 어긋남으로 치지 않는다 (반올림 오차)", () => {
        const r = mealCalories({ calories: { lunch: 216 }, meals: { lunch: meals().lunch } });
        assert.deepEqual(r.mismatches, []);
    });

    await t.test("meals도 calories도 없으면 전부 0", () => {
        const r = mealCalories({});
        assert.deepEqual(r.perMeal, { breakfast: 0, lunch: 0, dinner: 0, snack: 0 });
        assert.equal(r.total, 0);
    });
});

test("mealMacros - 탄단지는 무엇이 와도 숫자로 더한다", async (t) => {
    await t.test("정상 응답", () => {
        const r = mealMacros({
            macros: {
                lunch: { carb: 20.25, protein: 25, fat: 3 },
                dinner: { carb: 80, protein: 40.5, fat: 30 }
            }
        });
        assert.deepEqual(r.perMeal.lunch, { carb: 20.3, protein: 25, fat: 3 });
        assert.deepEqual(r.perMeal.breakfast, { carb: 0, protein: 0, fat: 0 });
        assert.deepEqual(r.total, { carb: 100.3, protein: 65.5, fat: 33 });
    });

    await t.test("carb가 문자열이면 접합이 아니라 덧셈이다 (toFixed 500 사례)", () => {
        const r = mealMacros({
            macros: { lunch: { carb: "20", protein: "25", fat: "3" }, dinner: { carb: 80, protein: 40, fat: 30 } }
        });
        assert.equal(r.total.carb, 100);
        assert.equal(typeof r.total.carb, "number");
        assert.equal(r.total.protein, 65);
        assert.equal(r.total.fat, 33);
    });

    await t.test("단위 붙은 문자열·객체·누락은 0", () => {
        const r = mealMacros({
            macros: { lunch: { carb: "20g", protein: { value: 25 } }, dinner: null, snack: "없음" }
        });
        assert.deepEqual(r.total, { carb: 0, protein: 0, fat: 0 });
    });

    await t.test("macros 자체가 없어도 0", () => {
        assert.deepEqual(mealMacros({}).total, { carb: 0, protein: 0, fat: 0 });
    });
});
