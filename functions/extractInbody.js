/**
 * 인바디 결과지 사진에서 체성분 숫자를 읽어오는 함수.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { rejectNonAppRequest } = require("./requestGuard");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { generateAndParse, paidModel, PHOTO_RETRY_OPTIONS } = require("./gemini");
const { verifyUser, reservePhoto, refundPhoto } = require("./quota");
const { normalizeInbody } = require("./inbody");

/**
 * 인바디 결과지에서 기초대사량·골격근량·체지방량·체지방률·체중·측정일을 읽어 준다.
 *
 * extractMeal과 분리한 이유:
 * - 읽는 대상이 음식이 아니라 표에 인쇄된 숫자라 프롬프트가 완전히 다르다
 * - 결과를 그대로 저장하지 않고 설정 화면의 입력칸에 채워 사용자가 확인·수정한 뒤 저장한다
 * - 응답이 짧아 타임아웃을 짧게 잡는다
 *
 * 횟수는 사진 인식 시도 상한(quota.js)을 식사 사진과 같이 쓴다. 인바디는 몇 달에 한 번이라
 * 별도 상한을 둘 이유가 없고, 비용 사고 방지선만 있으면 된다.
 */
exports.extractInbody = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 60,
}, async (req, res) => {
    // 스캐너·봇의 GET이나 빈 요청은 모델을 부르기 전에 끊는다 (requestGuard.js 참고)
    if (rejectNonAppRequest(req, res)) return;

    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];

    let user = null;
    let reserved = false;

    try {
        const { image, mimeType } = req.body;
        if (typeof image !== "string" || image.length === 0) {
            return res.status(400).json({ found: false, inbody: null, error: "image required" });
        }

        user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ found: false, inbody: null, error: L.authRequired });
        }

        const photo = await reservePhoto(user, 1);
        if (!photo.allowed) {
            console.warn(`[inbody] 시도 상한 ${photo.attempts}/${photo.limit} uid=${user.uid}`);
            return res.status(429).json({ found: false, inbody: null, error: L.photoLimitReached });
        }
        reserved = true;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const generationConfig = {
            responseMimeType: "application/json",
            temperature: 0, // 인쇄된 숫자를 옮기는 일이라 창의성이 필요 없다
            maxOutputTokens: 512,
            thinkingConfig: { thinkingLevel: "minimal" }
        };
        const model = genAI.getGenerativeModel({ model: "gemini-3.5-flash-lite", generationConfig });
        const fallbackModels = [
            genAI.getGenerativeModel({ model: "gemini-3.5-flash", generationConfig }),
            paidModel("gemini-3.5-flash-lite", generationConfig)
        ];

        const prompt = `
            이 사진은 인바디(InBody) 같은 체성분 분석 결과지입니다. 인쇄된 숫자를 읽어 옮기십시오.

            [읽을 항목]
            - bmr: 기초대사량 (kcal). "기초대사량", "Basal Metabolic Rate", "BMR" 옆의 숫자
            - skeletalMuscle: 골격근량 (kg). "골격근량", "Skeletal Muscle Mass", "SMM"
            - bodyFat: 체지방량 (kg). "체지방량", "Body Fat Mass", "BFM"
            - bodyFatPercent: 체지방률 (%). "체지방률", "Percent Body Fat", "PBF"
            - weight: 체중 (kg). "체중", "Weight"
            - visceralFatLevel: 내장지방 레벨. "내장지방레벨", "Visceral Fat Level" (1~30 사이 정수)
            - inbodyScore: 인바디 점수. "인바디점수", "InBody Score" (0~100 점)
            - bodyWater: 체수분 (L). "체수분", "Total Body Water", "TBW"
            - measuredAt: 측정일. 결과지 상단의 검사일시를 "YYYY-MM-DD"로. 없으면 빈 문자열

            [지침]
            1. **계산하지 마십시오. 인쇄된 숫자를 그대로 옮기기만 하십시오.**
            2. 항목이 없거나 숫자가 흐려 확신이 없으면 그 항목은 0으로 두십시오. 지어내면 안 됩니다.
               - 실제로 체지방률 자리에 체중이 들어가면 분석이 통째로 틀어집니다.
               - 표의 **측정값 칸**을 읽으십시오. 표준 범위나 목표치 칸이 아닙니다.
            3. 같은 항목이 여러 번 보이면(그래프와 표) 표의 숫자를 우선하십시오.
            4. 체성분 결과지가 아니면 found를 false로 하고 나머지는 전부 0으로 두십시오.
            5. Markdown 없이 순수 JSON만 응답하십시오.

            {
              "found": true,
              "bmr": 0,
              "skeletalMuscle": 0,
              "bodyFat": 0,
              "bodyFatPercent": 0,
              "weight": 0,
              "visceralFatLevel": 0,
              "inbodyScore": 0,
              "bodyWater": 0,
              "measuredAt": ""
            }
        `;

        const data = await generateAndParse(
            model,
            [
                { inlineData: { data: image, mimeType: mimeType || "image/jpeg" } },
                prompt
            ],
            // 매달린 호출 두 번 뒤에도 예비 모델을 한 번은 묻는 조합 (gemini.js PHOTO_RETRY_OPTIONS 주석 참고)
            { ...PHOTO_RETRY_OPTIONS, fallbackModels }
        );

        const inbody = data && data.found !== false ? normalizeInbody(data) : null;

        if (!inbody && reserved) {
            await refundPhoto(user.uid);
        }

        // 무엇을 읽었는지 남긴다. 결과지 양식이 기기·세대마다 달라 어느 칸을 못 읽는지 알아야 고친다.
        console.log(
            "[inbody]",
            inbody
                ? `bmr=${inbody.bmr} smm=${inbody.skeletalMuscle} bfm=${inbody.bodyFat} pbf=${inbody.bodyFatPercent} wt=${inbody.weight} vfl=${inbody.visceralFatLevel} score=${inbody.inbodyScore} tbw=${inbody.bodyWater} at=${inbody.measuredAt || "-"}`
                : "인식 실패"
        );

        res.status(200).json({ found: !!inbody, inbody });
    } catch (error) {
        console.error("extractInbody Error:", error.message);
        if (reserved && user) {
            await refundPhoto(user.uid);
        }
        res.status(500).json({ found: false, inbody: null, error: L.error });
    }
});
