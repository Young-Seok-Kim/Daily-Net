/**
 * 하루 식단·운동을 분석해 리포트를 만드는 함수.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { generateAndParse } = require("./gemini");
const { recommendedIntake } = require("./nutrition");
const { mergeDuplicateItems } = require("./mergeItems");
const { implausibleItems, statedKcals } = require("./kcalCheck");
const {
    REQUIRE_AUTH,
    QUOTA_TIMEOUT_MS,
    withTimeout,
    verifyUser,
    reserveAnalysis,
    refundAnalysis
} = require("./quota");

exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    // [보안] 인증된 앱의 요청만 허용 (App Check 필수 활성화 필요)
    enforceAppCheck: true,
    timeoutSeconds: 120,
}, async (req, res) => {

    // 어느 단계에서 시간을 쓰는지 보기 위한 계측. 응답 직전에 한 줄로 남긴다.
    const t0 = Date.now();
    let tAuth = t0;
    let tQuota = t0;

    // 구버전 앱은 language를 보내지 않는다. 그때는 ko로 떨어져 지금까지와 동일하게 동작한다.
    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];

    // 하루 분석 횟수는 서버가 센다. 토큰을 보내지 않는 구버전 앱은 예전처럼 그냥 통과시킨다.
    const user = await verifyUser(req);
    tAuth = Date.now();

    let usage = null;

    if (!user && REQUIRE_AUTH) {
        return res.status(401).json({ net_calories: 0, feedback: L.authRequired });
    }

    if (user) {
        try {
            // 횟수 확인이 느려도 분석 전체가 끌려가지 않도록 상한을 둔다.
            // 못 세는 것보다 사용자를 기다리게 하는 쪽이 나쁘다.
            usage = await withTimeout(reserveAnalysis(user), QUOTA_TIMEOUT_MS);
        } catch (error) {
            console.error("횟수 확인 실패:", error.message);
        }
        tQuota = Date.now();

        if (usage && !usage.allowed) {
            return res.status(429).json({
                net_calories: 0,
                feedback: L.limitReached,
                usage: usage
            });
        }
    }

    try {
        // Retrofit은 데이터를 body에 담아 보냅니다.
        const {
            weight, height, isMale, birthDate,
            breakfast, lunch, dinner, snack,
            exercise, remark, steps
        } = req.body;
        const stepCount = Number(steps) || 0;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = genAI.getGenerativeModel({
            // 무료 등급 한도가 2.5-flash는 하루 20회뿐이라 3.5-flash-lite로 옮겼다 (하루 500회).
            // 예전에 뺐던 lite는 2.5-flash-lite였고 이건 그보다 한 세대 위다.
            // 칼로리 숫자가 흔들리면 "gemini-2.5-flash"로 되돌리고 재배포할 것.
            model: "gemini-3.5-flash-lite",
            generationConfig: {
                responseMimeType: "application/json",
                // 같은 입력을 다시 분석하면 다른 숫자가 나와서 0.4에서 내렸다.
                // 0으로 두지 않은 것은 문장 해석에 약간의 폭이 필요해서다.
                temperature: 0.1,
                maxOutputTokens: 4096,
                // ⚡ 속도 개선의 핵심: 기본 'thinking' 지연 제거 (프롬프트의 단계별 사고 지시로 보완)
                // 3.x 계열은 thinkingBudget을 안 받는다(INVALID_ARGUMENT). thinkingLevel로 지정한다.
                // minimal이면 thinking 토큰 0으로, 기존 thinkingBudget: 0과 같은 효과다.
                thinkingConfig: { thinkingLevel: "minimal" }
            }
        });

// 상단에 성별 텍스트 변환 로직 추가 (genderText 대응)
const genderText = isMale ? '남성' : '여성';

// 1. 나이 및 기초대사량(BMR) 계산
        const calculateAge = (dateStr) => {
            if (!dateStr) return 29;
            const birth = new Date(dateStr);
            const today = new Date();
            let age = today.getFullYear() - birth.getFullYear();
            if (today.getMonth() < birth.getMonth() || (today.getMonth() === birth.getMonth() && today.getDate() < birth.getDate())) age--;
            return age;
        };
        const age = calculateAge(birthDate);
        const bmr = Math.round(10 * weight + 6.25 * height - 5 * age + (isMale ? 5 : -161));

        // 체중 감량을 위한 하루 권장 섭취량. 계산은 nutrition.js가 기준이다.
        // (단백질은 칼로리 비율이 아니라 체중 기준으로 잡는다 — 자세한 이유는 그쪽 주석 참고)
        const recommended = recommendedIntake(bmr, weight);
        const recommendedCalories = recommended.calories;
        const recCarb = recommended.carb;
        const recProtein = recommended.protein;
        const recFat = recommended.fat;

const prompt = `
            당신은 15년 경력의 [베테랑 전문 다이어트 영양사]입니다.
            사용자의 식단과 운동 데이터를 분석하여, 정중하지만 냉철하게 영양 성적표를 작성하세요.

            [사용자 데이터]
            - 신체: ${height}cm, ${weight}kg, ${genderText} (만 ${age}세)
            - 식사: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
            - 활동: ${exercise}
            - 걸음 수: ${stepCount}보 (0이면 걸음 데이터 없음)
            - 비고: ${remark}
            - 목표 권장 칼로리: 하루 ${recommendedCalories} kcal 섭취 권장

            [분석 및 응답 지침] - **Lite 모델 최적화 버전**
            1. **칼로리 산출 로직 고정**:
               - 모든 음식은 식약처 표준 영양 성분 DB를 기준으로 합니다.
               - **브랜드·제품명이 적혀 있으면 그 제품이 공표한 값을 쓰십시오.**
                 ("스타벅스 아메리카노 톨", "코카콜라 제로 355ml", "GS25 참치마요 삼각김밥")
                 - 같은 계열의 다른 제품 값으로 대신하지 마십시오. 제로·라이트·무설탕 표기는
                   그 하나로 열량이 0에 가깝게 달라집니다
                 - 제품을 모르면 브랜드를 빼고 일반 메뉴로 계산하십시오. 지어내면 안 됩니다
                 - ⚠️ **사용자가 안 적은 가게·브랜드 이름을 붙이지 마십시오.**
                   이건 적혀 있을 때만 쓰는 규칙입니다. "아메리카노"라고 적었는데
                   "스타벅스 아메리카노"로 바꾸면 사용자는 자기가 안 적은 가게 이름을
                   자기 기록에서 보게 됩니다. 어느 가게인지는 알 수 없고 짐작할 일도 아닙니다
                   - **금지하는 것은 가게·브랜드 이름뿐입니다.** 중량(g·ml)은 오히려 적어야 합니다.
                     아래 [중량 표기] 항목을 따르십시오
               - ⚠️ **열량이 이미 적혀 있으면 그 숫자를 그대로 쓰십시오. 다시 계산하지 마십시오.**
                 ("씨리얼 초코 80g 400kcal", "삼각김밥 190kcal"처럼 kcal이 함께 적힌 경우)
                 - 이 숫자는 **사진 속 영양성분표에서 읽었거나 사용자가 직접 적은 것**입니다.
                   둘 다 당신의 추정보다 정확합니다. 포장에 인쇄된 값을 이길 수는 없습니다
                 - 값이 예상과 달라도 그대로 쓰십시오. "낮아 보인다"고 올리거나
                   "높아 보인다"고 깎지 마십시오. **그 판단이 틀려서 생긴 문제를 고치는 중입니다**
                 - 항목 이름에서 kcal 표기는 빼고 담으십시오. ("씨리얼 초코 80g", kcal은 kcal 칸에)
                 - 탄단지(macros)는 적혀 있지 않으므로 그 열량에 맞게 추정하십시오
               - ⚠️ **"개당"(each)이 붙어 있으면 그것은 한 개 값입니다. 개수를 곱하십시오.**
                 **괄호 안의 중량도 한 개 값이라 똑같이 곱해야 합니다.**
                 - 예: "초코파이 3개 (개당 39g 171kcal)" → **117g, 513kcal** (39g·171이 아닙니다)
                 - 예: "초코파이 1개 (개당 39g 171kcal)" → 39g, 171kcal
                 - 예: "코카콜라 2캔 (개당 500ml 105kcal)" → 1000ml, 210kcal
                 - 이 표기는 사진 속 영양성분표에서 읽어 **한 개 기준으로 적어둔 것**입니다.
                   사용자가 개수만 고쳐도 총합이 맞게 나오라고 이렇게 적습니다.
                   **곱하지 않으면 몇 개를 먹었든 한 개 값만 계산됩니다**
                 - 곱한 뒤의 값을 kcal 칸에 담고, 이름에는 곱한 중량을 적으십시오 ("초코파이 3개 117g")
                 - "개당"이 없이 그냥 "400kcal"이라 적혀 있으면 그건 이미 총합이니 곱하지 마십시오
               - **양이 명시된 항목은 표준 성분표 값을 그대로 환산하십시오. 재추정하지 마십시오.**
                 ("우유 200ml", "닭가슴살 100g", "밥 1공기"처럼 양이 이미 정해진 것)
                 - 이런 항목은 **답이 하나뿐입니다.** 우유 200ml는 100ml당 약 62kcal이므로
                   약 124kcal이고, 이 값은 누가 언제 계산해도 같아야 합니다
                 - ⚠️ **같은 입력을 다시 분석하면 같은 숫자가 나와야 합니다.**
                   실제로 "우유 200ml"이 105, 120, 125, 160으로 매번 다르게 나왔습니다.
                   사용자는 날짜별 추세를 보는데, 먹은 것이 같은데 숫자가 흔들리면
                   **살이 빠지는지 찌는지를 알 수 없게 됩니다.** 값이 조금 틀리는 것보다 나쁩니다
                 - 폭을 두지 말고 그 음식의 **대표값 하나**를 쓰십시오
               - 양이 명시되지 않았다면 성인 1인분(표준 중량)을 기준으로 하되, 터무니없는 고칼로리 산출을 절대 금지합니다.
               - **확실하지 않을 때 한쪽으로 치우치지 마십시오. 통상적인 값을 쓰십시오.**
                 - 바로 위의 "터무니없는 고칼로리 금지"는 말 그대로 **터무니없는 값**을 막으라는 것이지,
                   애매할 때마다 낮은 쪽으로 깎으라는 뜻이 아닙니다.
                   항목마다 조금씩 깎으면 하루 총합에서 수백 kcal이 조용히 사라집니다
                 - 조리법이 적혀 있으면 반드시 반영하십시오. 튀기거나 볶은 것은 기름이 더해지고
                   굽거나 찐 것은 그렇지 않습니다. 안 적혀 있으면 그 음식의 **가장 흔한 조리법**으로 잡으십시오
                 - 눈에 안 보이는 기름·설탕·소스를 빠뜨리지 마십시오. 볶음밥의 기름,
                   양념의 설탕, 샐러드 드레싱은 그것만으로 100kcal이 넘는 경우가 흔합니다
                 - 다만 **없는 것을 넣어 올리지도 마십시오.** 적힌 것만 계산하십시오
               - 메뉴명이 아닌 식당이름을 명시했을경우 해당 식당에서 사용자가 먹은 메뉴를 예상하여 예상한 메뉴를 기준으로 칼로리를 계산하십시오.
               - **무언가에 타거나 섞어 먹었다고 적었으면, 섞은 재료도 반드시 별도 항목으로 나누어 계산하십시오.**
                 ("~에 타서", "~에 말아", "~와 섞어", "~를 넣고", "~에 부어" 등)
                 - 예: "프로틴을 우유에 타먹음" → [프로틴 1스쿱] + [우유 200ml] **두 항목**
                 - 예: "밥을 국에 말아먹음" → [밥 1공기] + [국 1그릇] **두 항목**
                 - 섞은 재료의 양이 없으면 통상적인 양으로 가정하십시오. (우유 200ml, 물 0kcal 등)
                 - **물처럼 열량이 없는 것만 빼고, 나머지는 절대 빠뜨리지 마십시오.**
                   타 먹는 재료(우유·두유·요거트)는 그 자체로 100kcal이 넘습니다.
               - **금액으로 적었으면 그 금액어치의 양을 추정해 계산하십시오.** ("3만원어치", "만원치", "2만원 어치")
                 - **바로 위의 1인분 기준보다 이쪽이 우선입니다.** 3만원어치 삼겹살은 1인분이 아닙니다
                 - 추정한 중량을 **항목 이름에 g으로 함께 적으십시오.** (예: "삼겹살 900g")
                 - **음식점에서 사 먹은 가격을 기준으로 잡으십시오.**
                   마트·정육점에서 사 왔다고 적혀 있을 때만 소매 가격으로 잡습니다
                   - 예: 삼겹살 3만원어치는 음식점 기준 **2인분(약 400g)**입니다. 1kg이 아닙니다
                     (마트 시세로 잡으면 1kg이 나온다. 실제로 그렇게 나왔다)
                   - 가격은 지역·가게마다 다르므로 그 이상 정밀하게 따지지는 마십시오
               - **여럿이 나눠 먹었다고 적었으면 사용자가 먹은 몫만 계산하십시오.**
                 ("3명이서", "둘이서 나눠", "친구랑 반씩", "N등분")
                 - 사용자 몫의 중량을 **항목 이름에 g으로 적으십시오.** (예: "탕수육 250g")
                 - 몇 명인지 안 적혀 있으면 나누지 마십시오. 짐작해서 나누면 안 됩니다
                 - **똑같이 나누지 않았다고 적었으면 그 말을 반영하십시오.**
                   ("내가 좀 더 먹음" → 절반보다 많게, "조금만 먹음" → 절반보다 적게)
                   - "~인 것 같음", "~한 듯" 처럼 확실하지 않게 적어도 똑같이 반영하십시오.
                     사용자가 기억하는 대로 적은 것이니 무시하지 마십시오
                   - 얼마나 더 먹었는지 안 적혀 있으면 **6:4 정도로만** 잡으십시오.
                     "좀 더"를 두 배로 해석하면 안 됩니다
               - **[중량 표기] 모든 항목의 이름 끝에 계산에 쓴 중량을 g이나 ml로 적으십시오.**
                 (예: "삼겹살 900g", "우유 200ml", "닭강정 400g", "바나나 150g")
                 - 2번의 단계별 사고에서 **어차피 예상 중량을 정하고 계산합니다.**
                   그 값을 그대로 적으면 됩니다. 새로 지어내는 것이 아닙니다
                 - 이걸 안 적으면 사용자에게는 "삼겹살 2340kcal"로만 보여서
                   **양을 잘못 잡은 것인지 열량을 잘못 잡은 것인지 구분할 수가 없습니다**
                 - "1개", "1인분", "0.5마리" 같은 표기는 **함께** 남겨도 되지만,
                   그것만 적고 중량을 빼지는 마십시오. ("핫바 1개 80g"처럼 둘 다 좋습니다)
                 - 낱개 포장 제품은 그 포장의 중량을 쓰십시오. 모르면 통상적인 크기로 잡으십시오
               - 사용자가 비고 혹은 운동에 도보를 명시했을경우 사용자의 키, 몸무게에 따른 소모 칼로리를에 추가하여 계산하십시오.
               - 걸음 수(${stepCount}보)가 0보다 크면, 사용자의 키/몸무게 기준 걸음당 소모 칼로리를 추정해 운동 소모 칼로리(calories.exercise)에 합산하십시오. (도보가 이미 운동/비고에 명시된 경우 중복 계산하지 않도록 주의)
            2. **단계별 사고(Chain of Thought)**: 내부적으로 [메뉴명 -> 예상 중량(g) -> 100g당 칼로리 -> 최종 칼로리] 단계를 거쳐 계산한 뒤 결과값만 JSON에 담으세요.
            3. **전문적 묘사**: 'descriptions'에는 해당 식단의 각 메뉴별로 [장점/단점/개선점]을 탄단지 비율을 포함하여 20자 이내로 코멘트하세요.
            4. **종합 평가**: 오늘 **먹은 것의 구성**을 한 줄로 평가하세요. 무엇이 넘치고 무엇이 빠졌는지만 씁니다.
               - ⚠️ **칼로리 숫자를 적지 마십시오.** 목표치도, 섭취량도 쓰지 마십시오.
                 "2000kcal 대비", "총 1375kcal로" 같은 표현을 모두 금지합니다.
               - ⚠️ **살이 빠질지 찔지 판정하지 마십시오.**
                 "체중 감량에 불리합니다", "감량에 도움이 됩니다", "칼로리 초과입니다" 같은 표현을 금지합니다.
               - 두 가지를 모두 금지하는 이유는 같습니다. **숫자도 판정도 서버가 계산해
                 바로 위 칸에 이미 적어두기 때문입니다.** 여기서 한 번 더 말하면 서로 어긋납니다.
                 (실제로 어긋났다 — 적자인 날에 "감량에 불리하다"고 적어 보냈다)
               - 남는 것은 **식단의 질**입니다. 끼니 거름, 채소·단백질 부족, 지방·나트륨 과다,
                 특정 끼니에 몰아 먹음 같은 것을 지적하십시오.
                 (예: 아침을 거르고 저녁에 지방이 몰려 하루 배분이 고르지 않습니다)
            5. **형식 엄수**: (반드시 아래 JSON 형태 그대로 응답해야 합니다)
                - 반드시 아래 JSON 형태를 유지하십시오.
               - 각 끼니의 [calories] 객체와 [macros] 객체, 그리고 [meals] 리스트를 모두 포함해야 합니다.
               - 운동을 안 했더라도 "calories": { "exercise": 0 }을 반드시 포함하십시오.
               - [exercises] 리스트에는 사용자가 한 **운동을 항목별로 나누어** 각 운동의 소모 칼로리(kcal)를 담으세요. 걸음 수(${stepCount}보)가 0보다 크면 "걸음 ${stepCount}보" 항목도 별도로 추가하세요. 운동이 전혀 없으면 빈 배열([])로 두세요.
               - "calories"."exercise"는 [exercises] 리스트의 모든 kcal 합계와 반드시 일치시키세요.
               - Markdown 형식(\`\`\`json) 없이 오직 순수 JSON 문자열만 응답하십시오.
            6. **응답 언어 (필수)**: JSON 안의 모든 자연어 텍스트를 반드시 **${L.outputLanguage}**로 작성하십시오.
               - 대상: meals[].name, exercises[].name, descriptions의 모든 값, evaluation
               - 사용자가 다른 언어로 입력했더라도 결과는 ${L.outputLanguage}로 번역해서 담으십시오.
               - JSON의 키 이름은 절대 번역하지 말고 아래 형태 그대로 두십시오.
            {
                "calories": { "breakfast": 0, "lunch": 0, "dinner": 0, "snack": 0, "exercise": 0 },
                "meals": {
                    "breakfast": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "lunch": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "dinner": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "snack": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }]
                },
                "exercises": [{ "name": "운동명", "kcal": 0 }],
                "macros": {
                    "breakfast": { "carb": 0, "protein": 0, "fat": 0 },
                    "lunch": { "carb": 0, "protein": 0, "fat": 0 },
                    "dinner": { "carb": 0, "protein": 0, "fat": 0 },
                    "snack": { "carb": 0, "protein": 0, "fat": 0 }
                },
                "descriptions": {
                    "breakfast": "메뉴명 + 영양학적 한줄평",
                    "lunch": "메뉴명 + 영양학적 한줄평",
                    "dinner": "메뉴명 + 영양학적 한줄평",
                    "snack": "메뉴명 + 영양학적 한줄평"
                },
                "evaluation": "오늘 식단에 대한 전문적인 종합 평가 한 줄"
            }
        `;

        // ⚙️ 생성 + JSON 파싱을 함께 재시도하여 간헐적 파싱 실패를 방지
        //
        // 세 번 다 실패하면 잘린 응답에서라도 건져 쓴다. 다만 **숫자가 다 살아났을 때만** 쓴다.
        // 스키마 순서가 calories → meals → exercises → macros → descriptions → evaluation 이라,
        // macros까지 왔으면 계산에 쓰는 값은 전부 온 것이고 뒤에 빠진 건 한줄평뿐이다.
        // (한줄평은 아래에서 기본값으로 떨어져 빈 칸으로 나간다)
        const tPrompt = Date.now();
        const data = await generateAndParse(model, prompt, {
            salvageIfHas: ["calories", "meals", "macros"]
        });
        const tGemini = Date.now();

        // 모델이 "코카콜라 2개"를 같은 이름 두 줄로 쪼개 놓는 일이 잦다.
        // 합계는 맞지만 리포트에 같은 메뉴가 두 번 뜨므로 한 줄로 합친다.
        const mergedMeals = mergeDuplicateItems(data);

        // 총평이 **숫자를 쓰거나 살이 빠질지 찔지 판정하면** 프롬프트 지시를 어긴 것이다.
        //
        // 둘 다 서버가 이미 바로 위 칸에 적어둔 내용이라, 여기서 반복하면 서로 어긋난다.
        // 실제로 두 번 겪었다.
        //   1) 섭취 1,375kcal인 날에 "목표 2000kcal 대비 높다" (권장은 1,738이고 방향도 반대였다)
        //   2) 숫자를 막았더니 "체중 감량에 불리합니다"로 판정만 남아 같은 충돌이 재발했다
        //
        // 텍스트를 고쳐 쓰지는 않는다. 남의 문장을 기계로 손대면 더 이상해진다.
        // 대신 남겨두고 본다. 계속 나오면 프롬프트를 더 조여야 한다는 신호다.
        const evaluation = data.evaluation || "";
        if (/\d[\d,]*\s*(kcal|칼로리)/i.test(evaluation)) {
            console.warn(`[eval] 총평에 칼로리 숫자가 들어감: ${evaluation}`);
        }
        if (/(감량|증량|체중 ?(감소|증가)|칼로리 ?(초과|부족))/.test(evaluation)) {
            console.warn(`[eval] 총평이 체중 방향을 판정함: ${evaluation}`);
        }

        // 숫자/포맷 안전 헬퍼 (필드 누락 시에도 예외가 나지 않도록)
        const n = (v) => Number(v) || 0;
        const f1 = (v) => n(v).toFixed(1);

        const mealCalories = data.calories || { breakfast: 0, lunch: 0, dinner: 0, snack: 0, exercise: 0 };
        const mealMacros = data.macros || { breakfast: {carb:0, protein:0, fat:0}, lunch: {carb:0, protein:0, fat:0}, dinner: {carb:0, protein:0, fat:0}, snack: {carb:0, protein:0, fat:0} };
        const mealDescriptions = data.descriptions || { breakfast: "", lunch: "", dinner: "", snack: "" };

        const buildMenuList = (mealArray) => {
            if (!Array.isArray(mealArray)) return L.noInfo;
            return mealArray.map(m => `${m.name || L.unknownMenu}(${m.kcal || 0}kcal)`).join(", ");
        };

        const exerciseCalories = Math.abs(n(mealCalories.exercise));
        const totalIn = n(mealCalories.breakfast) + n(mealCalories.lunch) + n(mealCalories.dinner) + n(mealCalories.snack);
        const totalOut = bmr + exerciseCalories;
        const netCalories = totalIn - totalOut;

        const bMacros = data.macros?.breakfast || { carb: 0, protein: 0, fat: 0 };
                const lMacros = data.macros?.lunch || { carb: 0, protein: 0, fat: 0 };
                const dMacros = data.macros?.dinner || { carb: 0, protein: 0, fat: 0 };
                const sMacros = data.macros?.snack || { carb: 0, protein: 0, fat: 0 };

                const totalCarb = (bMacros.carb || 0) + (lMacros.carb || 0) + (dMacros.carb || 0) + (sMacros.carb || 0);
                const totalProtein = (bMacros.protein || 0) + (lMacros.protein || 0) + (dMacros.protein || 0) + (sMacros.protein || 0);
                const totalFat = (bMacros.fat || 0) + (lMacros.fat || 0) + (dMacros.fat || 0) + (sMacros.fat || 0);
// 🎯 식사 항목별 탄단지 변수 가독성 좋게 매핑
        const bCarb = bMacros.carb || 0, bProg = bMacros.protein || 0, bFat = bMacros.fat || 0;
        const lCarb = lMacros.carb || 0, lProg = lMacros.protein || 0, lFat = lMacros.fat || 0;
        const dCarb = dMacros.carb || 0, dProg = dMacros.protein || 0, dFat = dMacros.fat || 0;
        const sCarb = sMacros.carb || 0, sProg = sMacros.protein || 0, sFat = sMacros.fat || 0;

        // 메뉴 리스트의 kcal을 모두 더하는 함수
        const sumKcal = (mealArray) => {
            if (!Array.isArray(mealArray)) return 0;
            return mealArray.reduce((acc, m) => acc + (m.kcal || 0), 0);
        };

        // 🔥 운동별 소모 칼로리 목록 생성
        const exerciseItems = Array.isArray(data.exercises) ? data.exercises : [];
        const exerciseBreakdown = exerciseItems.length > 0
            ? exerciseItems
                .map(e => `   • ${e.name || L.unknownExercise} (-${Math.abs(e.kcal || 0)}kcal)`)
                .join("\n")
            : L.noExercise;

        // 탄단지 한 줄. 언어별 약어(탄/단/지, C/P/F)만 달라지므로 함수로 묶는다.
        const macroLine = (m) =>
            `   [${L.carb} ${f1(m.carb)}g | ${L.protein} ${f1(m.protein)}g | ${L.fat} ${f1(m.fat)}g]`;

        const feedback = `
${L.reportTitle}

${L.breakfast}: ${buildMenuList(data.meals?.breakfast)} (${L.total}: ${sumKcal(data.meals?.breakfast)}kcal)
${macroLine(bMacros)}
   💡 ${data.descriptions?.breakfast || ""}

${L.lunch}: ${buildMenuList(data.meals?.lunch)} (${L.total}: ${sumKcal(data.meals?.lunch)}kcal)
${macroLine(lMacros)}
   💡 ${data.descriptions?.lunch || ""}

${L.dinner}: ${buildMenuList(data.meals?.dinner)} (${L.total}: ${sumKcal(data.meals?.dinner)}kcal)
${macroLine(dMacros)}
   💡 ${data.descriptions?.dinner || ""}

${L.snack}: ${buildMenuList(data.meals?.snack)} (${L.total}: ${sumKcal(data.meals?.snack)}kcal)
${macroLine(sMacros)}
   💡 ${data.descriptions?.snack || ""}

${L.exerciseTitle} (${L.totalBurned} -${exerciseCalories}kcal)
${exerciseBreakdown}

---

${L.goalTitle}
${L.goalIntake}: ${recommendedCalories} kcal
${L.goalMacro}: ${L.carb} ${recCarb}g | ${L.protein} ${recProtein}g | ${L.fat} ${recFat}g
${L.myIntake}: ${totalIn} kcal (${totalIn > recommendedCalories ? L.over : L.under})

${L.macroTitle}
${L.carbFull}: ${Number(totalCarb.toFixed(1))}g / ${recCarb}g
${L.proteinFull}: ${Number(totalProtein.toFixed(1))}g / ${recProtein}g
${L.fatFull}: ${Number(totalFat.toFixed(1))}g / ${recFat}g

---

${L.inLabel}: +${totalIn} kcal
${L.outLabel}: -${totalOut} kcal (${L.bmrNote(bmr)})
${L.netLabel}: ${netCalories} kcal

${L.evalTitle}
${data.evaluation}
        `.trim();

        // 항목 배열을 {name, kcal} 형태로 정리한다. AI가 필드를 빠뜨려도 안전하도록 기본값을 채운다.
        const normalizeItems = (arr, fallbackName) => Array.isArray(arr)
            ? arr.map(m => ({ name: String(m.name || fallbackName), kcal: n(m.kcal) }))
            : [];

        // 앱이 차트·통계로 쓸 수 있도록 계산 결과를 구조화해서 함께 내려보낸다.
        //
        // feedback(사람이 읽는 글)은 그대로 유지해야 한다. 구버전 앱은 그것만 보고 화면을 그리며,
        // 여기 structured는 모르는 필드라 Gson이 조용히 무시한다. 그래서 이 추가는 구버전을 깨지 않는다.
        const structured = {
            bmr: bmr,
            recommended: {
                calories: recommendedCalories,
                carb: recCarb,
                protein: recProtein,
                fat: recFat
            },
            calories: {
                breakfast: n(mealCalories.breakfast),
                lunch: n(mealCalories.lunch),
                dinner: n(mealCalories.dinner),
                snack: n(mealCalories.snack),
                exercise: exerciseCalories
            },
            // 하루 총 탄단지 (끼니별 합계)
            macros: {
                carb: Number(totalCarb.toFixed(1)),
                protein: Number(totalProtein.toFixed(1)),
                fat: Number(totalFat.toFixed(1))
            },
            totals: {
                intake: totalIn,
                burned: totalOut,
                net: netCalories
            },
            meals: {
                breakfast: normalizeItems(data.meals?.breakfast, L.unknownMenu),
                lunch: normalizeItems(data.meals?.lunch, L.unknownMenu),
                dinner: normalizeItems(data.meals?.dinner, L.unknownMenu),
                snack: normalizeItems(data.meals?.snack, L.unknownMenu)
            },
            exercises: normalizeItems(data.exercises, L.unknownExercise)
        };

        console.log(
            `[timing] auth=${tAuth - t0}ms quota=${tQuota - tAuth}ms ` +
            `prep=${tPrompt - tQuota}ms gemini=${tGemini - tPrompt}ms ` +
            `total=${Date.now() - t0}ms`
        );
        if (mergedMeals.length > 0) {
            console.log("[merge] 같은 메뉴를 합친 끼니:", mergedMeals.join(","));
        }

        // 100g당 열량이 상식 밖인 항목. 숫자는 그대로 두고 알리기만 한다.
        // 이게 계속 찍히면 프롬프트를 손볼 때가 된 것이다.
        //
        // 입력에 kcal이 적혀 있던 값은 범주 밴드로 재지 않는다 (성분표에서 읽었거나 사용자가 적은 것).
        const stated = statedKcals([breakfast, lunch, dinner, snack]);
        for (const bad of implausibleItems(data, stated)) {
            console.warn(
                `[kcal] ${bad.name} = ${bad.kcal}kcal / ${bad.grams}g ` +
                `→ 100g당 ${bad.per100} (${bad.reason})`
            );
        }

        // 칼로리가 어떻게 나왔는지 볼 수 있는 유일한 자리다. 숫자가 이상하면
        // 항목이 빠진 것인지 양을 잘못 잡은 것인지를 여기서 가른다.
        // 끼니 이름은 L(언어별 라벨)을 쓰지 않는다. 사용자 언어에 따라 로그 모양이 바뀌면
        // 나중에 찾아보기 어렵고, 이모지까지 섞여 읽기 나쁘다. 로그는 늘 같은 말로 남긴다.
        const MEAL_LOG_NAMES = { breakfast: "아침", lunch: "점심", dinner: "저녁", snack: "간식" };
        const mealLog = Object.entries(MEAL_LOG_NAMES)
            .map(([meal, label]) => {
                const items = data.meals?.[meal];
                if (!Array.isArray(items) || items.length === 0) return null;
                const listed = items
                    .map((m) => `${m.name || "?"} ${n(m.kcal)}`)
                    .join(" / ");
                return `${label}: ${listed}`;
            })
            .filter(Boolean)
            .join(" | ");
        if (mealLog) console.log("[meals]", mealLog);

        res.status(200).json({
            net_calories: netCalories,
            feedback: feedback,
            structured: structured,
            usage: usage
        });
    } catch (error) {
        console.error("Internal Error:", error.message);

        // 분석이 실패했으면 차감했던 횟수를 되돌려준다
        if (user && usage && usage.allowed) {
            await refundAnalysis(user.uid);
        }

        res.status(500).json({
            net_calories: 0,
            feedback: L.error
        });
    }
});
