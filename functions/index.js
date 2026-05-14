const { onRequest } = require("firebase-functions/v2/https");
const { GoogleGenerativeAI } = require("@google/generative-ai");

// [보안] API 키는 서버 코드 내에 위치하여 외부로 노출되지 않습니다.


exports.analyzeDiet = onRequest({
    region: "asia-northeast3",
    enforceAppCheck: true,
    secrets: ["GEMINI_API_KEY"]
}, async (req, res) => {
    // 한국 사용자가 많으므로 서울 리전(asia-northeast3) 권장
    try {
        const { weight, height, breakfast, lunch, dinner, snack, exercise } = req.body;

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = genAI.getGenerativeModel({ model: "gemini-1.5-flash" });
        
        // 안드로이드 코드에 있던 프롬프트를 그대로 이사 시킵니다.
        const prompt = `
            당신은 전문 다이어트 영양사이자 스마트한 분석 엔진입니다. 
            사용자의 데이터를 분석하여 오늘의 '순 칼로리'를 계산하고 로그 리포트를 작성하세요.

            [신체 데이터]
            - 키: ${height}cm / 체중: ${weight}kg
            
            [입력 데이터]
            - 아침: ${breakfast} / 점심: ${lunch} / 저녁: ${dinner} / 간식: ${snack}
            - 운동: ${exercise}
            
            분석 규칙:
            1. BMR은 Mifflin-St Jeor 공식으로 계산할 것.
            2. 피드백은 반드시 '구분 | 상세 데이터 | 칼로리 연산 | 비고' 형태의 테이블을 포함할 것.
            3. 결과는 반드시 아래 JSON 형식을 지킬 것:
            {
              "net_calories": 정수,
              "feedback": "문자열"
            }
        `;

        const result = await model.generateContent(prompt);
        const response = await result.response;
        const responseText = response.text();

        // JSON만 추출하여 파싱 (안드로이드에서 하던 replace 로직을 서버에서 수행)
        const jsonMatch = responseText.match(/\{[\s\S]*\}/);
        const jsonResult = JSON.parse(jsonMatch[0]);

        // 클라이언트로 결과 전송
        res.status(200).json(jsonResult);

    } catch (error) {
        console.error("Gemini Error:", error);
        res.status(500).json({ 
            net_calories: 0, 
            feedback: "서버 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요." 
        });
    }
});