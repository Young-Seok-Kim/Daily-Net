/**
 * 항목 열량 서버 계산(곱셈·나눗셈) 검증.
 *
 * 지켜야 할 계약은 하나다 — **모델은 한 단위 열량·개수·사람 수만 주고, 산수는 서버가 한다.**
 * "4명이서 치킨 2마리"가 프롬프트를 세 번 고쳐도 몫(약 1050kcal)에 붙지 않았고,
 * 나눗셈을 서버로 옮기자 이번에는 곱셈이 빠졌다(1마리 2100인데 2마리 전체가 2400).
 * 운동 칼로리(exerciseCalc)와 같은 수순으로 산수를 전부 서버가 가져왔다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { settleItemKcal } = require("../shareItems");

/** 치킨 사례 그대로: 4명이서 2마리(한 마리 2100kcal). 모델의 kcal 칸은 곱셈이 빠진 2400 */
function chickenData() {
    return {
        calories: { breakfast: 0, lunch: 850, dinner: 2700, snack: 0 },
        meals: {
            lunch: [{ name: "버거 세트 650g", kcal: 850 }],
            dinner: [
                { name: "치킨 2마리 2000g", kcal: 2400, units: 2, unitKcal: 2100, sharedBy: 4 },
                { name: "밥 210g", kcal: 300 }
            ]
        },
        macros: { dinner: { carb: 200, protein: 260, fat: 220 } }
    };
}

test("settleItemKcal - 곱하고 나눈다", async (t) => {
    await t.test("전체 = 한 단위 × 개수, 몫 = 전체 ÷ 사람 수", () => {
        const data = chickenData();
        const logs = settleItemKcal(data);

        const chicken = data.meals.dinner[0];
        // 2100 × 2 = 4200, ÷4 = 1050. 모델이 kcal 칸에 적은 2400은 버린다
        assert.equal(chicken.kcal, 1050);
        assert.equal(chicken.name, "치킨 2마리 2000g (1/4)");
        assert.equal(logs.length, 1);
        assert.match(logs[0], /2400 → 2100 ×2 = 4200 → 1\/4 = 1050/);
    });

    await t.test("개수만 있고 나눠 먹지 않았으면 곱셈만 한다", () => {
        const data = {
            meals: { dinner: [{ name: "치킨 2마리 2000g", kcal: 2400, units: 2, unitKcal: 2100 }] }
        };
        settleItemKcal(data);

        assert.equal(data.meals.dinner[0].kcal, 4200);
        assert.equal(data.meals.dinner[0].name, "치킨 2마리 2000g");
    });

    await t.test("개수 정보가 없으면 kcal 칸의 전체를 그대로 나눈다", () => {
        const data = {
            meals: { dinner: [{ name: "치킨 2마리 2000g", kcal: 4200, sharedBy: 4 }] }
        };
        settleItemKcal(data);

        assert.equal(data.meals.dinner[0].kcal, 1050);
        assert.equal(data.meals.dinner[0].name, "치킨 2마리 2000g (1/4)");
    });

    await t.test("0.5개처럼 개수가 소수여도 곱한다", () => {
        const data = {
            meals: { snack: [{ name: "도넛 0.5개", kcal: 300, units: 0.5, unitKcal: 280 }] }
        };
        settleItemKcal(data);

        assert.equal(data.meals.snack[0].kcal, 140);
    });

    await t.test("계산이 이미 맞으면 로그도 없다", () => {
        const data = {
            meals: { dinner: [{ name: "치킨 2마리", kcal: 4200, units: 2, unitKcal: 2100 }] }
        };
        assert.equal(settleItemKcal(data).length, 0);
        assert.equal(data.meals.dinner[0].kcal, 4200);
    });
});

