const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");
// 서버 배포 명령어 : firebase deploy --only functions

/**
 * 리포트에 들어가는 고정 문구. AI가 쓰는 문장이 아니라 서버가 조립하는 부분이라 여기서 번역한다.
 * 언어를 추가하려면 이 표에 항목 하나만 더 넣으면 된다.
 */
const LABELS = {
    ko: {
        outputLanguage: "한국어",
        reportTitle: "📋 오늘의 영양 분석 리포트",
        breakfast: "🍳 아침", lunch: "🍳 점심", dinner: "🍳 저녁", snack: "🍰 간식",
        total: "합계",
        carb: "탄", protein: "단", fat: "지",
        noInfo: "정보 없음",
        exerciseTitle: "🔥 운동별 소모 칼로리",
        totalBurned: "총",
        noExercise: "   • 기록된 운동이 없습니다",
        unknownExercise: "운동",
        unknownMenu: "알 수 없음",
        goalTitle: "🎯 다이어트 권장 목표 가이드",
        goalIntake: "• 하루 권장 섭취량",
        goalMacro: "• 추천 탄단지",
        myIntake: "• 나의 오늘 섭취량",
        over: "⚠️ 권장 초과",
        under: "✅ 권장 이내",
        macroTitle: "📊 나의 오늘 실제 탄단지 총합",
        carbFull: "• 탄수화물", proteinFull: "• 단백질", fatFull: "• 지방",
        inLabel: "🍎 인입 (IN)",
        outLabel: "🏃 배출 (OUT)",
        bmrNote: (bmr) => `기초대사 ${bmr} 포함`,
        netLabel: "📉 최종 결산",
        evalTitle: "💡 전문가 총평",
        error: "분석 중 오류가 발생했습니다."
    },
    en: {
        outputLanguage: "English",
        reportTitle: "📋 Today's Nutrition Report",
        breakfast: "🍳 Breakfast", lunch: "🍳 Lunch", dinner: "🍳 Dinner", snack: "🍰 Snack",
        total: "total",
        carb: "C", protein: "P", fat: "F",
        noInfo: "No data",
        exerciseTitle: "🔥 Calories Burned by Activity",
        totalBurned: "total",
        noExercise: "   • No activity recorded",
        unknownExercise: "Activity",
        unknownMenu: "Unknown",
        goalTitle: "🎯 Recommended Daily Targets",
        goalIntake: "• Recommended intake",
        goalMacro: "• Recommended macros",
        myIntake: "• Your intake today",
        over: "⚠️ Over target",
        under: "✅ Within target",
        macroTitle: "📊 Your Macros Today",
        carbFull: "• Carbs", proteinFull: "• Protein", fatFull: "• Fat",
        inLabel: "🍎 IN",
        outLabel: "🏃 OUT",
        bmrNote: (bmr) => `includes BMR ${bmr}`,
        netLabel: "📉 Net",
        evalTitle: "💡 Expert Summary",
        error: "An error occurred during analysis."
    }
};

/**
 * 앱이 보낸 언어 코드를 지원 언어로 바꾼다.
 *
 * 값이 아예 없으면(= language를 보내지 않는 구버전 앱) 한국어로 떨어뜨려
 * 기존 사용자가 지금까지 받던 결과와 완전히 동일하게 유지한다.
 * 값은 있는데 지원하지 않는 언어면, 한국어보다는 영어가 나으므로 영어로 보낸다.
 */
function resolveLang(raw) {
    if (!raw) return "ko";
    const primary = String(raw).toLowerCase().split(/[-_]/)[0];
    return LABELS[primary] ? primary : "en";
}
exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    // [보안] 인증된 앱의 요청만 허용 (App Check 필수 활성화 필요)
    enforceAppCheck: true,
    timeoutSeconds: 120,
}, async (req, res) => {

    // 구버전 앱은 language를 보내지 않는다. 그때는 ko로 떨어져 지금까지와 완전히 동일하게 동작한다.
    const lang = resolveLang(req.body && req.body.language);
    const L = LABELS[lang];

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
        const data = await generateAndParse(model, prompt);

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

        res.status(200).json({
            net_calories: netCalories,
            feedback: feedback
        });
    } catch (error) {
        console.error("Internal Error:", error.message);
        res.status(500).json({
            net_calories: 0,
            feedback: L.error
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