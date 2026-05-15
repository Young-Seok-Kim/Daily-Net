const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");

exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    secrets: ["GEMINI_API_KEY"]
}, async (req, res) => {

//    console.log("👉 [DEBUG] Received Data:", JSON.stringify(req.body));
    try {
        // 1. 요청 데이터 구조 분해 할당
        const { weight, height, isMale, birthDate, breakfast, lunch, dinner, snack, exercise, remark } = req.body;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

        // 2. 모델 설정 (Gemini 2.5 Flash-Lite & JSON 모드 강제)
        const model = genAI.getGenerativeModel({
            model: "gemini-2.5-flash",
            generationConfig: { responseMimeType: "application/json" }
        });

        const genderText = isMale ? "남성" : "여성";

        // 3. 프롬프트: AI는 오직 '추정치 데이터 추출'만 담당 (산수 금지)
        const prompt = `
            당신은 전문 다이어트 영양사입니다.
            제공된 데이터를 바탕으로 각 음식의 칼로리를 추정하여 JSON으로만 응답하세요.

            [사용자 데이터]
            - 신체: ${height}cm, ${weight}kg, ${genderText}
            - 식사: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
            - 활동: ${exercise}
            - 비고: ${remark}

            [응답 지침]
            1. 모든 칼로리는 정수(Integer)로 추정할 것.
            2. 피드백 문구에는 마크다운 기호(**, ---, |)를 절대 사용하지 말고 줄바꿈과 이모지만 사용할 것.
            3. 응답은 반드시 아래 JSON 구조를 지킬 것.

            {
                "calories": {
                    "breakfast": 0,
                    "lunch": 0,
                    "dinner": 0,
                    "snack": 0,
                    "exercise": 0
                },
                "descriptions": {
                    "breakfast": "메뉴명과 간단한 영양평",
                    "lunch": "메뉴명과 간단한 영양평",
                    "dinner": "메뉴명과 간단한 영양평",
                    "snack": "메뉴명과 간단한 영양평"
                },
                "evaluation": "오늘 식단에 대한 전문적인 종합 평가 한 줄"
            }
        `;

        const result = await model.generateContent(prompt);
        const data = JSON.parse(result.response.text());

        const calculateAge = (birthDateString) => {
            if (!birthDateString) return 29; // 혹시 데이터가 없으면 기본값
            const today = new Date();
            const birth = new Date(birthDateString);
            let age = today.getFullYear() - birth.getFullYear();
            const m = today.getMonth() - birth.getMonth();
            if (m < 0 || (m === 0 && today.getDate() < birth.getDate())) {
                age--;
            }
            return age;
        };

        const userAge = calculateAge(birthDate);

        // [수정] Mifflin-St Jeor 공식 (동적 나이 반영)
        const bmrOffset = isMale ? 5 : -161;
        const bmr = Math.round(10 * weight + 6.25 * height - 5 * userAge + bmrOffset);

        const totalIn = data.calories.breakfast + data.calories.lunch + data.calories.dinner + data.calories.snack;
        const totalOut = bmr + data.calories.exercise;
        const netCalories = totalIn - totalOut;

        // 5. 최종 사용자 피드백 문자열 조립
        const feedback = `
🍎 인입 (IN)
- 아침: ${data.descriptions.breakfast || '정보 없음'} (+${data.calories.breakfast} kcal)
- 점심: ${data.descriptions.lunch || '정보 없음'} (+${data.calories.lunch} kcal)
- 저녁: ${data.descriptions.dinner || '정보 없음'} (+${data.calories.dinner} kcal)
- 간식: ${data.descriptions.snack || '정보 없음'} (+${data.calories.snack} kcal)

🏃 배출 (OUT)
- 기초 대사: -${bmr} kcal
- 활동 및 운동: -${data.calories.exercise} kcal

📉 최종 결산 (NET)
- 에너지 손익: ${netCalories} kcal
- 종합 평가: ${data.evaluation}
`.trim();

        // 6. 결과 반환
        res.status(200).json({
            net_calories: netCalories,
            feedback: feedback
        });

    } catch (error) {
        console.error("Internal Error Details:", error.message);
        res.status(500).json({
            net_calories: 0,
            feedback: `분석 실패: ${error.message}`
        });
    }
});