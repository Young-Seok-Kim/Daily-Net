/**
 * 식약처 DB 보정 로직 검증.
 *
 * 네트워크를 타지 않는 순수 함수만 다룬다. 조회 자체는 키가 있어야 하므로
 * `scripts/probeFoodDb.js`로 따로 확인한다.
 *
 * 여기서 지켜야 할 계약은 하나다 — **애매하면 손대지 않는다.**
 * 잘못 붙인 제품의 정확한 숫자는 대충 맞는 추정값보다 나쁘다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const {
    normalize,
    similarity,
    pickBest,
    scoreRow,
    correctWithFoodDb,
    bulkUnitOf,
    collectMealNames,
    mergeDuplicateItems,
    MIN_SCORE
} = require("../foodDb");

test("normalize - 검색에 방해되는 것만 걷어낸다", async (t) => {
    await t.test("수량과 단위를 뗀다", () => {
        assert.equal(normalize("스타벅스 아메리카노 톨 1잔"), "스타벅스 아메리카노");
        assert.equal(normalize("밥 1공기"), "밥");
        assert.equal(normalize("닭가슴살 100g"), "닭가슴살");
    });

    await t.test("괄호 안 부연 설명을 뗀다", () => {
        assert.equal(normalize("라떼(무지방)"), "라떼");
    });

    await t.test("한글 수량도 뗀다", () => {
        // 숫자만 처리하면 "밥 한 공기"가 "밥"과 다른 캐시 키가 되어
        // 같은 밥을 매번 새로 조회한다. 조회 한 번이 1~4초라 그대로 지연이 된다.
        assert.equal(normalize("밥 한 공기"), "밥");
        assert.equal(normalize("사과 반개"), "사과");
        assert.equal(normalize("치킨 반 마리"), "치킨");
        assert.equal(normalize("김치 조금"), "김치");
        assert.equal(normalize("비빔밥 곱빼기"), "비빔밥");
        assert.equal(normalize("아메리카노 톨 사이즈"), "아메리카노");
        assert.equal(normalize("삼겹살 200그램"), "삼겹살");
    });

    await t.test("멀쩡한 제품명은 깎지 않는다", () => {
        // 부분 문자열로 지우면 "한우"의 "한", "큰컵라면"의 "큰"이 날아간다
        assert.equal(normalize("한우불고기"), "한우불고기");
        assert.equal(normalize("한우 불고기"), "한우 불고기");
        assert.equal(normalize("반건조오징어"), "반건조오징어");
        assert.equal(normalize("큰컵라면"), "큰컵라면");
        assert.equal(normalize("바나나맛우유"), "바나나맛우유");
    });

    await t.test("빈 입력에도 죽지 않는다", () => {
        assert.equal(normalize(null), "");
        assert.equal(normalize(undefined), "");
    });
});

test("cacheKey - 같은 음식은 같은 키를 쓴다", async (t) => {
    const { cacheKey } = require("../foodDb");

    await t.test("수량이 붙어도 한 칸을 쓴다", () => {
        assert.equal(cacheKey("신라면 1개"), cacheKey("신라면"));
        assert.equal(cacheKey("밥 한 공기"), cacheKey("밥"));
    });

    await t.test("다른 음식은 다른 칸", () => {
        assert.notEqual(cacheKey("신라면"), cacheKey("진라면"));
    });
});

test("similarity - 한쪽이 다른 쪽을 품는 경우를 인정한다", async (t) => {
    await t.test("완전히 같으면 1", () => {
        assert.equal(similarity("아메리카노", "아메리카노"), 1);
    });

    await t.test("남남인 이름은 기준을 못 넘는다", () => {
        assert.ok(similarity("아메리카노", "닭가슴살") < MIN_SCORE);
    });

    await t.test("브랜드가 붙으면 이름만으로는 기준에 못 미친다", () => {
        // 이 사실 때문에 scoreRow가 업체명을 함께 본다 (아래 테스트)
        assert.ok(similarity("스타벅스아메리카노", "아메리카노") < MIN_SCORE);
    });
});

test("scoreRow - 브랜드를 적어서 손해 보지 않게 한다", async (t) => {
    // DB는 이름과 업체를 따로 들고 있다
    const row = { FOOD_NM_KR: "아메리카노", MAKER_NM: "스타벅스커피코리아" };

    await t.test("앞에 붙은 브랜드를 떼고 이름을 맞춘다", () => {
        // 사진에서 브랜드를 읽어 붙이게 해놓고 그것 때문에 탈락시키면 앞뒤가 안 맞는다
        assert.equal(scoreRow(row, "스타벅스 아메리카노"), 1);
    });

    await t.test("브랜드 없이 적어도 그대로 맞는다", () => {
        assert.equal(scoreRow(row, "아메리카노"), 1);
    });

    await t.test("업체명과 무관한 앞말은 떼지 않는다", () => {
        // "아이스"는 업체명이 아니므로 떼면 안 된다. 떼버리면 아무 이름이나 통과한다
        assert.ok(scoreRow(row, "메가커피 아메리카노") < MIN_SCORE);
    });
});

test("pickBest - 확실할 때만 고른다", async (t) => {
    // 실제 API 응답에서 가져온 형태 (코카콜라 조회 결과).
    // 성분값은 SERVING_SIZE(100mL)당 수치이고, Z10500이 포장 총 내용량이다.
    const cola = {
        FOOD_NM_KR: "코카콜라",
        MAKER_NM: "코카콜라음료(주)",
        DB_CLASS_NM: "상용제품",
        SERVING_SIZE: "100mL",
        AMT_NUM1: "43.00",
        AMT_NUM3: "0.00",
        AMT_NUM4: "0.00",
        AMT_NUM6: "10.67",
        NUTRI_AMOUNT_SERVING: "200ml",
        Z10500: "300.000mL",
        DISH_ONE_SERVING: ""
    };
    const rows = [
        { FOOD_NM_KR: "코카콜라맛 사탕", SERVING_SIZE: "100g", AMT_NUM1: "380", Z10500: "50g" },
        cola
    ];

    await t.test("이름이 가장 가까운 행을 고른다", () => {
        const best = pickBest(rows, "코카콜라");
        assert.equal(best.name, "코카콜라");
        assert.equal(best.maker, "코카콜라음료(주)");
    });

    await t.test("100mL당 수치를 포장 총량 기준으로 환산한다", () => {
        const best = pickBest([cola], "코카콜라");
        // 43kcal/100mL × 300mL = 129kcal (한 병을 다 마신 값)
        assert.equal(best.kcal, 129);
        assert.equal(best.carb, 32);
        assert.equal(best.portion, 300);
        assert.equal(best.portionSource, "Z10500");
    });

    await t.test("음식은 1인분량(DISH_ONE_SERVING)을 먼저 본다", () => {
        const gimbap = [{
            FOOD_NM_KR: "김밥", SERVING_SIZE: "100g",
            AMT_NUM1: "150", DISH_ONE_SERVING: "230g", Z10500: ""
        }];
        const best = pickBest(gimbap, "김밥");
        assert.equal(best.kcal, 345); // 150 × 2.3
        assert.equal(best.portionSource, "DISH_ONE_SERVING");
    });

    await t.test("환산할 근거가 없으면 버린다 (100g당 값을 한 끼로 쓸 수 없다)", () => {
        const noPortion = [{ FOOD_NM_KR: "김밥", SERVING_SIZE: "100g", AMT_NUM1: "150" }];
        assert.equal(pickBest(noPortion, "김밥"), null);
    });

    await t.test("업소용 대용량은 개인 섭취량으로 보지 않는다", () => {
        const bulk = [{
            FOOD_NM_KR: "김밥", SERVING_SIZE: "100g",
            AMT_NUM1: "150", Z10500: "20kg"
        }];
        assert.equal(pickBest(bulk, "김밥"), null);
    });

    await t.test("나눠 먹는 포장은 1회 참고량을 쓴다", () => {
        // 오예스 360g은 12개들이다. 총량으로 계산하면 1,800kcal이 나온다
        const oyes = [{
            FOOD_NM_KR: "오예스", SERVING_SIZE: "100g",
            AMT_NUM1: "500", Z10500: "360g", NUTRI_AMOUNT_SERVING: "30g"
        }];
        const best = pickBest(oyes, "오예스");
        assert.equal(best.kcal, 150);  // 500 × 0.3
        assert.equal(best.portionSource, "NUTRI_AMOUNT_SERVING");
    });

    await t.test("한 번에 먹는 포장은 총량을 쓴다", () => {
        // 콜라 300mL(참고량 200mL)는 1.5배라 한 번에 다 마신다
        const cola300 = [{
            FOOD_NM_KR: "코카콜라", SERVING_SIZE: "100mL",
            AMT_NUM1: "43", Z10500: "300mL", NUTRI_AMOUNT_SERVING: "200mL"
        }];
        assert.equal(pickBest(cola300, "코카콜라").portionSource, "Z10500");
    });

    await t.test("1g짜리 행은 버린다", () => {
        // 방울토마토를 한 알 단위로 적어둔 행이 있다. 그대로 쓰면 2kcal이 된다
        const tiny = [{
            FOOD_NM_KR: "방울토마토", SERVING_SIZE: "100g",
            AMT_NUM1: "16", Z10500: "1g"
        }];
        assert.equal(pickBest(tiny, "방울토마토"), null);
    });

    await t.test("옛 식품안전나라 필드명으로 와도 읽는다", () => {
        const legacy = [{
            DESC_KOR: "김밥", MAKER_NAME: "김밥천국",
            SERVING_WT: "100g", NUTR_CONT1: "150", Z10500: "230g"
        }];
        const best = pickBest(legacy, "김밥");
        assert.equal(best.name, "김밥");
        assert.equal(best.kcal, 345);
    });

    await t.test("기준에 못 미치면 null (엉뚱한 걸 붙이느니 포기한다)", () => {
        assert.equal(pickBest(rows, "제육볶음"), null);
    });

    // DB에는 사람들이 부르는 이름이 없는 경우가 많다.
    // "자갈치"는 없고 `자갈치문어맛`(농심)으로 등록돼 있다.
    await t.test("계열 제품이 하나뿐이면 그것을 쓴다", () => {
        const snack = [{
            FOOD_NM_KR: "자갈치문어맛", MAKER_NM: "㈜농심",
            SERVING_SIZE: "100g", AMT_NUM1: "522", Z10500: "90.00g", NUTRI_AMOUNT_SERVING: "30g"
        }];
        const best = pickBest(snack, "자갈치");
        assert.equal(best.kcal, 470);   // 522 × 0.9
        assert.equal(best.variant, true);
    });

    await t.test("계열이 여러 갈래면 포기한다", () => {
        // "자갈치"에는 농심 과자(90g)와 해도식품 어묵(1kg)이 같이 걸린다.
        // 과자를 노렸는데 어묵 1kg이 붙으면 큰일 난다.
        const mixed = [
            { FOOD_NM_KR: "자갈치문어맛", SERVING_SIZE: "100g", AMT_NUM1: "522", Z10500: "90g" },
            { FOOD_NM_KR: "자갈치어묵", SERVING_SIZE: "100g", AMT_NUM1: "223", Z10500: "450g" }
        ];
        assert.equal(pickBest(mixed, "자갈치"), null);
    });

    await t.test("브랜드를 적으면 갈래가 좁혀져 찾아낸다", () => {
        const mixed = [
            {
                FOOD_NM_KR: "자갈치문어맛", MAKER_NM: "㈜농심",
                SERVING_SIZE: "100g", AMT_NUM1: "522", Z10500: "90g"
            },
            {
                FOOD_NM_KR: "자갈치어묵", MAKER_NM: "(주)해도식품",
                SERVING_SIZE: "100g", AMT_NUM1: "223", Z10500: "450g"
            }
        ];
        const best = pickBest(mixed, "농심 자갈치");
        assert.equal(best.name, "자갈치문어맛");
        assert.equal(best.kcal, 470);
    });

    await t.test("열량이 0인 행은 버린다 (성분 미입력 데이터)", () => {
        const empty = [{ FOOD_NM_KR: "김밥", SERVING_SIZE: "100g", AMT_NUM1: "0", Z10500: "230g" }];
        assert.equal(pickBest(empty, "김밥"), null);
    });

    await t.test("값이 상식 밖이면 버린다 (필드 자리를 잘못 읽은 경우)", () => {
        // 열량 자리에서 나트륨(mg)을 읽으면 이런 값이 나온다
        const wrong = [{ FOOD_NM_KR: "김밥", SERVING_SIZE: "100g", AMT_NUM1: "58000", Z10500: "230g" }];
        assert.equal(pickBest(wrong, "김밥"), null);
    });
});

test("bulkUnitOf - 덩어리 단위만 골라낸다", async (t) => {
    await t.test("숫자나 수사가 앞에 붙은 마리·판·통", () => {
        assert.equal(bulkUnitOf("닭강정 1마리"), "마리");
        assert.equal(bulkUnitOf("피자 1판"), "판");
        assert.equal(bulkUnitOf("치킨 한마리"), "마리");
        assert.equal(bulkUnitOf("아이스크림 2통"), "통");
    });

    await t.test("이름에 그냥 들어간 글자는 아니다", () => {
        // 이걸 안 가리면 통닭·통밀빵이 전부 보정에서 빠진다
        assert.equal(bulkUnitOf("통닭"), null);
        assert.equal(bulkUnitOf("통밀빵"), null);
        assert.equal(bulkUnitOf("판모밀"), null);
    });

    await t.test("크기가 대충 맞는 단위는 그대로 둔다", () => {
        // 콜라 1캔은 DB의 1캔이고 새우깡 1봉지는 DB의 1봉지다. 개수만 곱하면 된다
        assert.equal(bulkUnitOf("코카콜라 2캔"), null);
        assert.equal(bulkUnitOf("새우깡 3봉지"), null);
        assert.equal(bulkUnitOf("잡곡밥 1공기"), null);
    });
});

test("correctWithFoodDb - 칼로리를 바로잡고 탄단지를 따라 맞춘다", async (t) => {
    const build = () => ({
        calories: { breakfast: 500, lunch: 0, dinner: 0, snack: 0, exercise: 0 },
        meals: {
            breakfast: [
                { name: "코카콜라", kcal: 200 },
                { name: "식빵", kcal: 300 }
            ],
            lunch: [], dinner: [], snack: []
        },
        macros: {
            breakfast: { carb: 100, protein: 10, fat: 5 },
            lunch: { carb: 0, protein: 0, fat: 0 },
            dinner: { carb: 0, protein: 0, fat: 0 },
            snack: { carb: 0, protein: 0, fat: 0 }
        }
    });

    await t.test("DB 값으로 바꾸고 끼니 합계를 다시 낸다", () => {
        const data = build();
        const found = new Map([["코카콜라", { name: "코카콜라", kcal: 150, carb: 39, protein: 0, fat: 0 }]]);

        const applied = correctWithFoodDb(data, found);

        assert.equal(data.meals.breakfast[0].kcal, 150);
        assert.equal(data.meals.breakfast[0].source, "mfds");
        // 안 고쳐진 항목은 그대로
        assert.equal(data.meals.breakfast[1].kcal, 300);
        // 합계 = 150 + 300
        assert.equal(data.calories.breakfast, 450);
        assert.equal(applied.length, 1);
    });

    await t.test("탄단지도 칼로리가 줄어든 비율만큼 줄인다", () => {
        const data = build();
        const found = new Map([["코카콜라", { name: "코카콜라", kcal: 150 }]]);

        correctWithFoodDb(data, found);

        // 500 → 450 이므로 0.9배
        assert.equal(data.macros.breakfast.carb, 90);
        assert.equal(data.macros.breakfast.protein, 9);
        assert.equal(data.macros.breakfast.fat, 4.5);
    });

    await t.test("적은 개수만큼 DB 값을 곱한다", () => {
        // DB는 1캔 기준이다. "2캔"이면 두 배를 넣어야 한다.
        // 이걸 빼먹으면 두 캔 마시고 한 캔치 칼로리가 들어간다.
        const data = {
            calories: { breakfast: 300 },
            meals: { breakfast: [{ name: "코카콜라 2캔", kcal: 280 }] },
            macros: { breakfast: { carb: 70, protein: 0, fat: 0 } }
        };
        const found = new Map([["코카콜라 2캔", { name: "코카콜라", kcal: 129 }]]);

        const applied = correctWithFoodDb(data, found);

        assert.equal(data.meals.breakfast[0].kcal, 258); // 129 × 2
        assert.equal(applied[0].qty, 2);
    });

    await t.test("개수를 곱하지 않으면 걸러졌을 것까지 살린다", () => {
        // 곱하기 전이라면 129/280 = 0.46 으로 2배 기준에 걸려 통째로 건너뛰었다.
        // 개수를 반영하면 258/280 = 0.92 라 정상 보정된다.
        const data = {
            calories: { breakfast: 280 },
            meals: { breakfast: [{ name: "코카콜라 2캔", kcal: 280 }] },
            macros: { breakfast: { carb: 70, protein: 0, fat: 0 } }
        };
        const applied = correctWithFoodDb(
            data,
            new Map([["코카콜라 2캔", { name: "코카콜라", kcal: 129 }]])
        );
        assert.equal(applied.length, 1);
    });

    await t.test("마리·판·통이 붙으면 보정하지 않는다", () => {
        // 실제로 겪은 사례 (2026-08-05 22:59). 닭강정 1마리는 800g쯤인데
        // DB가 아는 닭강정은 184g들이 포장 하나라 403kcal이다.
        // 개수(1)를 곱해봐야 403 그대로다. 곱할 단위 자체가 틀렸다.
        const data = {
            calories: { dinner: 2200 },
            meals: { dinner: [{ name: "닭강정 1마리", kcal: 2200 }] },
            macros: { dinner: { carb: 150, protein: 120, fat: 100 } }
        };
        const applied = correctWithFoodDb(
            data,
            new Map([["닭강정 1마리", { name: "닭강정", kcal: 403, portion: 184 }]])
        );

        assert.equal(applied.length, 0);
        assert.equal(data.meals.dinner[0].kcal, 2200, "모델 추정을 그대로 둬야 한다");
        assert.equal(data.meals.dinner[0].source, undefined);
        // 안 고쳤으면 탄단지도 건드리면 안 된다
        assert.equal(data.macros.dinner.carb, 150);
    });

    await t.test("그램이 적혀 있으면 마리가 붙어도 보정한다", () => {
        // "800g"은 추정이 아니라 읽은 사실이다. 그 크기로 환산하면 믿을 수 있다.
        const data = {
            calories: { dinner: 2200 },
            meals: { dinner: [{ name: "닭강정 1마리 800g", kcal: 2200 }] },
            macros: { dinner: { carb: 150, protein: 120, fat: 100 } }
        };
        const applied = correctWithFoodDb(
            data,
            new Map([["닭강정 1마리 800g", { name: "닭강정", kcal: 403, portion: 184 }]])
        );

        assert.equal(applied.length, 1);
        // 403 × (800 / 184) = 1752
        assert.equal(data.meals.dinner[0].kcal, 1752);
    });

    await t.test("무게·부피는 개수가 아니다", () => {
        // "500ml"는 그 제품의 크기지 다섯 개가 아니다
        const data = {
            calories: { breakfast: 200 },
            meals: { breakfast: [{ name: "코카콜라 500ml", kcal: 200 }] },
            macros: { breakfast: { carb: 50, protein: 0, fat: 0 } }
        };
        const applied = correctWithFoodDb(
            data,
            new Map([["코카콜라 500ml", { name: "코카콜라", kcal: 129 }]])
        );
        assert.equal(applied[0].qty, 1);
        assert.equal(data.meals.breakfast[0].kcal, 129);
    });

    await t.test("개수가 많아도 한 개끼리 비교해 보정한다", () => {
        // 실제로 겪은 사례. 새우깡 한 봉지는 465kcal인데 모델은 150쯤으로 기억한다.
        // 총량(1395 vs 450)으로 비교하면 3.1배라 가드에 걸려 안 고쳐졌다.
        // 한 개끼리(465 vs 150) 비교해도 3.1배지만, 가드가 4배라 통과한다.
        const data = {
            calories: { snack: 450 },
            meals: { snack: [{ name: "새우깡 3개", kcal: 450 }] },
            macros: { snack: { carb: 50, protein: 5, fat: 25 } }
        };
        const applied = correctWithFoodDb(
            data,
            new Map([["새우깡 3개", { name: "새우깡", kcal: 465 }]])
        );

        assert.equal(applied.length, 1);
        assert.equal(applied[0].qty, 3);
        assert.equal(data.meals.snack[0].kcal, 1395);
    });

    await t.test("아주 동떨어진 값은 여전히 손대지 않는다", () => {
        const data = build();
        // 모델 200 vs DB 10 → 20배. 이쯤 되면 엉뚱한 게 붙은 것으로 본다.
        // (9배쯤은 통과시킨다 — 새우깡 3봉지에서 AI가 그만큼 틀린 적이 있다)
        const found = new Map([["코카콜라", { name: "코카콜라", kcal: 10 }]]);

        const applied = correctWithFoodDb(data, found);

        assert.equal(data.meals.breakfast[0].kcal, 200);
        assert.equal(applied.length, 0);
    });

    await t.test("찾은 게 없으면 아무것도 바꾸지 않는다", () => {
        const data = build();
        const before = JSON.stringify(data);

        assert.deepEqual(correctWithFoodDb(data, new Map()), []);
        assert.equal(JSON.stringify(data), before);
    });

    await t.test("meals가 통째로 없어도 죽지 않는다", () => {
        assert.deepEqual(correctWithFoodDb({}, new Map([["x", { kcal: 1 }]])), []);
    });
});

test("mergeDuplicateItems - 쪼개진 같은 메뉴를 한 줄로", async (t) => {
    await t.test("같은 이름이 여러 줄이면 합치고 개수를 표시한다", () => {
        // 모델이 "코카콜라 2개"를 두 항목으로 나눠 돌려준 상황
        const data = {
            meals: { breakfast: [{ name: "코카콜라", kcal: 129 }, { name: "코카콜라", kcal: 129 }] }
        };
        const touched = mergeDuplicateItems(data);

        assert.equal(data.meals.breakfast.length, 1);
        assert.equal(data.meals.breakfast[0].name, "코카콜라 ×2");
        assert.equal(data.meals.breakfast[0].kcal, 258);
        assert.deepEqual(touched, ["breakfast"]);
    });

    await t.test("수량 표기가 달라도 같은 메뉴면 합친다", () => {
        const data = {
            meals: { lunch: [{ name: "신라면", kcal: 500 }, { name: "신라면 1개", kcal: 500 }] }
        };
        mergeDuplicateItems(data);
        assert.equal(data.meals.lunch.length, 1);
        assert.equal(data.meals.lunch[0].kcal, 1000);
    });

    await t.test("다른 메뉴는 합치지 않는다", () => {
        const data = {
            meals: { dinner: [{ name: "코카콜라", kcal: 129 }, { name: "코카콜라 제로", kcal: 0 }] }
        };
        assert.deepEqual(mergeDuplicateItems(data), []);
        assert.equal(data.meals.dinner.length, 2);
    });

    await t.test("합칠 게 없으면 이름을 건드리지 않는다", () => {
        const data = { meals: { snack: [{ name: "사과", kcal: 50 }] } };
        mergeDuplicateItems(data);
        assert.equal(data.meals.snack[0].name, "사과");
    });

    await t.test("meals가 없어도 죽지 않는다", () => {
        assert.deepEqual(mergeDuplicateItems({}), []);
    });
});

test("splitCategoryHint - 종류를 적으면 그걸로 좁힌다", async (t) => {
    const { splitCategoryHint } = require("../foodDb");

    await t.test("종류어를 떼어내고 검색어와 분리한다", () => {
        // "자갈치 과자"로 검색하면 0건이 난다. 떼어내고 거르는 데만 써야 한다
        assert.deepEqual(splitCategoryHint("자갈치 과자"), { hint: ["과자", "빵"], core: "자갈치" });
        assert.deepEqual(splitCategoryHint("과자 자갈치"), { hint: ["과자", "빵"], core: "자갈치" });
        assert.deepEqual(splitCategoryHint("코카콜라 음료"), { hint: ["음료"], core: "코카콜라" });
    });

    await t.test("종류어가 없으면 그대로 둔다", () => {
        assert.deepEqual(splitCategoryHint("신라면"), { hint: null, core: "신라면" });
    });

    await t.test("이름 자체가 종류어면 떼지 않는다", () => {
        // "빵"만 적었으면 그게 검색어다. 떼면 검색할 게 없어진다
        assert.deepEqual(splitCategoryHint("빵"), { hint: null, core: "빵" });
    });
});

test("pickBest + 종류 - 브랜드를 몰라도 좁혀진다", async (t) => {
    // 실제 DB 형태. 같은 "자갈치"인데 대분류가 갈린다
    const rows = [
        {
            FOOD_NM_KR: "자갈치문어맛", MAKER_NM: "㈜농심", FOOD_CAT1_NM: "과자류·빵류 또는 떡류",
            SERVING_SIZE: "100g", AMT_NUM1: "522", Z10500: "90g", NUTRI_AMOUNT_SERVING: "30g"
        },
        {
            // 실제 DB의 어묵은 업소용 1kg 포장이다. 과자(90g)와 크기가 열 배 넘게 차이 난다
            FOOD_NM_KR: "자갈치어묵", MAKER_NM: "(주)해도식품", FOOD_CAT1_NM: "수산가공식품류",
            SERVING_SIZE: "100g", AMT_NUM1: "223", Z10500: "450g"
        }
    ];

    await t.test("종류를 적으면 그 갈래만 본다", () => {
        assert.equal(pickBest(rows, "자갈치", ["과자", "빵"]).name, "자갈치문어맛");
        // 어묵은 1kg 포장이라 한 끼로 볼 수 없어 걸러진다
        assert.equal(pickBest(rows, "자갈치", ["수산"]).name, "자갈치어묵");
    });

    await t.test("종류를 안 적으면 갈래가 섞여 포기한다", () => {
        assert.equal(pickBest(rows, "자갈치"), null);
    });

    await t.test("그 갈래에 아무것도 없으면 원래 후보로 판단한다", () => {
        // 엉뚱한 분류를 줬다고 조회 자체를 막지는 않는다
        assert.equal(pickBest(rows, "자갈치", ["빙과"]), null);
    });
});

test("collectMealNames - 네 끼니의 메뉴명을 모은다", () => {
    const data = {
        meals: {
            breakfast: [{ name: "밥" }, { name: "국" }],
            lunch: [{ name: "제육볶음" }],
            dinner: null,
            snack: [{ kcal: 10 }]
        }
    };
    assert.deepEqual(collectMealNames(data), ["밥", "국", "제육볶음"]);
});
