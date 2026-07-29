/**
 * Gemini 응답을 안전하게 JSON으로 바꾸는 공통 유틸.
 * 모델이 가끔 설명 문구나 마크다운을 섞어 보내기 때문에 그대로 JSON.parse 할 수 없다.
 */

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

module.exports = { safeParseJson, generateAndParse };
