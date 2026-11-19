/**
 * 전역 AI 질문(askAiCoach).
 *
 * 앱의 조회 화면(일일 목록·일일 정산·월/주/연 정산·인바디·체중 추이)에는 우하단에 AI 버튼이 뜬다.
 * 누르면 **지금 보고 있는 화면**을 앱이 요약해 보내고, 사용자는 그 화면에 대해 바로 묻는다.
 *
 * 하루 분석(analyzeDiet)과 다른 점:
 *  - 숫자를 다시 계산하지 않는다. 화면이 보여주는 값(앱이 contextData로 요약해 보낸 것)만 근거로 쓴다.
 *    서버가 권장량·순칼로리를 새로 계산하면 화면과 답이 어긋난다.
 *  - 응답이 JSON이 아니라 **글**이다. 앱은 그 글을 말풍선에 그대로 넣는다.
 *  - 세션 안에서만 이어지는 대화라 저장하지 않는다. history는 앱이 들고 있다가 매 요청에 다시 보낸다.
 *  - 분석 횟수를 소모하지 않고 따로 센다 (quota.reserveAiAsk).
 *
 * 사용자 입력은 전부 프롬프트 구조를 깨지 못하게 다듬는다 — 화면 데이터의 줄바꿈을 눌러 한 덩어리로
 * 만들고 길이를 자른다(functions/aiContext.js 와 같은 이유).
 */
const { onRequest } = require("firebase-functions/v2/https");
const { rejectNonAppRequest } = require("./requestGuard");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { retryReason, paidModel } = require("./gemini");
const { verifyUser, reserveAiAsk, refundAiAsk, withTimeout, QUOTA_TIMEOUT_MS } = require("./quota");
const { normalizeAiContext, aiContextPromptBlock } = require("./aiContext");

/** 질문 한 번의 길이 상한. 앱 입력창도 같은 값으로 자른다(AiAsk.MAX_QUESTION) */
const MAX_QUESTION_CHARS = 500;

/** 이어 보낼 수 있는 대화의 최대 왕복 수(질문+답 한 쌍을 1로 센다). 앱은 3왕복만 보낸다 */
const MAX_HISTORY_TURNS = 6;

/** history 한 줄의 길이 상한. 답변이 길어도 앞뒤 맥락만 남기면 충분하다 */
const MAX_HISTORY_CHARS = 600;

/** 화면 데이터 상한. 한 달치 일별 요약까지 들어가므로 넉넉하되 프롬프트가 무너지지 않게 자른다 */
const MAX_CONTEXT_CHARS = 2000;

/** 화면 이름(뱃지에 그대로 보이는 말)의 길이 상한 */
const MAX_TITLE_CHARS = 60;

/**
 * 앱이 보내는 화면 종류. 앱의 AiScreenTypes와 같아야 한다.
 * 여기 없는 값이 와도 거절하지 않고 "unknown"으로 두고 제목·데이터만으로 답한다
 * (앱이 먼저 배포돼 새 화면을 보내는 경우).
 */
const SCREEN_TYPES = [
    "daily_list",
    "daily_record",
    "month_report",
    "period_report",
    "inbody_list",
    "inbody_trend",
    "weight_trend"
];

/** 모든 공백·줄바꿈을 한 칸으로 눌러 한 줄로 만든다 */
function pressLine(raw) {
    return String(raw).replace(/\s+/g, " ").trim();
}

/**
 * 질문을 다듬는다. 비었으면 빈 문자열(=요청 거절).
 * 줄바꿈은 살린다 — 사용자가 여러 줄로 적은 질문은 그대로 읽어야 한다.
 */
function normalizeQuestion(raw) {
    if (typeof raw !== "string") return "";
    const text = raw.replace(/\r\n?/g, "\n").trim();
    if (!text) return "";
    return text.length > MAX_QUESTION_CHARS ? text.slice(0, MAX_QUESTION_CHARS) : text;
}

/**
 * 화면 종류와 화면 이름. 제목은 줄바꿈을 눌러 한 줄로 만든다(뱃지가 한 줄이다).
 * 제목이 비면 종류 이름을 그대로 쓴다.
 */
function normalizeScreen(body) {
    const raw = body && typeof body === "object" ? body : {};
    const type = SCREEN_TYPES.includes(raw.screenType) ? raw.screenType : "unknown";
    const title = pressLine(raw.screenTitle || "").slice(0, MAX_TITLE_CHARS);
    return { type, title: title || type };
}

/**
 * 화면 데이터. 앱이 만든 요약 텍스트를 그대로 받되, 제어문자를 걷어내고 길이를 자른다.
 * 빈 값이면 빈 문자열 — 프롬프트에 "화면 데이터 없음"으로 적힌다.
 */
