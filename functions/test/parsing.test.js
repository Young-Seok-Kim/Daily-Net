/**
 * Gemini 응답 파싱과 언어 판정 검증.
 *
 * 두 함수 모두 "모델이나 앱이 예상과 다르게 보냈을 때"를 다룬다.
 * 정상 입력만 확인해서는 의미가 없고, 깨진 입력에서의 행동이 계약이다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { safeParseJson, salvageTruncatedJson, generateAndParse, retryReason } = require("../gemini");
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

/**
 * 실제 SDK 대신 정해둔 응답을 돌려주는 모델. 요청을 기록해 재시도 방식까지 확인한다.
 * 배열 원소가 Error면 그 시도는 그 에러로 실패한다 (503·네트워크 오류 재현용).
 */
function fakeModel(texts) {
    const requests = [];
    const requestOptions_ = [];
    return {
        requests,
        options: requestOptions_,
        generationConfig: { temperature: 0.4, maxOutputTokens: 4096 },
        async generateContent(request, requestOptions) {
            requests.push(request);
            requestOptions_.push(requestOptions);
            const text = texts[Math.min(requests.length - 1, texts.length - 1)];
            if (text instanceof Error) throw text;
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

// SDK가 실제로 던지는 모양. HTTP 에러에는 status가 실리고, 연결이 끊기면 status가 없다.
function sdkError(message, status) {
    const error = new Error(`[GoogleGenerativeAI Error]: Error fetching from https://x: ${message}`);
    if (status) error.status = status;
    return error;
}

test("retryReason - 다시 물어서 될 것과 안 될 것을 가른다", async (t) => {
    await t.test("다시 하면 될 수도 있는 것", () => {
        // 전부 2026-08-05에 실제로 겪은 실패다. status가 없는 네트워크 오류를 안 걸러
        // 재시도 없이 그대로 실패했었다.
        assert.ok(retryReason(sdkError("fetch failed")));
        assert.ok(retryReason(sdkError("[503 Service Unavailable] high demand", 503)));
        assert.ok(retryReason(sdkError("[429 Too Many Requests] quota", 429)));
        assert.ok(retryReason(sdkError("[500 Internal Server Error]", 500)));
        assert.ok(retryReason(new Error("Request aborted when fetching https://x")));
        assert.ok(retryReason(new Error("read ECONNRESET")));
        assert.ok(retryReason(new SyntaxError("Unexpected end of JSON input")));
    });

    await t.test("몇 번을 물어도 같은 답이 오는 것은 즉시 실패", () => {
        // 3.x로 올릴 때 thinkingBudget이 INVALID_ARGUMENT로 죽었다. 이걸 재시도하면
        // 결과는 똑같고 사용자만 그만큼 늦게 실패를 본다.
        assert.equal(retryReason(sdkError("[400 Bad Request] INVALID_ARGUMENT", 400)), null);
        assert.equal(retryReason(sdkError("[403 Forbidden] API key not valid", 403)), null);
        assert.equal(retryReason(sdkError("[404 Not Found] model not found", 404)), null);
    });

    await t.test("status가 없어도 메시지의 코드를 읽는다", () => {
        // 실제 로그에 남은 문자열 그대로. status를 안 실어주는 경로가 있어 둘 다 본다.
        assert.ok(retryReason(new Error("[503 Service Unavailable] high demand")));
        assert.equal(retryReason(new Error("[400 Bad Request] INVALID_ARGUMENT")), null);
    });
});

test("generateAndParse - 호출 자체가 실패했을 때", async (t) => {
    await t.test("네트워크 오류는 다시 묻는다", async () => {
        const model = fakeModel([sdkError("fetch failed"), '{"ok":1}']);
        assert.deepEqual(await generateAndParse(model, "p", { maxRetries: 2 }), { ok: 1 });
        assert.equal(model.requests.length, 2);
    });

    await t.test("과부하(503)도 다시 묻는다", async () => {
        const model = fakeModel([sdkError("[503] high demand", 503), '{"ok":1}']);
        assert.deepEqual(await generateAndParse(model, "p", { maxRetries: 2 }), { ok: 1 });
        assert.equal(model.requests.length, 2);
    });

    await t.test("다시 물어도 소용없는 에러는 한 번만 묻고 던진다", async () => {
        const model = fakeModel([sdkError("[400] INVALID_ARGUMENT", 400)]);
        await assert.rejects(generateAndParse(model, "p", { maxRetries: 3 }), /INVALID_ARGUMENT/);
        assert.equal(model.requests.length, 1, "400을 재시도하면 안 된다");
    });

    await t.test("5xx가 연달아 오면 예비 모델로 갈아탄다", async () => {
        // 2026-09-04 저녁, lite가 503을 40초 내내 돌려줘 6번을 다 재시도하고도 실패했다.
        // 같은 모델을 계속 두드리는 대신 두 번 연달아 튕기면 다른 모델로 넘긴다.
        const overloaded = sdkError("[503 Service Unavailable] high demand", 503);
        const model = fakeModel([overloaded, overloaded, '{"from":"lite"}']);
        const fallbackModel = fakeModel(['{"from":"flash"}']);
        assert.deepEqual(
            await generateAndParse(model, "p", { maxRetries: 6, fallbackModel }),
            { from: "flash" }
        );
        assert.equal(model.requests.length, 2, "두 번 튕긴 뒤엔 원래 모델에 더 묻지 않는다");
        assert.equal(fallbackModel.requests.length, 1);
    });

    await t.test("5xx가 한 번뿐이면 원래 모델로 계속 간다", async () => {
        const model = fakeModel([sdkError("[503 Service Unavailable]", 503), '{"ok":1}']);
        const fallbackModel = fakeModel(['{"from":"flash"}']);
        assert.deepEqual(await generateAndParse(model, "p", { maxRetries: 6, fallbackModel }), { ok: 1 });
        assert.equal(fallbackModel.requests.length, 0);
    });

    await t.test("예비 모델도 계속 튕기면 그대로 실패한다", async () => {
        const overloaded = sdkError("[503 Service Unavailable]", 503);
        const model = fakeModel([overloaded]);
        const fallbackModel = fakeModel([overloaded]);
        await assert.rejects(
            generateAndParse(model, "p", { maxRetries: 4, fallbackModel, totalBudgetMs: 60000 }),
            /503/
        );
        assert.equal(model.requests.length, 2);
        assert.equal(fallbackModel.requests.length, 2, "남은 시도를 예비 모델이 이어받는다");
    });

    await t.test("호출이 실패했을 때는 온도를 올리지 않는다", async () => {
        // 온도를 바꾸는 건 "같은 답이 또 잘려 오는" 파싱 실패용이다.
        // 모델이 답을 만들지도 못한 경우엔 바꿀 이유가 없다.
        const model = fakeModel([sdkError("fetch failed"), '{"ok":1}']);
        await generateAndParse(model, "p", { maxRetries: 2 });
        assert.equal(model.requests[1].generationConfig, undefined);
    });

    await t.test("한 번의 호출에 제한 시간을 건다", async () => {
        // 이게 없어서 매달린 호출 하나가 180초를 먹고 함수 타임아웃(120초)을 넘겼다.
        const model = fakeModel(['{"ok":1}']);
        await generateAndParse(model, "p", { attemptTimeoutMs: 25000 });
        assert.equal(model.options[0].timeout, 25000);
    });

    await t.test("남은 예산보다 긴 제한 시간은 걸지 않는다", async () => {
        const model = fakeModel(['{"ok":1}']);
        await generateAndParse(model, "p", { attemptTimeoutMs: 25000, totalBudgetMs: 5000 });
        assert.ok(model.options[0].timeout <= 5000);
    });

    await t.test("사진(파트 배열)도 같은 재시도를 탄다", async () => {
        // extractMeal이 이 형태로 부른다. 문자열만 받으면 사진 경로는 재시도 없이 그냥 실패한다.
        const image = { inlineData: { data: "base64", mimeType: "image/jpeg" } };
        const model = fakeModel([sdkError("fetch failed"), '{"items":[]}']);
        assert.deepEqual(
            await generateAndParse(model, [image, "메뉴를 읽어라"], { maxRetries: 2 }),
            { items: [] }
        );
        assert.equal(model.requests.length, 2);
        // 파트 순서가 뒤집히면 모델이 사진을 지시로 읽는다. 문자열만 {text}로 감싼다.
        assert.deepEqual(model.requests[0].contents[0].parts, [image, { text: "메뉴를 읽어라" }]);
    });

    await t.test("예산이 없으면 아예 묻지 않는다", async () => {
        // 함수 타임아웃이 코앞인데 또 물어봐야 응답을 돌려줄 시간이 없다.
        // 이때 던지는 것이 Error여야 한다. undefined를 던지면 호출한 쪽의
        // error.message에서 또 터져 원인이 로그에서 사라진다.
        const model = fakeModel(['{"ok":1}']);
        await assert.rejects(
            generateAndParse(model, "p", { maxRetries: 5, totalBudgetMs: 0 }),
            (e) => e instanceof Error && /한 번도 묻지 못함/.test(e.message)
        );
        assert.equal(model.requests.length, 0);
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
