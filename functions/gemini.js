/**
 * Gemini 응답을 안전하게 JSON으로 바꾸는 공통 유틸.
 * 모델이 가끔 설명 문구나 마크다운을 섞어 보내기 때문에 그대로 JSON.parse 할 수 없다.
 */

/**
 * 파싱이 깨졌을 때 로그에 남길 원문의 끝 길이.
 *
 * 앞이 아니라 **끝**을 남긴다. 잘린 응답은 "어디서 끊겼는지"가 원인의 전부인데,
 * 앞부분은 매번 스키마 그대로라 봐도 알 수 있는 게 없다.
 */
const RAW_TAIL_CHARS = 300;

/**
 * 재시도할 때 쓸 temperature.
 *
 * 같은 프롬프트를 **같은 온도로** 다시 물으면 대체로 같은 답이 온다.
 * 실제로 겪었다 — 응답이 잘려 세 번을 다시 물었는데 세 번 다 똑같이 잘려 분석이 통째로 실패했다.
 * (2026-08-04 08:49, `Expected ',' or ']' after array element ... position 351`)
 * 재시도가 의미를 가지려면 **다른 경로로 생성하게** 만들어야 한다.
 */
const RETRY_TEMPERATURES = [0.7, 1.0];

// 마크다운 코드펜스를 걷어낸다. 모델이 순수 JSON만 달라고 해도 가끔 붙여 보낸다.
function stripFences(rawText) {
    return (rawText || "").replace(/```json/g, "").replace(/```/g, "").trim();
}

// 응답 텍스트에서 JSON 오브젝트만 안전하게 추출/파싱
function safeParseJson(rawText) {
    let text = stripFences(rawText);
    // 앞뒤에 설명 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 파싱
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start !== -1 && end !== -1 && end > start) {
        text = text.substring(start, end + 1);
    }
    return JSON.parse(text);
}

/**
 * 중간에 잘린 JSON을 **마지막으로 온전한 값까지만** 남기고 닫는다. 못 살리면 null.
 *
 * 왜 필요한가:
 * 응답이 `maxOutputTokens`에 걸려 끊기면 [safeParseJson]은 마지막 '}'까지 자르는데,
 * 그 '}'는 대개 배열 **안쪽** 원소의 것이라 배열이 안 닫힌 문자열이 남는다.
 * 그래서 에러가 "끝이 잘렸다"가 아니라 `Expected ',' or ']' after array element`로 나온다.
 * 열려 있는 괄호를 우리가 닫아 주면 끊기기 전까지의 내용은 그대로 쓸 수 있다.
 *
 * 자르는 기준은 **닫힌 괄호 뒤**로만 잡는다. 숫자 한가운데서 끊긴 `"kcal": 40`을 40으로
 * 읽어버리면 400일 수도 있는 값을 조용히 틀리게 쓴다. 빠뜨리는 것보다 그게 나쁘다.
 */
function salvageTruncatedJson(rawText) {
    const text = stripFences(rawText);
    const start = text.indexOf("{");
    if (start === -1) return null;

    const open = [];        // 아직 안 닫힌 괄호들이 기다리는 닫는 문자
    let inString = false;
    let escaped = false;
    let cutAt = -1;         // 마지막으로 값이 온전히 끝난 자리
    let cutOpen = null;     // 그 자리에서 아직 안 닫혀 있던 괄호들

    for (let i = start; i < text.length; i++) {
        const c = text[i];

        // 문자열 안에서는 괄호도 글자일 뿐이다. 메뉴 이름에 '{'가 들어와도 흔들리지 않아야 한다.
        if (inString) {
            if (escaped) escaped = false;
            else if (c === "\\") escaped = true;
            else if (c === '"') inString = false;
            continue;
        }

        if (c === '"') inString = true;
        else if (c === "{" || c === "[") open.push(c === "{" ? "}" : "]");
        else if (c === "}" || c === "]") {
            open.pop();
            cutAt = i + 1;
            cutOpen = [...open];
        }
    }

    if (cutAt === -1) return null;   // 닫힌 괄호가 하나도 없다. 건질 게 없다.

    const closed = text.slice(start, cutAt) + cutOpen.reverse().join("");
    try {
        return JSON.parse(closed);
    } catch (_) {
        return null;
    }
}

