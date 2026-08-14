/**
 * 하루 식단·운동을 분석해 리포트를 만드는 함수.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { generateAndParse } = require("./gemini");
const { recommendedIntake } = require("./nutrition");
const { mergeDuplicateItems } = require("./mergeItems");
const { divideSharedItems } = require("./shareItems");
const { implausibleItems, statedKcals } = require("./kcalCheck");
const { implausibleExercises } = require("./exerciseCheck");
const { buildExercises } = require("./exerciseCalc");
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
            exercise: exerciseInput, remark, steps
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

        // 운동 소모는 체중에 비례한다. 모델은 이 곱셈을 못 해서 서버가 한다 (exerciseCalc.js 참고)
        const weightKg = Number(weight) || 70;

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
            - 활동: ${exerciseInput}
            - 걸음 수: ${stepCount}보 (0이면 걸음 데이터 없음)
            - 비고: ${remark}
            - 목표 권장 칼로리: 하루 ${recommendedCalories} kcal 섭취 권장

            [분석 및 응답 지침] - **Lite 모델 최적화 버전**
            1. **칼로리 산출 로직 고정**:
               - 모든 음식은 식약처 표준 영양 성분 DB를 기준으로 합니다.
               - **브랜드·제품명이 적혀 있으면 그 제품이 공표한 값을 쓰십시오.**
                 - 같은 계열의 다른 제품 값으로 대신하지 마십시오. 제로·라이트·무설탕 표기는
                   그 하나로 열량이 0에 가깝게 달라집니다
                 - 제품을 모르면 브랜드를 빼고 일반 메뉴로 계산하십시오. 지어내면 안 됩니다
                 - ⚠️ **사용자가 안 적은 가게·브랜드 이름을 붙이지 마십시오.**
                   이건 적혀 있을 때만 쓰는 규칙입니다. 메뉴명만 적었는데 앞에 가게 이름을
                   붙이면, 사용자는 자기가 안 적은 가게 이름을 자기 기록에서 보게 됩니다.
                   어느 가게인지는 알 수 없고 짐작할 일도 아닙니다
                   - **금지하는 것은 가게·브랜드 이름뿐입니다.** 중량(g·ml)은 오히려 적어야 합니다.
                     아래 [중량 표기] 항목을 따르십시오
               - ⚠️ **열량이 이미 적혀 있으면 그 숫자를 그대로 쓰십시오. 다시 계산하지 마십시오.**
                 (항목 옆에 "... 80g 400kcal"처럼 kcal이 함께 적힌 경우)
                 - 이 숫자는 **사진 속 영양성분표에서 읽었거나 사용자가 직접 적은 것**입니다.
                   둘 다 당신의 추정보다 정확합니다. 포장에 인쇄된 값을 이길 수는 없습니다
                 - 값이 예상과 달라도 그대로 쓰십시오. "낮아 보인다"고 올리거나
                   "높아 보인다"고 깎지 마십시오. **그 판단이 틀려서 생긴 문제를 고치는 중입니다**
                 - 항목 이름에서 kcal 표기는 빼고 담으십시오. (이름은 "... 80g", kcal은 kcal 칸에)
                 - 탄단지(macros)는 적혀 있지 않으므로 그 열량에 맞게 추정하십시오
               - ⚠️ **"개당"(each)이 붙어 있으면 그것은 한 개 값입니다. 개수를 곱하십시오.**
                 **괄호 안의 중량도 한 개 값이라 똑같이 곱해야 합니다.**
                 - 예: "○○ 3개 (개당 39g 171kcal)" → **117g, 513kcal** (39g·171이 아닙니다)
                 - 예: "○○ 1개 (개당 39g 171kcal)" → 39g, 171kcal
                 - 예: "○○ 2캔 (개당 500ml 105kcal)" → 1000ml, 210kcal
                 - 이 표기는 사진 속 영양성분표에서 읽어 **한 개 기준으로 적어둔 것**입니다.
                   사용자가 개수만 고쳐도 총합이 맞게 나오라고 이렇게 적습니다.
                   **곱하지 않으면 몇 개를 먹었든 한 개 값만 계산됩니다**
                 - 곱한 뒤의 값을 kcal 칸에 담고, 이름에는 곱한 중량을 적으십시오 ("○○ 3개 117g")
                 - "개당"이 없이 그냥 "400kcal"이라 적혀 있으면 그건 이미 총합이니 곱하지 마십시오
               - **양이 명시된 항목은 표준 성분표 값을 그대로 환산하십시오. 재추정하지 마십시오.**
                 얼마나 먹었는지가 수량으로 적혀 있으면 **단위가 무엇이든** 해당합니다.
                 - ⚠️ **여기에 단위를 나열하지 않습니다.** 나열하면 나열된 단위만 지키고
                   다른 단위로 적은 양은 재추정하기 때문입니다.
                   **사용자가 양을 정했는가**만 보십시오
                 - 이런 항목은 **답이 하나뿐입니다.** 100ml당 62kcal인 것을 200ml 먹었으면
                   약 124kcal이고, 이 값은 누가 언제 계산해도 같아야 합니다
                 - **개수로 적힌 양은 그 개수가 사용자가 정한 값입니다.**
                   한 단위의 양을 정하고 개수를 곱하십시오.
                   한 단위의 양은 **개수와 무관하게 같아야 합니다**
                   - 실제로 같은 항목이 1마리일 때 1000g으로, 2마리로 고치자 총 700g으로
                     나왔습니다. 개수를 늘렸는데 총량이 줄어든 것입니다.
                     사용자가 개수를 고치면 총량과 열량은 **그 배수만큼 따라 움직여야 합니다**
                   - 여럿이 나눠 먹었다고 함께 적혀 있으면 그 개수는 **전체가 먹은 양**입니다.
                     전체를 그대로 계산해 담으십시오. 몫은 서버가 나눕니다 (아래 나눠 먹기 규칙)
                 - ⚠️ **같은 입력을 다시 분석하면 같은 숫자가 나와야 합니다.**
                   실제로 양이 명시된 같은 항목이 105, 120, 125, 160으로 매번 다르게 나왔습니다.
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
                 - 눈에 안 보이는 기름·설탕·소스를 빠뜨리지 마십시오. 볶거나 무치며 두른 기름,
                   양념의 설탕, 드레싱은 그것만으로 100kcal이 넘는 경우가 흔합니다
                 - 다만 **없는 것을 넣어 올리지도 마십시오.** 적힌 것만 계산하십시오
               - 메뉴명이 아닌 식당이름을 명시했을경우 해당 식당에서 사용자가 먹은 메뉴를 예상하여 예상한 메뉴를 기준으로 칼로리를 계산하십시오.
               - **여러 가지를 함께 먹었다고 적었으면, 각각을 별도 항목으로 나누어 계산하십시오.**
                 타거나, 말거나, 섞거나, 넣거나, 부어서 먹었다는 **뜻이면 모두 해당합니다.**
                 - 예: "A를 B에 타먹음" → [A] + [B] **두 항목**
                 - ⚠️ **해당하는 표현을 일부러 나열하지 않습니다.** 나열하면 그 말이 적혔을 때만
                   나누고, 같은 뜻을 다른 말로 적은 것은 한 항목으로 뭉쳐버립니다.
                   **단어가 아니라 뜻으로 판단하십시오**
                 - 섞은 재료의 양이 없으면 통상적인 양으로 가정하십시오. (마시는 것은 200ml 등)
                 - **물처럼 열량이 없는 것만 빼고, 나머지는 절대 빠뜨리지 마십시오.**
                   타 먹는 재료는 그 자체로 100kcal이 넘는 경우가 많습니다.
               - **양을 금액으로 적었으면 그 금액어치의 양을 추정해 계산하십시오.**
                 (금액 뒤에 어치·치 같은 말이 붙어 "그만큼 샀다"는 뜻이 되는 경우)
                 - **바로 위의 1인분 기준보다 이쪽이 우선입니다.** 몇 만원어치는 1인분이 아닙니다
                 - 추정한 중량을 **항목 이름에 g으로 함께 적으십시오.** (예: "○○ 900g")
                 - **음식점에서 사 먹은 가격을 기준으로 잡으십시오.**
                   마트·정육점에서 사 왔다고 적혀 있을 때만 소매 가격으로 잡습니다
                   - 소매 시세로 잡으면 같은 금액이 두세 배 양으로 부풀어 오릅니다.
                     실제로 그렇게 나왔습니다
                   - 가격은 지역·가게마다 다르므로 그 이상 정밀하게 따지지는 마십시오
               - **여럿이 나눠 먹었다고 적었으면 그 항목에 "sharedBy"(나눠 먹은 사람 수)를
                 담으십시오.** 몇 명이 함께 먹었다는 **뜻이면 어떻게 적혔든 해당합니다.**
                 - ⚠️ **해당하는 표현을 일부러 나열하지 않습니다.** 나열하면 그 말이 적혔을 때만
                   잡고, 같은 뜻을 다른 말로 적은 것은 혼자 다 먹은 것으로 계산됩니다.
                   **단어가 아니라 뜻으로 판단하십시오**
                 - ⚠️ **몫을 직접 나누지 마십시오. 나눗셈은 서버가 합니다.**
                   kcal에는 나누기 전 **전체 열량**(적힌 개수 × 한 단위)을 담고,
                   이름에도 사용자가 적은 **전체 양**을 그대로 적으십시오.
                   calories와 macros도 전체 기준입니다. 서버가 사용자 몫으로 바꿉니다
                   - 나눗셈을 맡겼더니 "4명이서 치킨 2마리"가 지시를 세 번 고치는 동안에도
                     1마리 800g, 0.5마리 ×2, 몫이 아닌 2100kcal로 계속 틀렸습니다.
                     그래서 운동 칼로리처럼 산수를 서버가 가져갔습니다.
                     **당신이 할 일은 "몇 명이서 나눴는가" 판단뿐입니다**
                 - 몇 명인지 안 적혀 있으면 sharedBy를 담지 마십시오. 짐작하면 안 됩니다
                 - **똑같이 나누지 않았다고 적었으면 "myShare"에 사용자 몫의 비율을 담으십시오.**
                   (2명인데 내가 더 먹었으면 0.6처럼. 0과 1 사이 비율만 담고, 곱하지는 마십시오)
                   남들보다 많이 먹었다는 뜻이면 균등하게 나눈 몫보다 크게,
                   적게 먹었다는 뜻이면 균등 몫보다 작게 잡습니다
                   - 확실하지 않게 적었어도 똑같이 반영하십시오.
                     사용자가 기억하는 대로 적은 것이니 무시하지 마십시오
                   - 얼마나 차이 나는지 안 적혀 있으면 균등 몫에서 **조금만** 움직이십시오.
                     (2명이면 6:4 정도) 조금 더 먹었다는 말을 두 배로 해석하면 안 됩니다
               - **[중량 표기] 모든 항목의 이름 끝에 계산에 쓴 중량을 g이나 ml로 적으십시오.**
                 (예: "○○ 900g", "○○ 200ml")
                 - 2번의 단계별 사고에서 **어차피 예상 중량을 정하고 계산합니다.**
                   그 값을 그대로 적으면 됩니다. 새로 지어내는 것이 아닙니다
                 - 이걸 안 적으면 사용자에게는 "○○ 2340kcal"로만 보여서
                   **양을 잘못 잡은 것인지 열량을 잘못 잡은 것인지 구분할 수가 없습니다**
                 - "1개", "1인분", "0.5마리" 같은 표기는 **함께** 남겨도 되지만,
                   그것만 적고 중량을 빼지는 마십시오. ("○○ 1개 80g"처럼 둘 다 좋습니다)
                 - 낱개 포장 제품은 그 포장의 중량을 쓰십시오. 모르면 통상적인 크기로 잡으십시오
               - ⚠️ **[운동] 소모 칼로리를 계산하지 마십시오. 서버가 계산합니다.**
                 - 각 운동에 대해 **시간(분)과 METs만** 담으십시오. kcal 칸은 없습니다
                 - 곱셈을 맡겼더니 체중 ${weightKg}kg을 넣지 않고 70kg 기준으로 계산한 값이
                   계속 나왔습니다. 그래서 산수를 서버가 가져갔습니다.
                   **당신이 할 일은 "몇 분, 몇 METs인가" 판단뿐입니다**
                 - **METs는 그 운동의 표준 신체활동 값을 쓰십시오. 당신은 이미 알고 있습니다.**
                   여기에 종목별 값을 적어두지 않는 것은, 적어두면 적힌 것에만 맞추고
                   적히지 않은 운동을 낮게 깎기 때문입니다
                 - 판단이 안 서면 **강도**로 잡으십시오. 종목을 몰라서 깎는 것이
                   가장 나쁜 실패입니다
                   - 숨이 안 차고 앉아서도 할 수 있는 정도: **2~3**
                   - 숨이 조금 차지만 대화가 이어지는 정도: **3~5**
                   - 숨이 차서 대화가 끊기고 땀이 나는 정도: **6~8**
                   - 말하기 힘들고 오래 못 하는 정도: **9~12**
                   - 몇 분도 못 버티는 전력: **13 이상**
                   - **끝까지 판단이 안 서면 5로 잡으십시오. 3 밑으로 내리지 마십시오.**
                     3 METs는 천천히 걷기입니다. 운동이라고 적어 보낸 것이
                     산책보다 가벼울 수는 없습니다
                 - ⚠️ **위의 [터무니없는 고칼로리 산출 금지]는 음식 규칙입니다.
                   운동에 적용하지 마십시오.**
                   운동을 음식처럼 낮춰 잡으면 하루 소모가 절반으로 줄어듭니다
                 - **사용자가 강도를 적었으면 그 뜻을 METs에 반영하십시오. 양쪽 다입니다.**
                   - 평소보다 세게 했다는 뜻이면 표준값에서 **최대 +30%**
                   - 평소보다 약하게 했다는 뜻이면 표준값에서 **최대 -25%**
                   - 강도에 대한 말이 없으면 **표준값을 그대로 쓰십시오.**
                     어느 쪽으로도 움직이지 마십시오
                   - ⚠️ **여기에 해당하는 표현을 일부러 나열하지 않습니다.**
                     나열하면 그 말이 적혔을 때만 반영하고, 같은 뜻을 다른 말로 적은 것은
                     놓치기 때문입니다. 사람은 같은 뜻을 수없이 다른 말로 적습니다.
                     **단어가 아니라 뜻으로 판단하십시오**
                   - 확실하지 않게 적었어도 똑같이 반영하십시오.
                     사용자가 기억하는 대로 적은 것이니 무시하면 안 됩니다
                 - **강도를 알 수 있는 수치가 적혀 있으면 그것으로 잡으십시오.**
                   종목의 표준값보다 이쪽이 우선입니다
                   - 시간과 거리가 함께 있으면 속도가 나옵니다. **속도가 곧 강도입니다**
                   - 무게·횟수·경사처럼 부하를 알 수 있는 값도 마찬가지입니다
                 - 시간이 안 적혀 있으면 1시간(60분)으로 잡으십시오.
                   그리고 **그 시간을 항목 이름에도 함께 적으십시오.** ("○○ 60분")
                   이걸 안 적으면 시간을 잘못 잡은 것인지 강도를 잘못 잡은 것인지
                   구분할 수가 없습니다
               - ⚠️ **걸음 수(${stepCount}보)는 서버가 직접 계산합니다.
                 [exercises]에 걸음 항목을 넣지 마십시오.** 넣으면 두 번 세어집니다
                 - 대신 **운동을 하는 동안 걸은 걸음 수**를 "stepsInExercise"에 적으십시오
                   - 발로 이동하는 운동이 적혀 있으면, 그 운동에서 걸었을 걸음 수
                   - 제자리에서 하거나 발로 이동하지 않는 운동뿐이면 **0**
                   - 운동이 아예 없으면 **0**
                 - 서버는 이 값만큼만 빼고 나머지 걸음을 셉니다.
                   그러니 **넉넉히 잡지 마십시오.** 그만큼 그날 걸어다닌 걸음이 사라집니다
            2. **단계별 사고(Chain of Thought)**: 내부적으로 아래 단계를 거쳐 계산한 뒤 결과값만 JSON에 담으세요.
               - 음식: [메뉴명 -> 예상 중량(g) -> 100g당 칼로리 -> 최종 칼로리]
               - 운동: [운동명 -> 시간(분) -> METs] **여기까지입니다. 칼로리는 서버가 냅니다**
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
               - [exercises] 리스트에는 사용자가 한 **운동을 항목별로 나누어** 각각의 "minutes"(분)와 "mets"를 담으세요.
                 **kcal은 담지 마십시오. 서버가 계산합니다.** 걸음 항목도 넣지 마십시오. 운동이 전혀 없으면 빈 배열([])로 두세요.
               - "stepsInExercise"에는 운동 중에 걸은 걸음 수를 담으세요. 해당 없으면 0입니다.
               - [meals] 항목 중 **여럿이 나눠 먹은 것에만** "sharedBy"(나눠 먹은 사람 수)를,
                 똑같이 나누지 않았을 때만 "myShare"(사용자 몫의 비율, 0~1)를 함께 담으세요.
                 혼자 먹은 항목에는 두 필드를 넣지 마세요. **몫 나눗셈은 서버가 합니다.**
               - Markdown 형식(\`\`\`json) 없이 오직 순수 JSON 문자열만 응답하십시오.
            6. **응답 언어 (필수)**: JSON 안의 모든 자연어 텍스트를 반드시 **${L.outputLanguage}**로 작성하십시오.
               - 대상: meals[].name, exercises[].name, descriptions의 모든 값, evaluation
               - 사용자가 다른 언어로 입력했더라도 결과는 ${L.outputLanguage}로 번역해서 담으십시오.
               - JSON의 키 이름은 절대 번역하지 말고 아래 형태 그대로 두십시오.
            {
                "calories": { "breakfast": 0, "lunch": 0, "dinner": 0, "snack": 0 },
                "meals": {
                    "breakfast": [{ "name": "메뉴1", "kcal": 0 }, { "name": "나눠 먹은 메뉴", "kcal": 0, "sharedBy": 0, "myShare": 0 }],
                    "lunch": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "dinner": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "snack": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }]
                },
                "exercises": [{ "name": "운동명 60분", "minutes": 60, "mets": 0 }],
                "stepsInExercise": 0,
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

        // 100g당 검산은 나누기 전에 잡는다 — 이름의 중량은 전체인데 kcal만 몫으로 바뀌면
        // 나눠 먹은 항목마다 "낮다"는 거짓 경고가 찍힌다. (경고 출력은 아래 로그 자리에서)
        const stated = statedKcals([breakfast, lunch, dinner, snack]);
        const implausible = implausibleItems(data, stated);

        // 나눠 먹은 몫은 서버가 나눈다. 모델은 전체 열량과 사람 수만 담는다 (shareItems.js 참고)
        const sharedLog = divideSharedItems(data);

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

        // 운동 소모는 **서버가 계산한다.** 모델은 시간과 METs만 준다.
        // (프롬프트에 공식과 체중을 박아넣어도 모델이 70kg 기준 값을 계속 냈다 — exerciseCalc.js 참고)
        const exercise = buildExercises(data, weightKg, stepCount, L.stepsItem);
        const exerciseCalories = exercise.total;

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
        const exerciseItems = exercise.items;
        const exerciseBreakdown = exerciseItems.length > 0
            ? exerciseItems
                .map(e => `   • ${e.name || L.unknownExercise} (-${e.kcal}kcal)`)
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
            // 앱에 나가는 형태는 {name, kcal} 그대로다. minutes·mets는 서버 계산용이라 빼고 보낸다
            exercises: exerciseItems.map(e => ({ name: e.name, kcal: e.kcal }))
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
        // 검산 자체는 몫을 나누기 전에 해두었다 (위의 implausible).
        for (const bad of implausible) {
            console.warn(
                `[kcal] ${bad.name} = ${bad.kcal}kcal / ${bad.grams}g ` +
                `→ 100g당 ${bad.per100} (${bad.reason})`
            );
        }

        // 어떤 항목을 몇으로 나눴는지. 몫이 이상하면 전체가 틀렸는지 나눗셈이 틀렸는지 가른다
        if (sharedLog.length > 0) console.log("[share]", sharedLog.join(" / "));

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

        // 운동은 지금까지 로그가 없었다. 그래서 89.5kg 사용자의 MMA 1시간이 300kcal로
        // 나갔는데도, 사용자가 직접 이상하다고 말할 때까지 알 방법이 없었다.
        //
        // 항목 합계와 모델이 적은 총합을 나란히 남긴다. 순칼로리에 쓰이는 것은 뒤쪽이라
        // 둘이 어긋나면 화면에 보이는 항목과 결산이 따로 논다.
        // 모델이 준 판단(분·METs)과 서버가 낸 값을 함께 남긴다.
        // METs가 안 붙은 항목은 구버전 경로로 떨어져 모델이 곱한 값을 그대로 쓴 것이다.
        const exerciseLog = exerciseItems
            .map((e) => `${e.name || "?"} ${e.kcal}${e.mets > 0 ? `(${e.minutes}분 ${e.mets}METs)` : ""}`)
            .join(" / ");
        console.log(
            `[exercise] 입력="${exerciseInput || ""}" 걸음=${stepCount}` +
            (exercise.overlap > 0 ? `(운동중 ${exercise.overlap} 제외)` : "") +
            ` | ${exerciseLog || "없음"} | 합계=${exerciseCalories}` +
            (exercise.fellBack ? " ⚠️METs없이옴" : "") +
            (exercise.droppedStepItems.length > 0
                ? ` ⚠️걸음항목버림(${exercise.droppedStepItems.join(",")})`
                : "")
        );

        // METs로 역산해 강도가 상식 밖인 운동을 찾는다. 숫자는 그대로 두고 알리기만 한다.
        for (const bad of implausibleExercises({ exercises: exerciseItems }, weightKg)) {
            console.warn(`[mets] ${bad.name} = ${bad.kcal}kcal → ${bad.reason}`);
        }

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