function normalizeContextData(raw) {
    if (typeof raw !== "string") return "";
    // 탭은 살린다(앱이 만든 표 모양 요약이 읽기 좋다). 나머지 제어문자만 지운다.
    const cleaned = raw.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "").trim();
    return cleaned.length > MAX_CONTEXT_CHARS ? cleaned.slice(0, MAX_CONTEXT_CHARS) : cleaned;
}

/**
 * 이어 온 대화. role은 user/assistant 둘만 받고, 모르는 값은 버린다.
 * 오래된 것부터 남기고 최근 MAX_HISTORY_TURNS개만 쓴다 (맥락은 최근 쪽이 중요하다).
 */
function normalizeHistory(raw) {
    if (!Array.isArray(raw)) return [];
    const turns = [];
    for (const item of raw) {
        if (!item || typeof item !== "object") continue;
        const role = item.role === "user" || item.role === "assistant" ? item.role : null;
        if (!role) continue;
        const text = typeof item.text === "string" ? item.text.trim() : "";
        if (!text) continue;
        turns.push({ role, text: text.length > MAX_HISTORY_CHARS ? text.slice(0, MAX_HISTORY_CHARS) : text });
    }
    return turns.slice(-MAX_HISTORY_TURNS);
}

/** 대화 기록을 프롬프트 블록으로. 없으면 빈 문자열 */
function historyBlock(history) {
    if (!history.length) return "";
    const lines = history.map((t) => `${t.role === "user" ? "사용자" : "AI"}: ${t.text}`);
    return ["[지금까지의 대화]", ...lines].join("\n");
}

/**
 * 모델에 보낼 프롬프트. 순수 함수라 테스트가 모델 없이 돈다.
 *
 * @param {object} input
 *   - screen: {type, title}
 *   - contextData: 앱이 요약한 화면 데이터 (없으면 "")
 *   - aiContextBlock: 설정의 목표·활동량·메모 블록 (없으면 "")
 *   - history: 정리된 대화
 *   - question: 정리된 질문
 *   - outputLanguage: "한국어" / "English"
 */
function buildPrompt({ screen, contextData, aiContextBlock: contextBlock, history, question, outputLanguage }) {
    const data = contextData || "(이 화면에서 앱이 보낸 데이터가 없습니다)";
    const parts = [
        "당신은 다이어트 기록 앱 'DailyNet'의 AI 코치입니다.",
        `사용자가 지금 앱에서 보고 있는 화면은 '${screen.title}' (${screen.type}) 입니다.`,
        "",
        "[화면 데이터 — 앱이 지금 화면에 보여주고 있는 값]",
        data
    ];
    if (contextBlock) {
        parts.push("", contextBlock);
    }
    const past = historyBlock(history);
    if (past) parts.push("", past);
    parts.push(
        "",
        "[질문]",
        question,
        "",
        "[지침]",
        "1. 위 [화면 데이터]에 있는 값만 근거로 답하십시오. 없는 수치를 만들거나 추측해서 단정하지 마십시오.",
        "   화면에 없는 값이 필요하면 \"이 화면에는 그 값이 없습니다\"라고 말하고, 어디서 볼 수 있는지 알려 주십시오.",
        "2. 화면 데이터의 숫자(순칼로리·권장량·체중·체성분)는 앱이 이미 계산한 값입니다. 다시 계산하거나 바꾸지 말고 그대로 인용하십시오.",
        "3. 결론을 먼저 두 문장 안에 말하고, 근거가 되는 숫자를 한두 개 붙이십시오. 전체는 3~6문장으로 짧게.",
        "4. 제안이 필요하면 '- '로 시작하는 짧은 불릿 2~3개를 쓰십시오. 불릿 말고는 목록·표·제목을 쓰지 마십시오.",
        "5. 의학적 진단·약물 처방·극단적 단식 권유는 하지 마십시오. 필요하면 전문가 상담을 권하십시오.",
        `6. 모든 문장은 반드시 ${outputLanguage}로 쓰십시오. kcal·kg 같은 단위는 그대로 두십시오.`
    );
    return parts.join("\n");
}

/**
 * 글 답변을 받는다 (JSON 아님).
 *
 * gemini.js의 generateAndParse는 JSON 파싱까지 하므로 쓸 수 없다. 재시도 규칙은 그쪽과 같게 둔다 —
 * 다시 물어도 소용없는 에러(400·401·403·404)는 즉시 던지고, 과부하·한도·무응답은 예비 모델로 갈아탄다.
 *
 * @param {object} options
 *   - attemptTimeoutMs: 한 번의 호출 상한
 *   - totalBudgetMs: 대기까지 합친 전체 예산. 함수 타임아웃(60초)보다 짧아야 한다
 *   - fallbackModels: 앞에서부터 갈아탈 예비 모델
 */
