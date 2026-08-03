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
            2. **포장지·컵·라벨·간판에서 브랜드나 제품명을 읽을 수 있으면 name 앞에 붙이십시오.**
               (예: "아메리카노"가 아니라 "스타벅스 아메리카노", "삼각김밥"이 아니라 "GS25 참치마요 삼각김밥")
               - 브랜드가 확실할 때만 붙이십시오. 비슷해 보인다는 이유로 짐작해 붙이지 마십시오.
               - 글자가 안 보이거나 일반 조리 음식이면 브랜드 없이 메뉴명만 적으십시오.
               - **제로·라이트·무설탕·다이어트 표기를 특히 주의해서 찾으십시오.**
                 (zero, light, no sugar, 무가당, 저칼로리, 슈가프리 등. 작게 인쇄된 경우가 많습니다)
                 - 같은 브랜드라도 이 표기 하나로 열량이 **0에 가깝게 달라집니다.**
                   파워에이드 360mL는 90kcal이지만 제로는 0kcal입니다.
                 - 표기가 있으면 반드시 이름에 넣으십시오. (예: "파워에이드 제로 마운틴블라스트")
                 - **글씨가 작아 확신이 없으면 맛 이름까지 단정하지 말고 브랜드만 적으십시오.**
                   틀린 맛 이름을 붙이면 그 제품의 열량으로 계산되어 오히려 더 크게 틀립니다.
            3. **포장에 용량·중량이 적혀 있으면 그것을 그대로 amount에 옮기십시오.**
               (예: 캔에 "500mL", 봉지에 "90g", 병에 "1.5L"가 보이면 그대로)
               - 이건 눈대중이 아니라 **읽은 사실**이므로 어림짐작보다 우선합니다.
               - 여러 개가 보이면 개수도 함께 적으십시오. (예: "500mL 2캔")
               - 글자가 흐려 확실하지 않으면 적지 마십시오. 숫자를 지어내면 안 됩니다.
            4. 용량이 안 보이는 음식은 양을 눈대중으로 추정해 amount에 적고,
               그 양을 기준으로 칼로리를 산출하십시오.
               - 일상에서 쓰는 짧은 표현으로 적으십시오. (예: 1공기, 1인분, 2개, 반 접시, 조금)
               - 이때는 그램(g)을 쓰지 마십시오. 눈대중한 무게는 사용자가 가늠하기 어렵습니다.
                 (포장에 인쇄된 중량을 옮겨 적는 위 3번과는 다릅니다)
               - 양을 도저히 가늠할 수 없으면 amount를 빈 문자열("")로 두십시오. 지어내지 마십시오.
            5. 음식이 아니거나 식별할 수 없으면 빈 배열([])을 반환하십시오. 추측으로 지어내지 마십시오.
            6. 메뉴명(name)과 양(amount) 모두 반드시 ${L.outputLanguage}로 작성하십시오.
               (브랜드명은 고유명사이므로 원래 표기 그대로 두십시오)
            7. Markdown 없이 순수 JSON만 응답하십시오.

            { "items": [{ "name": "메뉴명", "amount": "1인분", "kcal": 0 }] }
        `;

        const result = await model.generateContent([
            { inlineData: { data: image, mimeType: mimeType || "image/jpeg" } },
            prompt
        ]);

        const data = safeParseJson(result.response.text());
        const items = Array.isArray(data.items)
            ? data.items
                .filter(m => m && m.name)
                .map(m => ({
                    name: String(m.name),
                    // 못 알아본 양은 빈 문자열로 통일한다. 없는 필드와 null을 나눠 다루지 않기 위함.
                    amount: m.amount ? String(m.amount).trim() : "",
                    kcal: Number(m.kcal) || 0
                }))
            : [];

        // 음식을 못 알아봤으면 사용자가 얻은 게 없으므로 횟수를 돌려준다.
        // (시도 횟수는 그대로 남아 악용은 계속 막힌다)
        if (items.length === 0 && reserved) {
            await refundPhoto(user.uid);
        }

        // 사진에서 무엇을 읽어냈는지.
        //
        // 브랜드와 용량을 읽으라고 지시해 뒀지만 **모델이 지키는지는 결과를 봐야 안다.**
        // 이걸 안 남기면 "사진으로 용량이 들어오나?"를 확인할 방법이 없다.
        // (analyzeDiet의 [meals]와 같은 이유다 — 무엇이 들어왔는지 알아야 무엇이 빠졌는지 안다)
        console.log(
            "[extract]",
            items.length === 0
                ? "인식 실패"
                : items.map((m) => `${m.name}${m.amount ? " " + m.amount : ""} ${m.kcal}`).join(" / ")
        );

        // 입력창에 그대로 넣을 수 있는 형태로 합쳐서 준다.
        //
        // 양을 함께 넣는 이유는 두 가지다.
        // 하나는 사용자가 "밥 1공기"를 "밥 반공기"로 고치는 편이 처음부터 타이핑하는 것보다 쉬워서고,
        // 다른 하나는 이 문자열이 그대로 analyzeDiet으로 넘어가기 때문이다.
        // "밥"만 있을 때보다 "밥 1공기"가 있을 때 칼로리 추정이 훨씬 정확해진다.
        //
        // 앱은 이 text만 보고 입력창을 채우므로 구버전에서도 그대로 적용된다.
        res.status(200).json({
            text: items.map(m => (m.amount ? `${m.name} ${m.amount}` : m.name)).join(", "),
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
