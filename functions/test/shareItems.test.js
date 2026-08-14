/**
 * 나눠 먹은 몫 계산 검증.
 *
 * 지켜야 할 계약은 하나다 — **모델은 전체 열량과 사람 수만 주고, 나눗셈은 서버가 한다.**
 * "4명이서 치킨 2마리"가 프롬프트를 세 번 고쳐도 몫(약 1050kcal)에 붙지 않아서
 * 운동 칼로리(exerciseCalc)와 같은 수순으로 산수를 서버가 가져왔다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { divideSharedItems } = require("../shareItems");

/** 치킨 사례 그대로: 4명이서 2마리(전체 4200kcal) */
function chickenData() {
    return {
        calories: { breakfast: 0, lunch: 850, dinner: 5200, snack: 0 },
        meals: {
            lunch: [{ name: "버거 세트 650g", kcal: 850 }],
            dinner: [
                { name: "치킨 2마리 2000g", kcal: 4200, sharedBy: 4 },
                { name: "밥 210g", kcal: 300 }
            ]
        },
        macros: { dinner: { carb: 200, protein: 260, fat: 220 } }
    };
}

test("divideSharedItems - 균등하게 나눈 몫", async (t) => {
    await t.test("전체 ÷ 사람 수. 치킨 2마리 4200을 4로 나누면 1050이다", () => {
        const data = chickenData();
        const logs = divideSharedItems(data);

        const chicken = data.meals.dinner[0];
        assert.equal(chicken.kcal, 1050);
        assert.equal(chicken.name, "치킨 2마리 2000g (1/4)");
        assert.equal(logs.length, 1);
        assert.match(logs[0], /4200 → 1\/4 = 1050/);
    });

    await t.test("나누지 않은 항목은 건드리지 않는다", () => {
        const data = chickenData();
        divideSharedItems(data);

        assert.deepEqual(data.meals.dinner[1], { name: "밥 210g", kcal: 300 });
        assert.deepEqual(data.meals.lunch[0], { name: "버거 세트 650g", kcal: 850 });
        assert.equal(data.calories.lunch, 850);
    });

    await t.test("끼니 합계는 항목 합으로 다시 세운다", () => {
        const data = chickenData();
        divideSharedItems(data);

        // 1050 + 300. 모델이 적어 보낸 5200이 아니다
        assert.equal(data.calories.dinner, 1350);
    });

    await t.test("탄단지는 끼니 열량이 줄어든 비율만큼 깎는다", () => {
        const data = chickenData();
        divideSharedItems(data);

        // 전체 4500 → 몫 1350, 비율 0.3
        assert.equal(data.macros.dinner.carb, 60);
        assert.equal(data.macros.dinner.protein, 78);
        assert.equal(data.macros.dinner.fat, 66);
    });

    await t.test("sharedBy·myShare는 앱으로 나가기 전에 지운다", () => {
        const data = chickenData();
        divideSharedItems(data);

        for (const item of data.meals.dinner) {
            assert.equal("sharedBy" in item, false);
            assert.equal("myShare" in item, false);
        }
    });
});

test("divideSharedItems - 똑같이 나누지 않은 몫", async (t) => {
    await t.test("myShare 비율이 사람 수보다 우선한다", () => {
        const data = {
            meals: { dinner: [{ name: "피자 1판", kcal: 1800, sharedBy: 2, myShare: 0.6 }] }
        };
        divideSharedItems(data);

        assert.equal(data.meals.dinner[0].kcal, 1080);
        assert.equal(data.meals.dinner[0].name, "피자 1판 (60%)");
    });

    await t.test("비율이 (0, 1) 밖이면 버린다", () => {
        const data = {
            meals: { dinner: [{ name: "피자", kcal: 1000, sharedBy: 2, myShare: 1.5 }] }
        };
        divideSharedItems(data);

        // 깨진 비율은 무시하고 사람 수로 균등하게 나눈다
        assert.equal(data.meals.dinner[0].kcal, 500);
        assert.equal(data.meals.dinner[0].name, "피자 (1/2)");
    });
});

test("divideSharedItems - 나눌 것이 없으면 아무것도 안 한다", async (t) => {
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
        const logs = divideSharedItems(data);

        assert.equal(logs.length, 0);
        assert.equal(data.meals.dinner[0].kcal, 300);
        assert.equal(data.meals.dinner[1].kcal, 100);
        assert.equal(data.meals.dinner[2].kcal, 500);
        // 나눈 것이 없으면 모델이 적은 끼니 합계도 그대로 둔다
        assert.equal(data.calories.dinner, 900);
    });

    await t.test("kcal이 0이면 나누지 않는다", () => {
        const data = { meals: { dinner: [{ name: "물", kcal: 0, sharedBy: 4 }] } };
        assert.equal(divideSharedItems(data).length, 0);
        assert.equal(data.meals.dinner[0].kcal, 0);
    });

    await t.test("meals·calories·macros가 없어도 죽지 않는다", () => {
        assert.deepEqual(divideSharedItems({}), []);
        assert.deepEqual(divideSharedItems({ meals: { dinner: "문자열" } }), []);
        const data = { meals: { dinner: [{ name: "치킨", kcal: 2100, sharedBy: 4 }] } };
        assert.equal(divideSharedItems(data).length, 1);
        assert.equal(data.meals.dinner[0].kcal, 525);
    });
});
