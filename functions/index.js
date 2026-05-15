const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");

exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    enforceAppCheck: false,
    secrets: ["GEMINI_API_KEY"]
}, async (req, res) => {
    try {
        const { weight, height, breakfast, lunch, dinner, snack, exercise } = req.body;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

        // [수정] Gemini 2.5 Flash-Lite 모델로 변경
        const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash-lite" });

        const prompt = `
            당신은 전문 다이어트 영양사입니다. 사용자의 신체 정보와 하루 식단, 운동량을 바탕으로 오늘의 '순 칼로리(Net Calories)'를 정밀하게 분석하세요.

            [사용자 데이터]
            - 키: ${height}cm
            - 체중: ${weight}kg
            - 식사 내용: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
            - 운동량: ${exercise}

            [분석 지침]
            1. 입력된 식단의 대략적인 칼로리를 합산하세요.
            2. 운동으로 소모된 칼로리와 기초대사량을 고려하여 '순 칼로리'를 계산하세요.
            3. 결과는 반드시 아래 JSON 형식을 엄격히 지켜서 응답하세요. 다른 설명이나 인사말은 생략하세요.

            {
              "net_calories": 1500,
              "feedback": "오늘의 식단과 운동량에 대한 구체적이고 전문적인 분석 의견"
            }
        `;

        const result = await model.generateContent(prompt);
        const responseText = result.response.text();

        const jsonMatch = responseText.match(/\{[\s\S]*\}/);
        if (!jsonMatch) {
            throw new Error("Gemini 응답에서 유효한 JSON을 찾을 수 없습니다.");
        }

        const jsonResult = JSON.parse(jsonMatch[0]);
        res.status(200).json(jsonResult);

    } catch (error) {
        console.error("Internal Error Details:", error.message);
        res.status(500).json({
            net_calories: 0,
            feedback: `분석 실패: ${error.message}`
        });
    }
});