async function generateText({ model, fallbackModels = [], prompt, attemptTimeoutMs = 20000, totalBudgetMs = 40000, maxRetries = 3, fallbackAfter = 2 }) {
    const chain = [model, ...fallbackModels.filter(Boolean)];
    const deadline = Date.now() + totalBudgetMs;
    let stage = 0;
    let overloads = 0;
    let lastError = null;

    for (let i = 0; i < maxRetries; i++) {
        const left = deadline - Date.now();
        if (left <= 0) {
            console.warn(`[askAiCoach] 예산 ${totalBudgetMs}ms 소진 - ${i}번 시도하고 포기`);
            break;
        }
        try {
            const result = await chain[stage].generateContent(
                { contents: [{ role: "user", parts: [{ text: prompt }] }] },
                { timeout: Math.min(attemptTimeoutMs, left) }
            );
            const text = (result.response.text() || "").trim();
            if (text) return text;
            lastError = new Error("[askAiCoach] 빈 응답");
        } catch (error) {
            lastError = error;
            const reason = retryReason(error);
            // 키·모델명·요청이 틀린 것은 몇 번을 물어도 같다
            if (!reason) throw error;
            if (/과부하|한도|응답 없음/.test(reason)) overloads++;
            if (stage < chain.length - 1 && overloads >= fallbackAfter) {
                stage++;
                overloads = 0;
                console.warn(`[askAiCoach] ${reason} ${fallbackAfter}번 연속 - 예비 모델 ${stage}단계로 갈아탐`);
                continue;
            }
        }
        if (i === maxRetries - 1) break;
        await new Promise((resolve) => setTimeout(resolve, 500 * (i + 1)));
    }

    throw lastError || new Error("[askAiCoach] 답을 만들지 못함");
}

const askAiCoach = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 60,
}, async (req, res) => {
    if (rejectNonAppRequest(req, res)) return;

    const body = req.body || {};
    const lang = resolveLang(body.language);
    const L = LABELS[lang];

    // 질문이 비면 모델을 부르지 않는다 (비용·지연 모두 낭비다)
    const question = normalizeQuestion(body.question);
    if (!question) {
        return res.status(400).json({ answer: "", error: L.askEmpty });
    }

    try {
        // 앱이 만든 데이터는 인증 전에 정리해 둔다 (검증 실패해도 모델은 부르지 않는다)
        const screen = normalizeScreen(body);
        const contextData = normalizeContextData(body.contextData);
        const history = normalizeHistory(body.history);
        const aiContext = normalizeAiContext(body.aiContext);

        const user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ answer: "", error: L.authRequired });
        }

        // 무료 3회 제한은 로그인한 사용자에게만 의미가 있다. 토큰 없는 요청은 위에서 이미 끊겼다
        const quota = await withTimeout(reserveAiAsk(user), QUOTA_TIMEOUT_MS);
        if (!quota.allowed) {
            console.warn(`[askAiCoach] uid=${user.uid} 오늘 질문 상한 ${quota.limit}회 도달`);
            return res.status(429).json({
                answer: "",
                error: L.askLimitReached,
                remainingFreeCount: quota.remainingFreeCount,
                limit: quota.limit
            });
        }

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const generationConfig = {
            temperature: 0.5,
            maxOutputTokens: 900,
            thinkingConfig: { thinkingLevel: "minimal" }
        };
        const model = genAI.getGenerativeModel({ model: "gemini-3.5-flash-lite", generationConfig });
        const fallbackModels = [
            genAI.getGenerativeModel({ model: "gemini-3.5-flash", generationConfig }),
            paidModel("gemini-3.5-flash-lite", generationConfig)
        ];

        const prompt = buildPrompt({
            screen,
            contextData,
            aiContextBlock: aiContextPromptBlock(aiContext),
            history,
            question,
            outputLanguage: L.outputLanguage
        });

        try {
            const answer = await generateText({ model, fallbackModels, prompt });
            console.log(`[askAiCoach] uid=${user.uid} 화면=${screen.type} 질문=${question.length}자 답변=${answer.length}자 남은=${quota.remainingFreeCount}`);
            return res.status(200).json({
                answer,
                remainingFreeCount: quota.remainingFreeCount,
                limit: quota.limit
            });
        } catch (error) {
            // 답을 못 만들었으면 센 횟수를 돌려준다. 실패한 질문까지 세면 사용자만 손해다.
            await refundAiAsk(user.uid);
            throw error;
        }
    } catch (error) {
        console.error("askAiCoach Error:", error.message);
        res.status(500).json({ answer: "", error: L.error });
    }
});

module.exports = {
    askAiCoach,
    MAX_QUESTION_CHARS,
    MAX_HISTORY_TURNS,
    MAX_HISTORY_CHARS,
    MAX_CONTEXT_CHARS,
    MAX_TITLE_CHARS,
    SCREEN_TYPES,
    normalizeQuestion,
    normalizeScreen,
    normalizeContextData,
    normalizeHistory,
    buildPrompt,
    generateText
};