test("settleItemKcal - 끼니 보정과 필드 정리", async (t) => {
    await t.test("나누지 않은 항목은 건드리지 않는다", () => {
        const data = chickenData();
        settleItemKcal(data);

        assert.deepEqual(data.meals.dinner[1], { name: "밥 210g", kcal: 300 });
        assert.deepEqual(data.meals.lunch[0], { name: "버거 세트 650g", kcal: 850 });
        assert.equal(data.calories.lunch, 850);
    });

    await t.test("값이 바뀐 끼니의 합계는 항목 합으로 다시 세운다", () => {
        const data = chickenData();
        settleItemKcal(data);

        // 1050 + 300. 모델이 적어 보낸 2700이 아니다
        assert.equal(data.calories.dinner, 1350);
    });

    await t.test("탄단지는 끼니 열량이 바뀐 비율만큼 움직인다", () => {
        const data = chickenData();
        settleItemKcal(data);

        // 모델 기준 2700 → 서버 계산 1350, 비율 0.5
        assert.equal(data.macros.dinner.carb, 100);
        assert.equal(data.macros.dinner.protein, 130);
        assert.equal(data.macros.dinner.fat, 110);
    });

    await t.test("판단 필드는 앱으로 나가기 전에 지운다", () => {
        const data = chickenData();
        settleItemKcal(data);

        for (const item of data.meals.dinner) {
            for (const key of ["units", "unitKcal", "sharedBy", "myShare"]) {
                assert.equal(key in item, false);
            }
        }
    });
});

test("settleItemKcal - 똑같이 나누지 않은 몫", async (t) => {
    await t.test("myShare 비율이 사람 수보다 우선한다", () => {
        const data = {
            meals: { dinner: [{ name: "피자 1판", kcal: 1800, sharedBy: 2, myShare: 0.6 }] }
        };
        settleItemKcal(data);

        assert.equal(data.meals.dinner[0].kcal, 1080);
        assert.equal(data.meals.dinner[0].name, "피자 1판 (60%)");
    });

    await t.test("비율이 (0, 1) 밖이면 버린다", () => {
        const data = {
            meals: { dinner: [{ name: "피자", kcal: 1000, sharedBy: 2, myShare: 1.5 }] }
        };
        settleItemKcal(data);

        // 깨진 비율은 무시하고 사람 수로 균등하게 나눈다
        assert.equal(data.meals.dinner[0].kcal, 500);
        assert.equal(data.meals.dinner[0].name, "피자 (1/2)");
    });
});

test("settleItemKcal - 계산할 것이 없으면 아무것도 안 한다", async (t) => {
    await t.test("sharedBy가 없거나 0·1이면 그대로 둔다", () => {
        const data = {
            calories: { dinner: 900 },
            meals: {
                dinner: [
                    { name: "밥", kcal: 300 },
                    { name: "국", kcal: 100, sharedBy: 0 },
                    { name: "고기", kcal: 500, sharedBy: 1 }
                ]
            }
        };
        const logs = settleItemKcal(data);

        assert.equal(logs.length, 0);
        assert.equal(data.meals.dinner[0].kcal, 300);
        assert.equal(data.meals.dinner[1].kcal, 100);
        assert.equal(data.meals.dinner[2].kcal, 500);
        // 계산한 것이 없으면 모델이 적은 끼니 합계도 그대로 둔다
        assert.equal(data.calories.dinner, 900);
    });

    await t.test("kcal이 0이면 나누지 않는다", () => {
        const data = { meals: { dinner: [{ name: "물", kcal: 0, sharedBy: 4 }] } };
        assert.equal(settleItemKcal(data).length, 0);
        assert.equal(data.meals.dinner[0].kcal, 0);
    });

    await t.test("meals·calories·macros가 없어도 죽지 않는다", () => {
        assert.deepEqual(settleItemKcal({}), []);
        assert.deepEqual(settleItemKcal({ meals: { dinner: "문자열" } }), []);
        const data = { meals: { dinner: [{ name: "치킨", kcal: 2100, sharedBy: 4 }] } };
        assert.equal(settleItemKcal(data).length, 1);
        assert.equal(data.meals.dinner[0].kcal, 525);
    });
});