/**
 * 왜 실패했는지 로그 한 줄로 남긴다.
 *
 * `finishReason`이 핵심이다. MAX_TOKENS면 모델이 아니라 **우리 상한**이 자른 것이고,
 * STOP인데 JSON이 깨졌다면 그건 다른 문제다. 이 값을 안 남겨서 원인을 한참 뒤에야 알았다.
 */
function describeResponse(response) {
    const reason = response?.candidates?.[0]?.finishReason ?? "?";
    const usage = response?.usageMetadata ?? {};
    return `finishReason=${reason} 프롬프트=${usage.promptTokenCount ?? "?"} `
        + `출력=${usage.candidatesTokenCount ?? "?"} 사고=${usage.thoughtsTokenCount ?? 0}`;
}

/**
 * 생성 + JSON 파싱을 함께 재시도 (503 과부하 및 파싱 실패 모두 대응).
 *
 * @param {object} options
 *   - maxRetries: 시도 횟수
 *   - salvageIfHas: 전부 다 실패했을 때, 잘린 응답에서 건져 쓸지 판단할 필수 키 목록.
 *     여기 적힌 키가 **하나라도 안 살아나면 건지지 않고 실패시킨다.**
 *     숫자가 반쯤 빠진 리포트를 멀쩡한 척 보여주는 것보다 실패가 낫다.
 */
async function generateAndParse(model, prompt, options = {}) {
    const { maxRetries = 3, salvageIfHas = null } = options;
    // 재시도 때 온도만 바꿔 다시 보내려면 원래 설정을 그대로 들고 가야 한다.
    // 요청에 generationConfig를 실으면 모델에 걸어둔 값을 **합치지 않고 통째로 덮어쓴다.**
    const baseConfig = model.generationConfig || {};

    let lastError;
    let lastRaw = null;

    for (let i = 0; i < maxRetries; i++) {
        let response = null;
        try {
            const request = { contents: [{ role: "user", parts: [{ text: prompt }] }] };
            if (i > 0) {
                const temperature = RETRY_TEMPERATURES[Math.min(i - 1, RETRY_TEMPERATURES.length - 1)];
                request.generationConfig = { ...baseConfig, temperature };
            }

            const result = await model.generateContent(request);
            response = result.response;
            lastRaw = response.text();
            return safeParseJson(lastRaw); // 파싱까지 성공해야 반환
        } catch (error) {
            lastError = error;
            const msg = error.message || "";
            const isOverload = msg.includes("503") || msg.includes("high demand");
            const isParseError = error instanceof SyntaxError;

            if (isParseError) {
                // 다음에 또 이러면 로그만 보고 원인을 잡을 수 있어야 한다.
                const tail = (lastRaw || "").slice(-RAW_TAIL_CHARS);
                console.warn(`[gemini] 파싱 실패 - ${describeResponse(response)} 길이=${(lastRaw || "").length}`);
                console.warn(`[gemini] 원문 끝: ${tail}`);
            }

            // 과부하(503) 또는 파싱 실패면 재시도, 그 외 에러는 즉시 종료
            if (isOverload || isParseError) {
                // 마지막 시도 뒤에는 기다리지 않는다. 다시 물어보지도 않을 거면서
                // 4초를 더 세우면 사용자만 그만큼 늦게 실패를 본다.
                if (i === maxRetries - 1) break;

                const delay = Math.pow(2, i) * 1000; // 1초, 2초, 4초 대기
                console.warn(`재시도(${i + 1}/${maxRetries}) - 사유: ${isParseError ? "JSON 파싱 실패" : "503 과부하"} (${delay}ms 대기)`);
                await new Promise(resolve => setTimeout(resolve, delay));
                continue;
            }
            throw error;
        }
    }

    // 마지막 수단. 여기까지 왔다는 건 다시 물어도 안 된다는 뜻이라,
    // 잘린 응답이라도 필요한 부분이 다 들어 있으면 실패시키는 것보다 쓰는 편이 낫다.
    if (salvageIfHas) {
        const salvaged = salvageTruncatedJson(lastRaw);
        const enough = salvaged && salvageIfHas.every((key) => salvaged[key] != null);
        if (enough) {
            console.warn(`[gemini] 잘린 응답에서 건져 씀 - 살린 키: ${Object.keys(salvaged).join(", ")}`);
            return salvaged;
        }
    }

    throw lastError;
}

module.exports = { safeParseJson, salvageTruncatedJson, generateAndParse };
