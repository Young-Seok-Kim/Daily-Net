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
            exercise, remark
        } = req.body;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = genAI.getGenerativeModel({
            model: "gemini-2.5-flash",
            generationConfig: { responseMimeType: "application/json" }
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
            - 비고: ${remark}
            - 목표 권장 칼로리: 하루 ${recommendedCalories} kcal 섭취 권장

            [분석 및 응답 지침] - **Lite 모델 최적화 버전**
            1. **칼로리 산출 로직 고정**:
               - 모든 음식은 식약처 표준 영양 성분 DB를 기준으로 합니다.
               - 양이 명시되지 않았다면 성인 1인분(표준 중량)을 기준으로 하되, 터무니없는 고칼로리 산출을 절대 금지합니다.
               - 메뉴명이 아닌 식당이름을 명시했을경우 해당 식당에서 사용자가 먹은 메뉴를 예상하여 예상한 메뉴를 기준으로 칼로리를 계산하십시오.
               - 사용자가 비고 혹은 운동에 도보를 명시했을경우 사용자의 키, 몸무게에 따른 소모 칼로리를에 추가하여 계산하십시오.
            2. **단계별 사고(Chain of Thought)**: 내부적으로 [메뉴명 -> 예상 중량(g) -> 100g당 칼로리 -> 최종 칼로리] 단계를 거쳐 계산한 뒤 결과값만 JSON에 담으세요.
            3. **전문적 묘사**: 'descriptions'에는 해당 식단의 각 메뉴별로 [장점/단점/개선점]을 탄단지 비율을 포함하여 20자 이내로 코멘트하세요.
            4. **종합 평가**: 사용자의 목표 권장 칼로리(${recommendedCalories} kcal)와 비교하여, 현재 식단이 체중 감량 및 영양 균형에 미치는 영향을 영양학적으로 평가하세요.
            5. **형식 엄수**: (반드시 아래 JSON 형태 그대로 응답해야 합니다)
                - 반드시 아래 JSON 형태를 유지하십시오.
               - 각 끼니의 [calories] 객체와 [macros] 객체, 그리고 [meals] 리스트를 모두 포함해야 합니다.
               - 운동을 안 했더라도 "calories": { "exercise": 0 }을 반드시 포함하십시오.
               - Markdown 형식(\`\`\`json) 없이 오직 순수 JSON 문자열만 응답하십시오.
            {
                "calories": { "breakfast": 0, "lunch": 0, "dinner": 0, "snack": 0, "exercise": 0 },
                "meals": {
                    "breakfast": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "lunch": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "dinner": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }],
                    "snack": [{ "name": "메뉴1", "kcal": 0 }, { "name": "메뉴2", "kcal": 0 }]
                },
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

        const result = await generateWithRetry(model, prompt);
        let rawText = result.response.text();
        // 마크다운 코드 블록 태그 제거
        rawText = rawText.replace(/```json/g, "").replace(/```/g, "").trim();

        const data = JSON.parse(rawText);

        const mealCalories = data.calories || { breakfast: 0, lunch: 0, dinner: 0, snack: 0, exercise: 0 };
        const mealMacros = data.macros || { breakfast: {carb:0, protein:0, fat:0}, lunch: {carb:0, protein:0, fat:0}, dinner: {carb:0, protein:0, fat:0}, snack: {carb:0, protein:0, fat:0} };
        const mealDescriptions = data.descriptions || { breakfast: "", lunch: "", dinner: "", snack: "" };

        const buildMenuList = (mealArray) => {
            if (!Array.isArray(mealArray)) return "정보 없음";
            return mealArray.map(m => `${m.name || '알 수 없음'}(${m.kcal || 0}kcal)`).join(", ");
        };

        const exerciseCalories = Math.abs(data.calories.exercise || 0);
        const totalIn = (data.calories.breakfast || 0) + (data.calories.lunch || 0) + (data.calories.dinner || 0) + (data.calories.snack || 0);
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

        const feedback = `
📋 오늘의 영양 분석 리포트

🍳 아침: ${buildMenuList(data.meals?.breakfast)} (합계: ${sumKcal(data.meals?.breakfast)}kcal)
   [탄 ${data.macros?.breakfast?.carb.toFixed(1) || 0}g | 단 ${data.macros?.breakfast?.protein.toFixed(1) || 0}g | 지 ${data.macros?.breakfast?.fat.toFixed(1) || 0}g]
   💡 ${data.descriptions?.breakfast || ""}

🍳 점심: ${buildMenuList(data.meals?.lunch)} (합계: ${sumKcal(data.meals?.lunch)}kcal)
   [탄 ${data.macros?.lunch?.carb.toFixed(1) || 0}g | 단 ${data.macros?.lunch?.protein.toFixed(1) || 0}g | 지 ${data.macros?.lunch?.fat.toFixed(1) || 0}g]
   💡 ${data.descriptions?.lunch || ""}

🍳 저녁: ${buildMenuList(data.meals?.dinner)} (합계: ${sumKcal(data.meals?.dinner)}kcal)
   [탄 ${data.macros?.dinner?.carb.toFixed(1) || 0}g | 단 ${data.macros?.dinner?.protein.toFixed(1) || 0}g | 지 ${data.macros?.dinner?.fat.toFixed(1) || 0}g]
   💡 ${data.descriptions?.dinner || ""}

🍰 간식: ${buildMenuList(data.meals?.snack)} (합계: ${sumKcal(data.meals?.snack)}kcal)
   [탄 ${data.macros?.snack?.carb.toFixed(1) || 0}g | 단 ${data.macros?.snack?.protein.toFixed(1) || 0}g | 지 ${data.macros?.snack?.fat.toFixed(1) || 0}g]
   💡 ${data.descriptions?.snack || ""}

🔥 운동: ${exercise || '없음'} (-${exerciseCalories}kcal)

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

async function generateWithRetry(model, prompt, maxRetries = 3) {
    let lastError;
    for (let i = 0; i < maxRetries; i++) {
        try {
            return await model.generateContent(prompt);
        } catch (error) {
            lastError = error;
            // 503 과부하 에러일 경우에만 재시도
            if (error.message.includes("503") || error.message.includes("high demand")) {
                const delay = Math.pow(2, i) * 1000; // 1초, 2초, 4초 대기
                console.warn(`Gemini 503 에러 발생, ${i + 1}회 재시도 중... (${delay}ms 대기)`);
                await new Promise(resolve => setTimeout(resolve, delay));
                continue;
            }
            // 다른 에러면 즉시 종료
            throw error;
        }
    }
    throw lastError;
}