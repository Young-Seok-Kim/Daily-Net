/**
 * 앱이 보낸 요청이 아닌 것을 모델 호출 전에 걸러낸다.
 *
 * 2026-10-29 00:10 한 IP가 analyzeDiet의 Cloud Run 주소로 robots.txt·sitemap.xml 같은 경로를
 * GET으로 1분에 20번 긁었다. 인터넷을 돌며 웹사이트를 찾는 스캐너가 흔히 하는 일인데, 함수가
 * 요청 방식도 본문도 보지 않고 Gemini를 불러서 빈 입력으로 20번이 나갔다. 무료 등급 분당 한도(5회)에
 * 걸려 5건은 500으로 끝났고, 그 1분 동안 실제 사용자가 있었다면 429를 봤을 것이다.
 *
 * 옵션의 enforceAppCheck는 onCall 함수에만 적용되고 onRequest에는 효과가 없다. REQUIRE_AUTH도
 * 구버전 앱 때문에 아직 false다(quota.js 참고). 그래서 여기서 최소한의 모양만 본다.
 * 앱(Retrofit)은 항상 POST로 JSON 본문을 보내므로, 그 모양이 아니면 앱이 보낸 요청이 아니다.
 *
 * 거절했으면 true를 돌려준다. 호출한 쪽은 그대로 return 하면 된다.
 */
function rejectNonAppRequest(req, res) {
    if (req.method !== "POST") {
        res.set("Allow", "POST");
        res.status(405).json({ error: "method not allowed" });
        return true;
    }

    const body = req.body;
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length === 0) {
        res.status(400).json({ error: "json body required" });
        return true;
    }

    return false;
}

module.exports = { rejectNonAppRequest };
