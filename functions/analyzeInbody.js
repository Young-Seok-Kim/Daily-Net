/**
 * 인바디(체성분) 결과를 해석해 주는 함수.
 *
 * 식단 분석(analyzeDiet)과 분리한 이유:
 * - 인바디는 몇 달에 한 번 바뀌는 값이라 저장할 때 한 번만 해석하면 된다
 * - 분석 횟수를 소모하지 않는다 (하루 한도는 식단 분석용이다)
 * - 입력이 숫자 몇 개라 응답이 짧고 타임아웃도 짧게 잡을 수 있다
 *
 * 수치 판정(BMI, 체지방률 구간, 골격근 비율)은 서버가 먼저 계산해 프롬프트에 넣는다 (inbody.js bodyMetrics).
 * 모델에게 계산을 맡기면 틀린다 — 식단 분석에서 운동 kcal 곱셈을 서버로 옮긴 것과 같은 이유다.
 * 모델은 그 수치를 바탕으로 **말로 풀어주는 일**만 한다.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { generateAndParse, paidModel } = require("./gemini");
const { verifyUser } = require("./quota");
const { normalizeInbody, ageFrom, bodyMetrics, inbodyReportText } = require("./inbody");

exports.analyzeInbody = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 60,
}, async (req, res) => {
    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];

    try {
        const { inbody: raw, height, weight, isMale, birthDate } = req.body || {};
        const inbody = normalizeInbody(raw);
        if (!inbody) {
            return res.status(400).json({ text: "", error: "inbody required" });
        }

        const user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ text: "", error: L.authRequired });
        }

        const age = ageFrom(birthDate);
        const metrics = bodyMetrics({ inbody, height, weight, age, isMale: !!isMale });

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const generationConfig = {
            responseMimeType: "application/json",
            temperature: 0.3,
            maxOutputTokens: 1024,
            thinkingConfig: { thinkingLevel: "minimal" }
        };
        const model = genAI.getGenerativeModel({ model: "gemini-3.5-flash-lite", generationConfig });
        const fallbackModels = [
            genAI.getGenerativeModel({ model: "gemini-3.5-flash", generationConfig }),
            paidModel("gemini-3.5-flash-lite", generationConfig)
        ];

        const genderText = isMale ? "남성" : "여성";
        const lines = [];
        lines.push(`- 신체: ${height}cm, ${metrics.weight}kg, ${genderText} (만 ${age}세), BMI ${metrics.bmi}`);
        if (inbody.bodyFatPercent > 0) lines.push(`- 체지방률 ${inbody.bodyFatPercent}% → 판정: ${metrics.fatLabel} (표준 ${metrics.fatStandard})`);
        if (inbody.bodyFat > 0) lines.push(`- 체지방량 ${inbody.bodyFat}kg`);
        if (inbody.skeletalMuscle > 0) lines.push(`- 골격근량 ${inbody.skeletalMuscle}kg (체중의 ${metrics.smmRatio}%) → 판정: ${metrics.muscleLabel} (표준 ${metrics.muscleStandard})`);
        if (inbody.bmr > 0) lines.push(`- 기초대사량 ${inbody.bmr}kcal (키·체중·나이 공식으로는 ${metrics.formulaBmr}kcal, 차이 ${metrics.bmrDiff >= 0 ? "+" : ""}${metrics.bmrDiff})`);
        if (inbody.measuredAt) lines.push(`- 측정일 ${inbody.measuredAt}`);

        const prompt = `
            당신은 15년 경력의 [베테랑 전문 다이어트 영양사]입니다.
            아래는 사용자의 체성분(인바디) 측정 결과와 서버가 이미 계산한 판정입니다.
            이 사람에게 맞는 짧고 구체적인 해석을 써 주십시오.

            [측정 결과]
${lines.map((l) => "            " + l).join("\n")}

            [지침]
            1. **숫자를 다시 계산하거나 판정을 바꾸지 마십시오.** 위 판정은 표준 범위로 서버가 정한 것입니다.
               당신의 일은 그 뜻을 풀어 설명하고, 무엇을 하면 좋을지 말하는 것입니다.
            2. summary: 전체 상태를 두세 문장으로. 체지방과 근육 중 무엇이 우선 과제인지 분명히.
            3. bodyFat: 체지방률·체지방량에 대한 설명 두 문장. 표준과 얼마나 떨어져 있는지.
            4. muscle: 골격근량에 대한 설명 두 문장. 감량 중 근손실 위험이 있는지.
            5. bmr: 기초대사량이 공식 추정과 어떻게 다른지, 그래서 하루 섭취를 어떻게 잡아야 하는지 두 문장.
               (앱의 식단 분석은 이 실측 기초대사량을 쓴다는 것을 알려 주십시오)
            6. actions: 지금부터 2~3개월간 할 일 3가지. 각 한 문장, 구체적으로 (예: "단백질 하루 ${metrics.proteinTarget}g").
            7. 없는 항목(0인 값)은 언급하지 마십시오. 겁주지 말고, 과장하지 말고, 냉철하게.
            8. 모든 문장은 반드시 **${L.outputLanguage}**로 쓰십시오. Markdown 없이 순수 JSON만 응답하십시오.

            {
              "summary": "",
              "bodyFat": "",
              "muscle": "",
              "bmr": "",
              "actions": ["", "", ""]
            }
        `;

        const data = await generateAndParse(
            model,
            prompt,
            { attemptTimeoutMs: 20000, totalBudgetMs: 40000, fallbackModels }
        );

        const text = inbodyReportText(data, metrics, inbody, L);
        console.log(`[inbody-analysis] uid=${user.uid} bmi=${metrics.bmi} fat=${metrics.fatLabel} muscle=${metrics.muscleLabel} len=${text.length}`);

        res.status(200).json({ text, metrics });
    } catch (error) {
        console.error("analyzeInbody Error:", error.message);
        res.status(500).json({ text: "", error: L.error });
    }
});
