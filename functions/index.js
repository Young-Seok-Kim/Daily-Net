const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");

exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    enforceAppCheck: false,
    secrets: ["GEMINI_API_KEY"]
}, async (req, res) => {
    try {
        const { weight, height, breakfast, lunch, dinner, snack, exercise, remark } = req.body;
        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

        // 1. 모델명 수정 (안정적인 최신 버전 사용)
        const model = genAI.getGenerativeModel({
            model: "gemini-2.5-flash-lite
            ",
            generationConfig: { responseMimeType: "application/json" } // JSON 출력 강제
        });

        // 2. AI에게는 '데이터 추출'만 맡기는 프롬프트
        const prompt = `
            사용자의 식단과 활동 데이터를 분석하여 칼로리 수치를 '추정'하세요.
            반드시 다음 JSON 구조로만 응답하세요. 다른 설명은 필요 없습니다.

            [사용자 데이터]
            - 신체: ${height}cm, ${weight}kg, 남성
            - 식사: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
            - 활동: ${exercise}
            - 비고: ${remark}

            [JSON 응답 구조]
            {
                "calories": {
                    "breakfast": 정수,
                    "lunch": 정수,
                    "dinner": 정수,
                    "snack": 정수,
                    "exercise": 정수 (운동으로 소모된 칼로리, 양수로 표기)
                },
                "descriptions": {
                    "breakfast": "메뉴명 및 간단 설명",
                    "lunch": "메뉴명 및 간단 설명",
                    "dinner": "메뉴명 및 간단 설명",
                    "snack": "메뉴명 및 간단 설명"
                },
                "evaluation": "영양사 관점의 짧은 종합 평가 한 줄"
            }
        `;

        const result = await model.generateContent(prompt);
        const data = JSON.parse(result.response.text());

        // 3. 실제 산수는 서버 코드(Node.js)에서 직접 수행 (정확도 100%)
        const bmr = Math.round(10 * weight + 6.25 * height - 5 * 29 + 5); // Mifflin-St Jeor (남성, 만 29세 가정)
        const totalIn = data.calories.breakfast + data.calories.lunch + data.calories.dinner + data.calories.snack;
        const totalOut = bmr + data.calories.exercise;
        const netCalories = totalIn - totalOut;

        // 4. 최종 사용자 피드백 문자열 조립
        const feedback = `
🍎 인입 (IN)
- 아침: ${data.descriptions.breakfast} (+${data.calories.breakfast} kcal)
- 점심: ${data.descriptions.lunch} (+${data.calories.lunch} kcal)
- 저녁: ${data.descriptions.dinner} (+${data.calories.dinner} kcal)
- 간식: ${data.descriptions.snack} (+${data.calories.snack} kcal)

🏃 배출 (OUT)
- 기초 대사: -${bmr} kcal
- 활동 및 운동: -${data.calories.exercise} kcal

📉 최종 결산 (NET)
- 에너지 손익: ${netCalories} kcal
- 종합 평가: ${data.evaluation}
`.trim();

        res.status(200).json({
            net_calories: netCalories,
            feedback: feedback
        });

    } catch (error) {
        console.error("Error:", error.message);
        res.status(500).json({ net_calories: 0, feedback: `분석 실패: ${error.message}` });
    }
});