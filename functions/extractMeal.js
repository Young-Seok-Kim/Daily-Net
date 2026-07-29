/**
 * 음식 사진에서 메뉴명을 읽어오는 함수.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { safeParseJson } = require("./gemini");
const { verifyUser, reservePhoto, refundPhoto } = require("./quota");

/**
 * 음식 사진에서 메뉴를 읽어 입력창에 채울 텍스트로 바꿔준다.
 *
 * 분석(analyzeDiet)과 분리한 이유:
 * - 분석 횟수를 소모하면 안 된다. 사진은 "입력을 돕는" 단계일 뿐이다
 * - 실패해도 분석 경로에 영향이 없어야 한다
 * - 응답이 짧아 타임아웃도 훨씬 짧게 잡을 수 있다
 *
 * 결과를 그대로 확정하지 않고 입력창 초안으로 채워 사용자가 고치게 하는 것을 전제로 한다.
 * AI의 양(그램) 추정은 정확하지 않기 때문이다.
 */
exports.extractMeal = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 60,
}, async (req, res) => {
    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];

    // 결과를 못 냈을 때 차감분을 되돌리려면 catch에서도 알아야 한다
    let user = null;
    let reserved = false;

    try {
        const { image, mimeType } = req.body;

        if (!image) {
            return res.status(400).json({ text: "", items: [], error: "image required" });
        }

        // 이 함수는 b24에서 처음 생겼다. 호출하는 앱은 전부 토큰을 보내므로
        // analyzeDiet과 달리 구버전 호환을 걱정할 필요 없이 인증을 요구할 수 있다.
        user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ text: "", items: [], error: L.authRequired });
        }

        // 분석 횟수와는 별개로 사진 인식에도 하루 상한이 있다 (무료 3회 / 구독 30회).
        const photo = await reservePhoto(user);
        if (!photo.allowed) {
            return res.status(429).json({
                text: "",
                items: [],
                // 무료 사용자에게는 "구독하면 더 쓸 수 있다"를, 구독자에게는 그냥 한도 안내를 보낸다
                error: photo.paid ? L.photoLimitReached : L.photoLimitFree,
                paid: photo.paid
            });
        }
        reserved = true;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = genAI.getGenerativeModel({
            model: "gemini-2.5-flash",
            generationConfig: {
                responseMimeType: "application/json",
                temperature: 0.2, // 메뉴 인식은 창의성이 필요 없다
                maxOutputTokens: 1024,
                thinkingConfig: { thinkingBudget: 0 }
            }
        });

        const prompt = `
            이 사진에 담긴 음식을 식별하십시오.

            [지침]
            1. 보이는 메뉴를 각각 항목으로 나누십시오. 반찬처럼 여러 개가 함께 있으면 묶어도 됩니다.
            2. 각 메뉴의 양을 눈대중으로 추정해 1인분 기준 칼로리를 산출하십시오.
            3. 음식이 아니거나 식별할 수 없으면 빈 배열([])을 반환하십시오. 추측으로 지어내지 마십시오.
            4. 메뉴명은 반드시 ${L.outputLanguage}로 작성하십시오.
            5. Markdown 없이 순수 JSON만 응답하십시오.

            { "items": [{ "name": "메뉴명", "kcal": 0 }] }
        `;

        const result = await model.generateContent([
            { inlineData: { data: image, mimeType: mimeType || "image/jpeg" } },
            prompt
        ]);

        const data = safeParseJson(result.response.text());
        const items = Array.isArray(data.items)
            ? data.items
                .filter(m => m && m.name)
                .map(m => ({ name: String(m.name), kcal: Number(m.kcal) || 0 }))
            : [];

        // 음식을 못 알아봤으면 사용자가 얻은 게 없으므로 횟수를 돌려준다.
        // (시도 횟수는 그대로 남아 악용은 계속 막힌다)
        if (items.length === 0 && reserved) {
            await refundPhoto(user.uid);
        }

        // 입력창에 그대로 넣을 수 있는 형태로 합쳐서 준다
        res.status(200).json({
            text: items.map(m => m.name).join(", "),
            items: items
        });
    } catch (error) {
        console.error("extractMeal Error:", error.message);

        // 오류로 끝났으면 차감분을 되돌린다
        if (reserved && user) {
            await refundPhoto(user.uid);
        }

        res.status(500).json({ text: "", items: [], error: L.error });
    }
});
