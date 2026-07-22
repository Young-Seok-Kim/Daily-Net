const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
// 서버 배포 명령어 : firebase deploy --only functions
exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    // [보안] 인증된 앱의 요청만 허용 (App Check 필수 활성화 필요)
    enforceAppCheck: true,
    timeoutSeconds: 120,
}, async (req, res) => {

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
            // 정확도를 위해 flash 모델 유지 (lite 아님)
            model: "gemini-2.5-flash",
            generationConfig: {
                responseMimeType: "application/json",
                temperature: 0.4,        // 칼로리 산출 일관성 향상
                maxOutputTokens: 4096,
                // ⚡ 속도 개선의 핵심: 기본 'thinking' 지연 제거 (프롬프트의 단계별 사고 지시로 보완)
                thinkingConfig: { thinkingBudget: 0 }
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

        // 💡 [추가] 체중 감량을 위한 하루 권장 영양 섭취량 계산 (일반 활동 계수 1.375 적용 후 500kcal 감량)
        const tdee = Math.round(bmr * 1.375);
        const recommendedCalories = Math.round(tdee - 500); // 안전한 다이어트를 위한 권장 칼로리 목표

        // 다이어트 권장 탄단지 비율 (4:4:2 가이드라인 산출)
        const recCarb = Math.round((recommendedCalories * 0.4) / 4);
        const recProtein = Math.round((recommendedCalories * 0.4) / 4);
        const recFat = Math.round((recommendedCalories * 0.2) / 9);

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
               - 양이 명시되지 않았다면 성인 1인분(표준 중량)을 기준으로 하되, 터무니없는 고칼로리 산출을 절대 금지합니다.
               - 메뉴명이 아닌 식당이름을 명시했을경우 해당 식당에서 사용자가 먹은 메뉴를 예상하여 예상한 메뉴를 기준으로 칼로리를 계산하십시오.
               - 사용자가 비고 혹은 운동에 도보를 명시했을경우 사용자의 키, 몸무게에 따른 소모 칼로리를에 추가하여 계산하십시오.
               - 걸음 수(${stepCount}보)가 0보다 크면, 사용자의 키/몸무게 기준 걸음당 소모 칼로리를 추정해 운동 소모 칼로리(calories.exercise)에 합산하십시오. (도보가 이미 운동/비고에 명시된 경우 중복 계산하지 않도록 주의)
            2. **단계별 사고(Chain of Thought)**: 내부적으로 [메뉴명 -> 예상 중량(g) -> 100g당 칼로리 -> 최종 칼로리] 단계를 거쳐 계산한 뒤 결과값만 JSON에 담으세요.
            3. **전문적 묘사**: 'descriptions'에는 해당 식단의 각 메뉴별로 [장점/단점/개선점]을 탄단지 비율을 포함하여 20자 이내로 코멘트하세요.
            4. **종합 평가**: 사용자의 목표 권장 칼로리(${recommendedCalories} kcal)와 비교하여, 현재 식단이 체중 감량 및 영양 균형에 미치는 영향을 영양학적으로 평가하세요.
            5. **형식 엄수**: (반드시 아래 JSON 형태 그대로 응답해야 합니다)
                - 반드시 아래 JSON 형태를 유지하십시오.
               - 각 끼니의 [calories] 객체와 [macros] 객체, 그리고 [meals] 리스트를 모두 포함해야 합니다.
               - 운동을 안 했더라도 "calories": { "exercise": 0 }을 반드시 포함하십시오.
               - [exercises] 리스트에는 사용자가 한 **운동을 항목별로 나누어** 각 운동의 소모 칼로리(kcal)를 담으세요. 걸음 수(${stepCount}보)가 0보다 크면 "걸음 ${stepCount}보" 항목도 별도로 추가하세요. 운동이 전혀 없으면 빈 배열([])로 두세요.
               - "calories"."exercise"는 [exercises] 리스트의 모든 kcal 합계와 반드시 일치시키세요.
               - Markdown 형식(\`\`\`json) 없이 오직 순수 JSON 문자열만 응답하십시오.
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
        const data = await generateAndParse(model, prompt);

        // 숫자/포맷 안전 헬퍼 (필드 누락 시에도 예외가 나지 않도록)
        const n = (v) => Number(v) || 0;
        const f1 = (v) => n(v).toFixed(1);

        const mealCalories = data.calories || { breakfast: 0, lunch: 0, dinner: 0, snack: 0, exercise: 0 };
        const mealMacros = data.macros || { breakfast: {carb:0, protein:0, fat:0}, lunch: {carb:0, protein:0, fat:0}, dinner: {carb:0, protein:0, fat:0}, snack: {carb:0, protein:0, fat:0} };
        const mealDescriptions = data.descriptions || { breakfast: "", lunch: "", dinner: "", snack: "" };

        const buildMenuList = (mealArray) => {
            if (!Array.isArray(mealArray)) return "정보 없음";
            return mealArray.map(m => `${m.name || '알 수 없음'}(${m.kcal || 0}kcal)`).join(", ");
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
                .map(e => `   • ${e.name || '운동'} (-${Math.abs(e.kcal || 0)}kcal)`)
                .join("\n")
            : "   • 기록된 운동이 없습니다";

        const feedback = `
📋 오늘의 영양 분석 리포트

🍳 아침: ${buildMenuList(data.meals?.breakfast)} (합계: ${sumKcal(data.meals?.breakfast)}kcal)
   [탄 ${f1(bMacros.carb)}g | 단 ${f1(bMacros.protein)}g | 지 ${f1(bMacros.fat)}g]
   💡 ${data.descriptions?.breakfast || ""}

🍳 점심: ${buildMenuList(data.meals?.lunch)} (합계: ${sumKcal(data.meals?.lunch)}kcal)
   [탄 ${f1(lMacros.carb)}g | 단 ${f1(lMacros.protein)}g | 지 ${f1(lMacros.fat)}g]
   💡 ${data.descriptions?.lunch || ""}

🍳 저녁: ${buildMenuList(data.meals?.dinner)} (합계: ${sumKcal(data.meals?.dinner)}kcal)
   [탄 ${f1(dMacros.carb)}g | 단 ${f1(dMacros.protein)}g | 지 ${f1(dMacros.fat)}g]
   💡 ${data.descriptions?.dinner || ""}

🍰 간식: ${buildMenuList(data.meals?.snack)} (합계: ${sumKcal(data.meals?.snack)}kcal)
   [탄 ${f1(sMacros.carb)}g | 단 ${f1(sMacros.protein)}g | 지 ${f1(sMacros.fat)}g]
   💡 ${data.descriptions?.snack || ""}

🔥 운동별 소모 칼로리 (총 -${exerciseCalories}kcal)
${exerciseBreakdown}

---

🎯 다이어트 권장 목표 가이드
• 하루 권장 섭취량: ${recommendedCalories} kcal
• 추천 탄단지: 탄 ${recCarb}g | 단 ${recProtein}g | 지 ${recFat}g
• 나의 오늘 섭취량: ${totalIn} kcal (${totalIn > recommendedCalories ? '⚠️ 권장 초과' : '✅ 권장 이내'})

📊 나의 오늘 실제 탄단지 총합
• 탄수화물: ${Number(totalCarb.toFixed(1))}g / ${recCarb}g
• 단백질: ${Number(totalProtein.toFixed(1))}g / ${recProtein}g
• 지방: ${Number(totalFat.toFixed(1))}g / ${recFat}g

---

🍎 인입 (IN): +${totalIn} kcal
🏃 배출 (OUT): -${totalOut} kcal (기초대사 ${bmr} 포함)
📉 최종 결산: ${netCalories} kcal

💡 전문가 총평
${data.evaluation}
        `.trim();

        res.status(200).json({
            net_calories: netCalories,
            feedback: feedback
        });
    } catch (error) {
        console.error("Internal Error:", error.message);
        res.status(500).json({
            net_calories: 0,
            feedback: "분석 중 오류가 발생했습니다."
        });
    }
});

// 응답 텍스트에서 JSON 오브젝트만 안전하게 추출/파싱
function safeParseJson(rawText) {
    let text = (rawText || "").replace(/```json/g, "").replace(/```/g, "").trim();
    // 앞뒤에 설명 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 파싱
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start !== -1 && end !== -1 && end > start) {
        text = text.substring(start, end + 1);
    }
    return JSON.parse(text);
}

// 생성 + JSON 파싱을 함께 재시도 (503 과부하 및 파싱 실패 모두 대응)
async function generateAndParse(model, prompt, maxRetries = 3) {
    let lastError;
    for (let i = 0; i < maxRetries; i++) {
        try {
            const result = await model.generateContent(prompt);
            const rawText = result.response.text();
            return safeParseJson(rawText); // 파싱까지 성공해야 반환
        } catch (error) {
            lastError = error;
            const msg = error.message || "";
            const isOverload = msg.includes("503") || msg.includes("high demand");
            const isParseError = error instanceof SyntaxError;
            // 과부하(503) 또는 파싱 실패면 재시도, 그 외 에러는 즉시 종료
            if (isOverload || isParseError) {
                const delay = Math.pow(2, i) * 1000; // 1초, 2초, 4초 대기
                console.warn(`재시도(${i + 1}/${maxRetries}) - 사유: ${isParseError ? "JSON 파싱 실패" : "503 과부하"} (${delay}ms 대기)`);
                await new Promise(resolve => setTimeout(resolve, delay));
                continue;
            }
            throw error;
        }
    }
    throw lastError;
}