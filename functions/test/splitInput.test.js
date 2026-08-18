/**
 * 콤마 입력을 항목으로 나누기 검증.
 *
 * 지켜야 할 계약은 하나다 — **경계가 아닌 콤마를 자르지 않는다.**
 * 항목이 안 나뉘면 모델이 예전처럼 통짜로 추측할 뿐이지만,
 * 자릿수 콤마나 괄호 안을 자르면 없던 항목이 생겨 열량이 틀린다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { splitItems, numberedLines } = require("../splitInput");

test("splitItems - 경계인 콤마만 자른다", async (t) => {
    await t.test("콤마로 항목을 나눈다", () => {
        assert.deepEqual(
            splitItems("김치찌개, 밥 한공기, 콜라"),
            ["김치찌개", "밥 한공기", "콜라"]
        );
    });

    await t.test("공백 없이 붙여 써도 나눈다", () => {
        assert.deepEqual(splitItems("사과1,배2"), ["사과1", "배2"]);
    });

    await t.test("숫자 사이 콤마는 자릿수 표기라 안 자른다", () => {
        assert.deepEqual(splitItems("아이스티 1,000ml"), ["아이스티 1,000ml"]);
        assert.deepEqual(
            splitItems("아이스티 1,000ml, 밥"),
            ["아이스티 1,000ml", "밥"]
        );
    });

    await t.test("괄호 안 콤마는 부연 설명이라 안 자른다", () => {
        assert.deepEqual(
            splitItems("반반치킨 (양념, 후라이드), 콜라"),
            ["반반치킨 (양념, 후라이드)", "콜라"]
        );
        // 사진 추출이 만드는 "개당" 표기도 괄호째로 보존돼야 한다
        assert.deepEqual(
            splitItems("오예스 3개 (개당 30g 171kcal), 우유 200ml"),
            ["오예스 3개 (개당 30g 171kcal)", "우유 200ml"]
        );
    });

    await t.test("전각 콤마도 경계다", () => {
        assert.deepEqual(splitItems("밥，김치、콜라"), ["밥", "김치", "콜라"]);
    });

    await t.test("줄바꿈도 경계다", () => {
        assert.deepEqual(splitItems("밥\n김치\r\n콜라"), ["밥", "김치", "콜라"]);
    });

    await t.test("안 닫힌 괄호가 뒷줄까지 삼키지 않는다", () => {
        assert.deepEqual(
            splitItems("치킨 (양념\n밥, 김치"),
            ["치킨 (양념", "밥", "김치"]
        );
    });

    await t.test("빈 조각은 버린다", () => {
        assert.deepEqual(splitItems("밥,, 김치, "), ["밥", "김치"]);
        assert.deepEqual(splitItems(""), []);
        assert.deepEqual(splitItems(null), []);
        assert.deepEqual(splitItems(undefined), []);
        assert.deepEqual(splitItems("  ,  "), []);
    });

    await t.test("콤마 없는 한 항목은 그대로", () => {
        assert.deepEqual(splitItems("제육볶음 1인분"), ["제육볶음 1인분"]);
    });
});

test("numberedLines - 프롬프트에 넣을 줄", async (t) => {
    await t.test("번호를 붙여 줄로 만든다", () => {
        assert.equal(numberedLines("밥, 김치"), "1. 밥\n2. 김치");
    });

    await t.test("들여쓰기를 각 줄에 붙인다", () => {
        assert.equal(numberedLines("밥, 김치", "  "), "  1. 밥\n  2. 김치");
    });

    await t.test("비어 있으면 (없음)", () => {
        assert.equal(numberedLines(""), "(없음)");
        assert.equal(numberedLines(null, "  "), "  (없음)");
    });
});
