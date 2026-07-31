/**
 * Gemini 응답 파싱과 언어 판정 검증.
 *
 * 두 함수 모두 "모델이나 앱이 예상과 다르게 보냈을 때"를 다룬다.
 * 정상 입력만 확인해서는 의미가 없고, 깨진 입력에서의 행동이 계약이다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { safeParseJson } = require("../gemini");
const { resolveLang, LABELS } = require("../labels");

test("safeParseJson - 모델이 섞어 보내는 것들을 걷어낸다", async (t) => {
    await t.test("순수 JSON", () => {
        assert.deepEqual(safeParseJson('{"a":1}'), { a: 1 });
    });

    await t.test("마크다운 코드펜스가 붙어 와도 파싱한다", () => {
        assert.deepEqual(safeParseJson('```json\n{"a":1}\n```'), { a: 1 });
        assert.deepEqual(safeParseJson('```\n{"a":1}\n```'), { a: 1 });
    });

    await t.test("앞뒤에 설명 문구가 섞여도 파싱한다", () => {
        const raw = '네, 분석 결과입니다.\n{"netCalories":-420}\n도움이 되었길 바랍니다.';
        assert.deepEqual(safeParseJson(raw), { netCalories: -420 });
    });

    await t.test("중첩 오브젝트를 끝까지 읽는다 (첫 } 에서 끊지 않는다)", () => {
        const raw = 'text {"a":{"b":2},"c":3} tail';
        assert.deepEqual(safeParseJson(raw), { a: { b: 2 }, c: 3 });
    });

    await t.test("공백과 줄바꿈이 많아도 파싱한다", () => {
        assert.deepEqual(safeParseJson('\n\n   {"a": 1}   \n'), { a: 1 });
    });

    await t.test("깨진 응답은 SyntaxError를 던진다", () => {
        // generateAndParse의 재시도가 `error instanceof SyntaxError`로 판단하므로,
        // 다른 예외로 바뀌면 파싱 실패 시 재시도가 통째로 동작하지 않는다.
        for (const bad of ["", null, undefined, "설명만 있고 JSON이 없다", "{잘못된 json}"]) {
            assert.throws(() => safeParseJson(bad), SyntaxError, `입력: ${bad}`);
        }
    });
});

test("resolveLang - 지원 언어로 떨어뜨린다", async (t) => {
    await t.test("값이 없으면 한국어 (language를 안 보내는 구버전 앱)", () => {
        // 기존 사용자가 지금까지 받던 결과와 완전히 동일해야 한다
        for (const empty of [undefined, null, ""]) {
            assert.equal(resolveLang(empty), "ko");
        }
    });

    await t.test("지원하는 언어는 그대로 쓴다", () => {
        assert.equal(resolveLang("ko"), "ko");
        assert.equal(resolveLang("ko-KR"), "ko");
        assert.equal(resolveLang("en"), "en");
        assert.equal(resolveLang("en-US"), "en");
    });

    await t.test("지원하지 않는 언어는 한국어가 아니라 영어로 간다", () => {
        // 읽을 수 있는 확률이 더 높은 쪽을 고른다
        for (const lang of ["fr-FR", "ja-JP", "de", "zh-Hans-CN"]) {
            assert.equal(resolveLang(lang), "en", `${lang}이 영어로 안 갔다`);
        }
    });

    await t.test("대소문자와 구분자(- _)를 가리지 않는다", () => {
        assert.equal(resolveLang("KO-KR"), "ko");
        assert.equal(resolveLang("en_US"), "en");
        assert.equal(resolveLang("EN"), "en");
    });

    await t.test("돌려준 코드는 항상 LABELS에 존재한다", () => {
        for (const input of [undefined, "ko", "en-US", "fr", "zz", "!!!", 123]) {
            assert.ok(LABELS[resolveLang(input)], `${input} → 없는 언어를 가리켰다`);
        }
    });
});

test("LABELS - 언어별 항목이 빠지지 않는다", async (t) => {
    await t.test("영어 표가 한국어 표와 같은 키를 갖는다", () => {
        // 키가 하나 빠지면 리포트 그 줄만 undefined로 나간다
        const missing = Object.keys(LABELS.ko).filter((k) => !(k in LABELS.en));
        assert.deepEqual(missing, [], `영어에 없는 키: ${missing.join(", ")}`);
    });

    await t.test("빈 문구가 없다", () => {
        for (const [lang, table] of Object.entries(LABELS)) {
            for (const [key, value] of Object.entries(table)) {
                if (typeof value === "function") continue;
                assert.ok(String(value).length > 0, `${lang}.${key}가 비어 있다`);
            }
        }
    });
});
