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

        // [수정] Gemini 2.5 Flash-Lite 모델로 변경
        const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash-lite" });

const prompt = `
            당신은 전문 다이어트 영양사입니다.
            사용자의 신체 정보(키: ${height}cm, 체중: ${weight}kg)를 바탕으로 기초대사량(BMR)을 계산하고, 아래 식단과 운동량을 분석하여 '오늘의 정산 상세' 리포트를 작성하세요.

            [입력 데이터]
            - 식단: 아침(${breakfast}), 점심(${lunch}), 저녁(${dinner}), 간식(${snack})
            - 활동 및 운동: ${exercise}
            - 비고(Remark): ${remark}

            [응답 지침]
            1. 'net_calories'는 최종 계산된 순 칼로리 수치(정수)만 넣으세요.
            2. 'feedback' 항목 안에 아래 형식을 참고하여 깔끔한 줄바꿈 텍스트로 작성하세요.
            3. 절대 별표(**), 구분선(|), 하이픈 다발(---) 등의 마크다운 기호를 사용하지 마세요.
            4. 각 항목 앞에 적절한 이모지(🍎, 🏃, 📉 등)를 사용해 가독성을 높여주세요.

            [feedback 구성 예시]
            🍎 인입 (IN)
            - 아침: ${breakfast} (+150 kcal) / 비고: 가벼운 시작
            - 점심: ${lunch} (+600 kcal) / 비고: 적정량 섭취
            ...
            🏃 배출 (OUT)
            - 기초 대사: -1,900 kcal (Mifflin-St Jeor 계산 결과)
            - 활동 및 운동: -300 kcal (일상 활동량 반영)

            📉 최종 결산 (NET)
            - 에너지 손익: -1,700 kcal
            - 종합 평가: 현재 체지방 연소에 매우 유리한 에너지 적자 상태입니다.

            전문적이고 친절한 어투로 작성하세요. JSON 형식은 엄격히 지키세요.
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