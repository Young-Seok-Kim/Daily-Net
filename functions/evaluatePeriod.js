/**
 * 주·월·연 정산의 식단·운동을 평가하는 함수.
 *
 * 식단 분석(analyzeDiet)이 "오늘 하루"를 보는 것이라면 이건 "그 기간 전체"를 본다.
 * 앱이 그 기간의 일별 기록(숫자 + 식단·운동 텍스트 앞부분)을 보내면
 * 서버가 합계·평균·권장 대비·체중 변화·흐름을 계산하고(periodEval.js),
 * 모델은 그 숫자를 바탕으로 점수·총평·식단 평가·운동 평가·다음 할 일을 쓴다.
 *
 * 분석 횟수는 소모하지 않고 따로 센다 (quota.reserveEvaluation).
 * 받은 결과는 앱이 Firestore에 저장해 두고, 기록이 바뀌었을 때만 다시 묻는다.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { rejectNonAppRequest } = require("./requestGuard");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { generateAndParse, paidModel } = require("./gemini");
const { verifyUser, reserveEvaluation, withTimeout, QUOTA_TIMEOUT_MS } = require("./quota");
const { normalizeInbody, ageFrom, inbodyPromptLine } = require("./inbody");
const {
    PERIOD_TYPES, normalizeDays, normalizeTopItems, periodStats, factLines, dayLines, normalizeEvaluation
} = require("./periodEval");

exports.evaluatePeriod = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 90,
    // 한 달치 기록이 프롬프트에 들어가므로 기본(256MB)보다 넉넉히 둔다
    memory: "512MiB"
}, async (req, res) => {
    if (rejectNonAppRequest(req, res)) return;

    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];
    const startedAt = Date.now();

    try {
        const body = req.body || {};
        const period = body.period && typeof body.period === "object" ? body.period : {};
        const type = PERIOD_TYPES.includes(period.type) ? period.type : null;
        const days = normalizeDays(body.days);
        if (!type || days.length === 0) {
            return res.status(400).json({ evaluation: null, error: "period and days required" });
        }

        const user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ evaluation: null, error: L.authRequired });
        }

        const quota = await withTimeout(reserveEvaluation(user), QUOTA_TIMEOUT_MS);
        if (!quota.allowed) {
            console.warn(`[period-eval] uid=${user.uid} 하루 상한 ${quota.limit}회 도달`);
            return res.status(429).json({ evaluation: null, error: L.evalLimitReached });
        }

        const profile = body.profile && typeof body.profile === "object" ? body.profile : {};
        const inbody = normalizeInbody(body.inbody);
        const topFoods = normalizeTopItems(body.topFoods);
        const topExercises = normalizeTopItems(body.topExercises);
        const today = typeof body.today === "string" ? body.today : "";

        const stats = periodStats(days, { type, start: period.start, end: period.end }, today, profile.weight);

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const generationConfig = {
            responseMimeType: "application/json",
            temperature: 0.4,
            maxOutputTokens: 1536,
            thinkingConfig: { thinkingLevel: "minimal" }
        };
        const model = genAI.getGenerativeModel({ model: "gemini-3.5-flash-lite", generationConfig });
        const fallbackModels = [
            genAI.getGenerativeModel({ model: "gemini-3.5-flash", generationConfig }),
            paidModel("gemini-3.5-flash-lite", generationConfig)
        ];

        const age = ageFrom(profile.birthDate);
        const genderText = profile.isMale ? "남성" : "여성";
        const bodyLine = profile.height > 0
            ? `- 신체: ${profile.height}cm, ${profile.weight}kg, ${genderText} (만 ${age}세)`
            : `- 신체: ${genderText} (만 ${age}세)`;
        const inbodyLine = inbodyPromptLine(inbody);
        const facts = factLines(stats, { ...period, type }, topFoods, topExercises);
        const daily = dayLines(days, type);
        const typeName = { week: "이번 주", month: "이번 달", year: "올해" }[type];

        const prompt = `
            당신은 15년 경력의 [베테랑 전문 다이어트 영양사]입니다.
            아래는 사용자가 앱에 적은 식단·운동 정산을 서버가 **${typeName === "올해" ? "일 년" : typeName === "이번 달" ? "한 달" : "한 주"} 단위로 집계한 것**입니다.
            이 기간의 식단과 운동을 평가해 주십시오. 정중하지만 냉철하게, 근거가 되는 숫자와 음식 이름을 들어서.

            [사용자]
            ${bodyLine}
            ${inbodyLine}

            [기간 집계 — 서버가 계산한 값]
${facts.map((l) => "            " + l).join("\n")}
${daily.length ? `
            [일별 기록 — 순칼로리와 적은 내용]
${daily.map((l) => "        " + l).join("\n")}` : ""}

            [지침]
            1. **숫자를 다시 계산하거나 바꾸지 마십시오.** 위 집계가 사실의 전부입니다. 당신의 일은 그 뜻을 풀어 평가하는 것입니다.
            2. score: 0~100 정수. 네 가지를 같은 무게로 봅니다 — 기록을 꾸준히 했는가(기록한 날/지난 날),
               순칼로리 적자를 유지했는가, 탄단지가 권장에 가까운가(특히 단백질), 운동을 자주 했는가.
               기록이 사흘 미만이면 60을 넘기지 마십시오. 평가할 근거가 부족합니다.
            3. headline: 이 기간을 한 줄로 (20자 안팎). 예: "적자는 지켰지만 단백질이 모자랐습니다"
            4. summary: 전체 총평 두세 문장. 가장 잘한 것 하나와 가장 아쉬운 것 하나를 분명히.
            5. diet: 식단 평가 세네 문장. 자주 먹은 음식과 탄단지 균형을 근거로 들고, 어떤 음식을 줄이거나
               바꾸면 좋을지 **음식 이름을 들어** 말하십시오. 끼니별 기록이 있으면 늦은 간식·과식한 날의 패턴도.
            6. exercise: 운동 평가 두세 문장. 빈도·종류·소모량을 근거로. **운동한 날 수는 위 "운동한 날" 숫자가 전부입니다** —
               활동 소모에는 걸음이 섞여 있으니 소모량이 크다고 운동을 많이 한 것으로 쓰지 마십시오.
               운동이 없거나 드물면 그 사실을 짚고 시작할 방법을.
            7. actions: 다음 ${typeName === "올해" ? "해" : typeName === "이번 달" ? "달" : "주"}에 할 일 3가지. 각 한 문장, 숫자가 들어간 구체적인 것.
               ${stats.inProgress ? "아직 진행 중인 기간이니 **남은 날에 바로 할 수 있는 것**으로." : ""}
            8. 기록하지 않은 날을 굶은 날로 보지 마십시오. 기록률이 낮으면 그것 자체를 지적하십시오.
            9. 없는 항목(0인 값·없는 줄)은 언급하지 마십시오. 겁주지 말고, 과장하지 말고, 냉철하게.
            10. 모든 문장은 반드시 **${L.outputLanguage}**로 쓰십시오. Markdown 없이 순수 JSON만 응답하십시오.

            {
              "score": 0,
              "headline": "",
              "summary": "",
              "diet": "",
              "exercise": "",
              "actions": ["", "", ""]
            }
        `;

        const data = await generateAndParse(
            model,
            prompt,
            { attemptTimeoutMs: 30000, totalBudgetMs: 70000, fallbackModels }
        );
        const evaluation = normalizeEvaluation(data);
        if (!evaluation) {
            throw new Error("모델 응답에 평가 내용이 없음");
        }

        console.log(`[period-eval] uid=${user.uid} type=${type} label=${period.label || ""} days=${days.length} score=${evaluation.score} ms=${Date.now() - startedAt} prompt=${prompt.length}`);

        res.status(200).json({ evaluation, stats });
    } catch (error) {
        console.error("evaluatePeriod Error:", error.message);
        res.status(500).json({ evaluation: null, error: L.error });
    }
});
