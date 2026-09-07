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

/**
 * 한 번의 호출을 이만큼 기다렸는데도 답이 없으면 끊고 다시 묻는다.
 *
 * 무료 등급으로 옮긴 날 실제로 겪었다 — 에러도 응답도 없이 한 호출이 100초 넘게 매달려 있었고,
 * 세 번째에 답이 왔을 땐 이미 180초가 지나 함수 타임아웃(120초)도 앱 타임아웃(120초)도
 * 넘긴 뒤였다. 서버 로그에만 성공으로 남고 사용자는 실패를 봤다.
 * (2026-08-05 11:31 gemini=180735ms, 13:54 gemini=146393ms)
 * 끊어야 재시도라도 할 수 있다.
 */
const ATTEMPT_TIMEOUT_MS = 25000;

/**
 * 대기 시간까지 포함해 모델에 쓸 수 있는 전체 시간.
 *
 * 함수 타임아웃이 120초인데 뒤에 식약처 조회(최대 6초)와 응답 조립이 남는다.
 * 여기서 다 써버리면 어차피 아무것도 못 돌려준다.
 */
const TOTAL_BUDGET_MS = 80000;

/** 재시도 간격 상한. 이보다 더 기다려봐야 예산만 먹는다. */
const MAX_BACKOFF_MS = 8000;

/**
 * 5xx가 이만큼 **연달아** 오면 예비 모델로 갈아탄다.
 *
 * 2026-09-04 저녁 실제로 겪었다 — 3.5-flash-lite가 "high demand" 503을 40초 내내 돌려줘
 * 6번을 다 재시도하고도 실패했고, 사용자는 매번 40초를 기다린 뒤 실패를 봤다.
 * 같은 모델을 계속 두드려서는 과부하가 풀리길 기다리는 것뿐이다. 다른 모델은 다른 큐라
 * 두 번 연달아 튕겼으면 바로 옮기는 편이 낫다. 한 번은 잡음일 수 있어 두 번으로 잡았다.
 */
const FALLBACK_AFTER_OVERLOADS = 2;

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
 * 다시 물으면 될 수도 있는 에러인지 판정한다. 재시도할 이유면 로그에 쓸 사유, 아니면 null.
 *
 * 예전엔 메시지에 "503"이 들어 있는지만 봤다. 유료 등급에선 그걸로 충분했는데
 * 무료 등급으로 옮기자마자 걸러지지 않는 실패가 쏟아졌다 (2026-08-05):
 *   - `fetch failed`  — 응답을 받기도 전에 연결이 끊긴 것. 상태 코드가 아예 없어 그냥 통과해 실패했다.
 *   - 429             — 무료 등급은 분당 15회 제한이 있다. 잠깐 기다리면 되는 건데 즉시 실패했다.
 * 반대로 400/401/403/404는 **절대 재시도하지 않는다.** 키가 틀렸거나 모델명이 틀렸거나
 * 요청이 잘못된 것이라 몇 번을 물어도 같은 답이 오고, 사용자만 그만큼 늦게 실패를 본다.
 * (3.x로 올릴 때 thinkingBudget이 INVALID_ARGUMENT로 죽던 게 바로 이 부류다)
 */
