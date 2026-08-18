/**
 * 사용자가 콤마로 이어 적은 입력을 항목 단위로 나눈다.
 *
 * 항목 경계 판단을 모델에게 맡기지 않는다 — 곱셈(shareItems.js)·운동 계산(exerciseCalc.js)과
 * 같은 결론이다. 통짜 문자열을 주면 모델이 경계를 매번 다르게 추측해서,
 * 같은 입력인데 항목이 붙었다 떨어졌다 했다. 쪼개기는 결정적인 일이라 코드가 한다.
 * 모델에게는 번호 붙은 줄로 넘기고 "줄 하나가 항목 하나"라고 못박는다.
 *
 * 콤마라고 다 경계는 아니다:
 *   - 숫자 사이 콤마는 자릿수 표기다 ("아이스티 1,000ml")
 *   - 괄호 안 콤마는 부연 설명이다 ("반반치킨 (양념, 후라이드)")
 */

/** 항목을 가르는 문자. 전각 콤마와 일본식 모점도 콤마로 취급한다 */
const SEPARATORS = new Set([",", "，", "、"]);

const isDigit = (ch) => ch >= "0" && ch <= "9";

/** 입력을 항목 문자열 배열로. 빈 조각은 버린다 */
function splitItems(text) {
    const raw = String(text || "");
    const parts = [];
    let buf = "";
    let depth = 0;

    for (let i = 0; i < raw.length; i++) {
        const ch = raw[i];

        // 줄바꿈은 언제나 경계다. 괄호를 안 닫고 줄을 바꿨으면 그냥 잘못 적은 것이니
        // depth도 함께 버린다 — 안 버리면 다음 줄들의 콤마까지 전부 삼킨다
        if (ch === "\n") {
            parts.push(buf);
            buf = "";
            depth = 0;
            continue;
        }

        if (ch === "(" || ch === "（") depth++;
        else if (ch === ")" || ch === "）") depth = depth > 0 ? depth - 1 : 0;

        if (SEPARATORS.has(ch) && depth === 0) {
            const prev = raw[i - 1] || "";
            const next = raw[i + 1] || "";
            // "1,000"처럼 양옆이 숫자면 자릿수 표기다. 경계는 "사과 1, 배 2"처럼
            // 콤마 뒤에 공백이나 글자가 온다
            if (!(isDigit(prev) && isDigit(next))) {
                parts.push(buf);
                buf = "";
                continue;
            }
        }

        buf += ch;
    }
    parts.push(buf);

    return parts.map((s) => s.trim()).filter(Boolean);
}

/**
 * 프롬프트에 넣을 번호 붙은 줄. 비어 있으면 "(없음)".
 *
 * 프롬프트 지시가 한국어라 "(없음)"도 한국어로 고정한다. 사용자에게 보이는 글이 아니다.
 *
 * @param indent 각 줄 앞에 붙일 들여쓰기. 프롬프트 템플릿의 줄맞춤용
 */
function numberedLines(text, indent = "") {
    const items = splitItems(text);
    if (items.length === 0) return `${indent}(없음)`;
    return items.map((s, i) => `${indent}${i + 1}. ${s}`).join("\n");
}

module.exports = { splitItems, numberedLines };
