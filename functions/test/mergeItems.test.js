/**
 * 쪼개져 나온 같은 메뉴 합치기 검증.
 *
 * 원래 foodDb.test.js에 있던 것 중 **살아남은 코드를 검사하는 부분만** 옮겨왔다.
 * 나머지(매칭·환산·보정)는 코드와 함께 지웠다 — 안 쓰는 코드를 검사하는 초록불은
 * 아무것도 보장하지 않으면서 고칠 때마다 손이 간다.
 *
 * 여기서 지켜야 할 계약은 하나다 — **다른 것을 합치지 않는다.**
 * 안 합쳐서 두 줄로 보이는 건 눈에 거슬릴 뿐이지만, 잘못 합치면 열량이 틀린다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { normalize, mergeDuplicateItems } = require("../mergeItems");

test("normalize - 수량 표기만 걷어낸다", async (t) => {
    await t.test("수량과 단위를 뗀다", () => {
        assert.equal(normalize("스타벅스 아메리카노 톨 1잔"), "스타벅스 아메리카노");
        assert.equal(normalize("밥 1공기"), "밥");
        assert.equal(normalize("닭가슴살 100g"), "닭가슴살");
    });

    await t.test("괄호 안 부연 설명을 뗀다", () => {
        assert.equal(normalize("라떼(무지방)"), "라떼");
    });

    await t.test("한글 수량도 뗀다", () => {
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