function retryReason(error) {
    if (error instanceof SyntaxError) return "JSON 파싱 실패";

    const msg = error?.message || "";
    // SDK가 HTTP 에러에 status를 실어준다. 메시지 문자열을 훑는 것보다 이쪽이 정확하다.
    const status = Number.isFinite(error?.status)
        ? error.status
        : Number((msg.match(/\[(\d{3})\s/) || [])[1]) || 0;

    if (status === 429) return "429 호출 한도";
    if (status >= 500) return `${status} 과부하`;
    if (status >= 400) return null;

    // 상태 코드가 없다는 건 응답을 받기 전에 끝났다는 뜻이다. 이건 대체로 다시 하면 된다.
    if (/abort/i.test(msg)) return "응답 없음(제한 시간 초과)";
    if (/fetch failed|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|socket hang up/i.test(msg)) {
        return "네트워크 오류";
    }
    return null;
}

/**
 * 생성 + JSON 파싱을 함께 재시도 (503 과부하 및 파싱 실패 모두 대응).
 *
 * @param {object} options
 *   - maxRetries: 시도 횟수
 *   - salvageIfHas: 전부 다 실패했을 때, 잘린 응답에서 건져 쓸지 판단할 필수 키 목록.
 *     여기 적힌 키가 **하나라도 안 살아나면 건지지 않고 실패시킨다.**
 *     숫자가 반쯤 빠진 리포트를 멀쩡한 척 보여주는 것보다 실패가 낫다.
 *   - attemptTimeoutMs: 한 번의 호출을 기다릴 상한
 *   - totalBudgetMs: 대기까지 합쳐 여기서 쓸 수 있는 전체 시간.
 *     **부르는 쪽의 함수 타임아웃보다 반드시 짧아야 한다.** 넘기면 응답을 조립할 시간이 없다.
 *   - fallbackModel: 5xx가 연달아 오면 남은 시도를 넘길 예비 모델. 없으면 끝까지 같은 모델로 간다.
 *   - fallbackAfter: 몇 번 연달아 5xx여야 갈아타는지
 */
async function generateAndParse(model, prompt, options = {}) {
    const {
        // 6회인 이유: 어제 과부하가 38초 이어졌다 (11:27:38~11:28:16).
        // 503은 2초쯤이면 튕겨 나오므로 시도 자체는 시간을 거의 안 먹고 대기가 창을 만든다.
        // 1+2+4+8+8초 = 23초에 시도 시간을 더해 35초쯤 버틴다.
        // 더 늘리지 않는 것은 무료 등급이 분당 15회 제한이라 마구 두드리면
        // 과부하가 풀려도 이번엔 429로 막히기 때문이다.
        maxRetries = 6,
        salvageIfHas = null,
        attemptTimeoutMs = ATTEMPT_TIMEOUT_MS,
        totalBudgetMs = TOTAL_BUDGET_MS,
        fallbackModel = null,
        fallbackAfter = FALLBACK_AFTER_OVERLOADS
    } = options;
    const deadline = Date.now() + totalBudgetMs;

    // 프롬프트는 문자열이거나 파트 배열이다. 사진을 함께 보내는 쪽(extractMeal)이 배열을 쓴다.
    const parts = Array.isArray(prompt)
        ? prompt.map((part) => (typeof part === "string" ? { text: part } : part))
        : [{ text: prompt }];

    let lastError;
    let lastRaw = null;
    // 온도는 **파싱이 깨졌을 때만** 올린다. 과부하나 네트워크 문제로 다시 묻는 것은
    // 모델이 답을 만들지도 못한 경우라, 답의 내용을 바꿀 이유가 없다.
    let parseRetries = 0;
    // 5xx가 연달아 온 횟수. 모델이 답을 만들어 준 순간(파싱 실패 포함) 0으로 돌아간다.
    // 제한 시간 초과는 세지도 지우지도 않는다 — 과부하 중에 섞여 오는 증상이지 회복 신호가 아니다.
    let overloads = 0;
    let current = model;

    for (let i = 0; i < maxRetries; i++) {
        const left = deadline - Date.now();
        if (left <= 0) {
            console.warn(`[gemini] 예산 ${totalBudgetMs}ms 소진 - ${i}번 시도하고 포기`);
            break;
        }

        let response = null;
        try {
            const request = { contents: [{ role: "user", parts }] };
            if (parseRetries > 0) {
                // 재시도 때 온도만 바꿔 다시 보내려면 원래 설정을 그대로 들고 가야 한다.
                // 요청에 generationConfig를 실으면 모델에 걸어둔 값을 **합치지 않고 통째로 덮어쓴다.**
                const baseConfig = current.generationConfig || {};
                const idx = Math.min(parseRetries - 1, RETRY_TEMPERATURES.length - 1);
                request.generationConfig = { ...baseConfig, temperature: RETRY_TEMPERATURES[idx] };
            }

            // 한 번의 호출에 상한을 건다. 이게 없으면 매달린 호출 하나가 예산을 통째로 먹고
            // 재시도할 기회조차 남기지 않는다.
            const result = await current.generateContent(request, {
                timeout: Math.min(attemptTimeoutMs, left)
            });
            response = result.response;
            lastRaw = response.text();
            return safeParseJson(lastRaw); // 파싱까지 성공해야 반환
        } catch (error) {
            lastError = error;
            const reason = retryReason(error);
            const isParseError = error instanceof SyntaxError;

            if (isParseError) {
                // 다음에 또 이러면 로그만 보고 원인을 잡을 수 있어야 한다.
                const tail = (lastRaw || "").slice(-RAW_TAIL_CHARS);
                console.warn(`[gemini] 파싱 실패 - ${describeResponse(response)} 길이=${(lastRaw || "").length}`);
                console.warn(`[gemini] 원문 끝: ${tail}`);
                parseRetries++;
                overloads = 0;
            }

            // 다시 물어도 소용없는 에러(키·모델명·요청이 틀림)는 즉시 종료
            if (!reason) throw error;

            // 마지막 시도 뒤에는 기다리지 않는다. 다시 물어보지도 않을 거면서
            // 4초를 더 세우면 사용자만 그만큼 늦게 실패를 본다.
            if (i === maxRetries - 1) break;

            // 5xx가 연달아 오면 예비 모델로 갈아탄다. 기다리지 않고 바로 묻는다 —
            // 대기는 같은 모델의 과부하가 풀리길 기대하는 것인데, 모델을 바꿨으면 그 이유가 없다.
            if (/과부하/.test(reason)) overloads++;
            if (fallbackModel && current !== fallbackModel && overloads >= fallbackAfter) {
                console.warn(`[gemini] ${reason} ${overloads}번 연속 - 예비 모델로 갈아탐 (${fallbackModel.model || "?"})`);
                current = fallbackModel;
                overloads = 0;
                continue;
            }

            // 1·2·4·8초에서 절반~전부 사이로 흔든다. 같은 순간에 몰려 다시 나가면
            // 과부하일 때 똑같이 또 튕긴다.
            const base = Math.min(MAX_BACKOFF_MS, Math.pow(2, i) * 1000);
            const delay = Math.round(base * (0.5 + Math.random() * 0.5));
            if (Date.now() + delay >= deadline) {
                console.warn(`[gemini] 예산이 ${delay}ms를 못 버팀 - ${i + 1}번 시도하고 포기 (사유: ${reason})`);
                break;
            }

            console.warn(`재시도(${i + 1}/${maxRetries}) - 사유: ${reason} (${delay}ms 대기)`);
            await new Promise(resolve => setTimeout(resolve, delay));
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

    // 한 번도 못 물어본 채로 예산이 끝났으면 lastError가 없다. 그대로 던지면
    // undefined가 올라가 호출한 쪽의 error.message에서 또 터진다.
    throw lastError || new Error(`[gemini] 시간이 없어 한 번도 묻지 못함 (예산 ${totalBudgetMs}ms)`);
}

module.exports = { safeParseJson, salvageTruncatedJson, generateAndParse, retryReason };
