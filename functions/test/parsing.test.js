/**
 * Gemini 응답 파싱과 언어 판정 검증.
 *
 * 두 함수 모두 "모델이나 앱이 예상과 다르게 보냈을 때"를 다룬다.
 * 정상 입력만 확인해서는 의미가 없고, 깨진 입력에서의 행동이 계약이다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { safeParseJson, salvageTruncatedJson, generateAndParse } = require("../gemini");
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

/**
 * 실제로 겪은 실패 응답의 모양.
 *
 * 2026-08-04 08:49 정산이 통째로 실패했다. `maxOutputTokens`에 걸려 응답이 끊겼는데,
 * safeParseJson이 마지막 '}'까지 자르는 바람에 **배열이 안 닫힌 문자열**이 남아
 * `Expected ',' or ']' after array element`가 났다.
 */
const CUT_IN_ARRAY = `{
  "calories": { "breakfast": 0, "lunch": 0, "dinner": 0, "snack": 400, "exercise": 0 },
  "meals": {
    "snack": [
      { "name": "바나프레소 초코쉐이크", "kcal": 400 },
      { "name": "아메리카노", "kc`;

test("salvageTruncatedJson - 잘린 응답에서 온전한 데까지 건진다", async (t) => {
    await t.test("실제 실패 응답을 살려낸다", () => {
        // 먼저 원래 경로가 정말 깨지는지부터 확인한다. 안 깨지면 이 함수를 탈 일이 없다.
        assert.throws(() => safeParseJson(CUT_IN_ARRAY), SyntaxError);

        const data = salvageTruncatedJson(CUT_IN_ARRAY);
        assert.equal(data.calories.snack, 400);
        // 끊긴 항목은 버리고 온전한 것만 남는다
        assert.equal(data.meals.snack.length, 1);
        assert.equal(data.meals.snack[0].name, "바나프레소 초코쉐이크");
    });

    await t.test("숫자 한가운데서 끊긴 값은 버린다", () => {
        // 40으로 읽었는데 실제로 400이면 조용히 틀린 칼로리가 나간다. 빠뜨리는 쪽이 낫다.
        const data = salvageTruncatedJson('{"a":[{"kcal":400},{"kcal":12');
        assert.deepEqual(data, { a: [{ kcal: 400 }] });
    });

    await t.test("문자열 안의 괄호에 흔들리지 않는다", () => {
        const data = salvageTruncatedJson('{"a":[{"name":"{이상한] 메뉴}"}');
        assert.deepEqual(data, { a: [{ name: "{이상한] 메뉴}" }] });
    });

    await t.test("온전한 JSON은 그대로 돌려준다", () => {
        assert.deepEqual(salvageTruncatedJson('{"a":{"b":[1,2]}}'), { a: { b: [1, 2] } });
    });

    await t.test("건질 게 없으면 null", () => {
        for (const bad of ["", null, undefined, "설명만 있고 JSON이 없다", '{"a":1']) {
            assert.equal(salvageTruncatedJson(bad), null, `입력: ${bad}`);
        }
    });
});

// 실제 SDK 대신 정해둔 응답을 돌려주는 모델. 요청을 기록해 재시도 방식까지 확인한다.
function fakeModel(texts) {
    const requests = [];
    return {
        requests,
        generationConfig: { temperature: 0.4, maxOutputTokens: 4096 },
        async generateContent(request) {
            requests.push(request);
            const text = texts[Math.min(requests.length - 1, texts.length - 1)];
            return {
                response: {
                    text: () => text,
                    candidates: [{ finishReason: "MAX_TOKENS" }],
                    usageMetadata: { promptTokenCount: 100, candidatesTokenCount: 4096 }
                }
            };
        }
    };
}

test("generateAndParse - 깨진 응답을 다루는 방식", async (t) => {
    await t.test("재시도는 temperature를 올려 다시 묻는다", async () => {
        // 같은 온도로 다시 물으면 같은 답이 온다. 실제로 세 번 다 똑같이 잘려 실패했다.
        const model = fakeModel([CUT_IN_ARRAY, '{"ok":1}']);
        assert.deepEqual(await generateAndParse(model, "p", { maxRetries: 2 }), { ok: 1 });

        assert.equal(model.requests.length, 2);
        assert.equal(model.requests[0].generationConfig, undefined, "첫 시도는 모델 설정 그대로");
        assert.equal(model.requests[1].generationConfig.temperature, 0.7);
        // 온도만 바꿔야 한다. 요청에 실으면 모델 설정을 통째로 덮어쓰므로 나머지를 빠뜨리면 안 된다.
        assert.equal(model.requests[1].generationConfig.maxOutputTokens, 4096);
    });

    await t.test("끝까지 실패하면 필수 키가 다 살았을 때만 건져 쓴다", async () => {
        const model = fakeModel([CUT_IN_ARRAY]);
        const data = await generateAndParse(model, "p", {
            maxRetries: 1,
            salvageIfHas: ["calories", "meals"]
        });
        assert.equal(data.calories.snack, 400);
    });

    await t.test("필수 키가 하나라도 빠지면 건지지 않고 실패시킨다", async () => {
        // 숫자가 반쯤 빠진 리포트를 멀쩡한 척 보여주는 것보다 실패가 낫다.
        const model = fakeModel([CUT_IN_ARRAY]);
        await assert.rejects(
            generateAndParse(model, "p", { maxRetries: 1, salvageIfHas: ["calories", "macros"] }),
            SyntaxError
        );
    });

    await t.test("salvageIfHas를 안 주면 건지지 않는다", async () => {
        const model = fakeModel([CUT_IN_ARRAY]);
        await assert.rejects(generateAndParse(model, "p", { maxRetries: 1 }), SyntaxError);
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
