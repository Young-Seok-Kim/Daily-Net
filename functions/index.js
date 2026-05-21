const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");

exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    // [보안] 인증된 앱의 요청만 허용 (App Check 필수 활성화 필요)
    enforceAppCheck: true
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
            model: "gemini-2.5-flash-lite",
            generationConfig: { responseMimeType: "application/json" }
        });

// 상단에 성별 텍스트 변환 로직 추가 (genderText 대응)
const genderText = isMale ? '남성' : '여성';

const prompt = `
    당신은 15년 경력의 [베테랑 전문 다이어트 영양사]입니다.
    사용자의 식단과 운동 데이터를 분석하여, 정중하지만 냉철하게 영양 성적표를 작성하세요.

    [사용자 데이터]
    - 신체: ${height}cm, ${weight}kg, ${genderText}
    - 식사: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
    - 활동: ${exercise}
    - 비고: ${remark}

    [분석 및 응답 지침] - **Lite 모델 최적화 버전**
    1. **칼로리 산출 로직 고정**:
       - 모든 음식은 식약처 표준 영양 성분 DB를 기준으로 합니다.
       - 양이 명시되지 않았다면 성인 1인분(표준 중량)을 기준으로 하되, 터무니없는 고칼로리(예: 닭가슴살 500kcal) 산출을 절대 금지합니다.
    2. **단계별 사고(Chain of Thought)**: 내부적으로 [메뉴명 -> 예상 중량(g) -> 100g당 칼로리 -> 최종 칼로리] 단계를 거쳐 계산한 뒤 결과값만 JSON에 담으세요.
    3. **전문적 묘사**: 'descriptions'에는 해당 식단의 [장점/단점/개선점]을 탄단지 비율을 포함하여 20자 내외로 코멘트하세요.
    4. **종합 평가**: 사용자의 BMR과 활동량을 고려하여, 현재 식단이 체중 감량에 미치는 영향을 영양학적으로 평가하세요.
    5. **형식 엄수**: (이하 JSON 구조 동일)
    {
        "calories": {
            "breakfast": 0,
            "lunch": 0,
            "dinner": 0,
            "snack": 0,
            "exercise": 0
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
        const result = await model.generateContent(prompt);
        const data = JSON.parse(result.response.text());

        // 기초대사량(BMR) 계산
        const calculateAge = (dateStr) => {
            if (!dateStr) return 29;
            const birth = new Date(dateStr);
            const today = new Date();
            let age = today.getFullYear() - birth.getFullYear();
            if (today.getMonth() < birth.getMonth() || (today.getMonth() === birth.getMonth() && today.getDate() < birth.getDate())) age--;
            return age;
        };

        const bmr = Math.round(10 * weight + 6.25 * height - 5 * calculateAge(birthDate) + (isMale ? 5 : -161));

        const totalIn = (data.calories.breakfast || 0) + (data.calories.lunch || 0) + (data.calories.dinner || 0) + (data.calories.snack || 0);
        const totalOut = bmr + (data.calories.exercise || 0);
        const netCalories = totalIn - totalOut;

        const feedback = `
        📊 영양 분석 리포트

🍳 아침: ${data.descriptions.breakfast} (+${data.calories.breakfast}kcal)
🍳 점심: ${data.descriptions.lunch} (+${data.calories.lunch}kcal)
🍳 저녁: ${data.descriptions.dinner} (+${data.calories.dinner}kcal)
🍰 간식: ${data.descriptions.snack} (+${data.calories.snack}kcal)
🔥 운동: ${exercise || '없음'} (-${data.calories.exercise}kcal)

        ---

🍎 인입 (IN): +${totalIn} kcal
🏃 배출 (OUT): -${totalOut} kcal (기초대사 ${bmr} 포함)
📉 최종 결산: ${netCalories} kcal

💡 전문가 총평
${data.evaluation}
        `.trim();

        // 성공 응답
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