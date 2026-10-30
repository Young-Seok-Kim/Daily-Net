/**
 * 스캐너가 보낸 요청을 모델 호출 전에 걸러내는지 검증 (requestGuard.js 주석 참고).
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { rejectNonAppRequest } = require("../requestGuard");

function fakeRes() {
    const res = { statusCode: null, body: null, headers: {} };
    res.status = (code) => { res.statusCode = code; return res; };
    res.json = (payload) => { res.body = payload; return res; };
    res.set = (k, v) => { res.headers[k] = v; return res; };
    return res;
}

test("GET /robots.txt 같은 스캐너 요청은 405로 거절한다", () => {
    const res = fakeRes();
    const rejected = rejectNonAppRequest({ method: "GET", body: {} }, res);
    assert.equal(rejected, true);
    assert.equal(res.statusCode, 405);
    assert.equal(res.headers.Allow, "POST");
});

test("HEAD 요청도 거절한다", () => {
    const res = fakeRes();
    assert.equal(rejectNonAppRequest({ method: "HEAD", body: undefined }, res), true);
    assert.equal(res.statusCode, 405);
});

test("POST라도 본문이 없거나 비어 있으면 400", () => {
    for (const body of [undefined, null, "", {}, [], "text"]) {
        const res = fakeRes();
        assert.equal(rejectNonAppRequest({ method: "POST", body }, res), true, `body=${JSON.stringify(body)}`);
        assert.equal(res.statusCode, 400);
    }
});

test("앱이 보내는 POST + JSON 본문은 통과한다", () => {
    const res = fakeRes();
    const req = { method: "POST", body: { weight: 70, lunch: "닭가슴살 100g", language: "ko" } };
    assert.equal(rejectNonAppRequest(req, res), false);
    assert.equal(res.statusCode, null);
});

test("구버전 앱처럼 필드가 적어도 본문이 객체면 통과한다", () => {
    const res = fakeRes();
    assert.equal(rejectNonAppRequest({ method: "POST", body: { image: "abc" } }, res), false);
});
