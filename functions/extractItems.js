/**
 * 사진에서 읽은 항목을 정리하고 입력창에 넣을 텍스트로 바꾼다.
 *
 * 여기서 지켜야 할 계약은 하나다 — **영양성분표에서 읽은 열량만 텍스트에 적는다.**
 * 이 텍스트는 그대로 analyzeDiet으로 넘어가고, 거기서 "적힌 kcal은 그대로 쓰라"는
 * 지시를 받는다. 눈대중으로 낸 숫자가 섞여 들어가면 **추측이 정답 자리에 앉는다.**
 */

const { gramsOf } = require("./kcalCheck");

/**
 * 성분표 기준과 먹은 양이 이 범위를 벗어나면 잘못 읽은 것으로 본다.
 * 1회 제공량은 작아도 5g 남짓이고, 한 사람이 한 번에 먹는 양이 5kg일 수는 없다.
 */
const MIN_AMOUNT = 1;
const MAX_AMOUNT = 5000;

/** 한 항목이 이 열량을 넘으면 읽기가 어긋난 것이다 (하루치를 한 항목이 채울 수는 없다) */
const MAX_ITEM_KCAL = 5000;

const positive = (v, max) => {
    const n = Number(v);
    return Number.isFinite(n) && n >= MIN_AMOUNT && n <= max ? n : 0;
};

/**
 * 성분표에서 읽은 값으로 먹은 양의 열량을 낸다. 못 내면 0.
 *
 * **모델에게 곱셈을 시키지 않는다.** 읽어온 숫자 세 개로 여기서 계산한다.
 * 모델은 씨리얼 중량을 42g/30g/80g으로 오락가락했고 우유 200ml을 70~160으로 냈다.
 * 읽는 것과 셈하는 것을 나눠서, 셈은 틀릴 수 없게 만든다.
 *
 * 기준과 먹은 양의 **단위가 같기만 하면** 비율에서 약분되므로 g·ml을 나눠 다룰 필요가 없다.
 * ("100ml당 62kcal"인 우유 200ml → 62 × 200 / 100. g으로 바꿔 볼 이유가 없다)
 */
function kcalFromLabel(label, eatenAmount) {
    if (!label) return 0;

    const per = Number(label.kcal);
    const basis = positive(label.basisAmount, MAX_AMOUNT);
    const eaten = positive(eatenAmount, MAX_AMOUNT);

    // 열량 0은 제로 음료의 정상값이라 통과시킨다. 다만 기준과 먹은 양은 있어야 한다
    if (!Number.isFinite(per) || per < 0 || !basis || !eaten) return 0;

    const kcal = Math.round((per * eaten) / basis);
    return kcal >= 0 && kcal <= MAX_ITEM_KCAL ? kcal : 0;
}

/**
 * amount에 적은 양과 eatenAmount가 어긋나면 그 사연을 돌려준다. 맞으면 빈 문자열.
 *
 * 실제로 이렇게 나갔다:
 *   `오리온 초코파이 468g (39g x 12봉지) 171*(171/39×39)`
 * 성분표는 맞게 읽었는데 eatenAmount에 **기준값 39를 그대로 베껴** 한 개 값이 나갔다.
 * 화면에는 468g이라 적혀 있고 열량은 39g치인, 그 자체로 앞뒤가 안 맞는 줄이었다.
 *
 * 프롬프트로도 막아뒀지만 **지시는 지켜지는지 확인할 방법이 없다.** 여기서 잰다.
 * 값은 고치지 않는다 — 사용자가 정말 한 개만 먹었을 수도 있고, 그건 우리가 알 수 없다.
 *
 * 적게 잡은 쪽만 본다. `500mL 2캔`처럼 **여러 개를 먹어 eatenAmount가 더 큰 것은 정상**이다.
 */
function amountMismatch(amount, eatenAmount) {
    const stated = gramsOf(amount);
    const eaten = Number(eatenAmount);
    if (!stated || !Number.isFinite(eaten) || eaten <= 0) return "";

    // 반올림 오차 정도는 넘어간다
    if (eaten >= stated * 0.95) return "";

    return `amount는 ${stated}인데 먹은 양을 ${eaten}으로 잡음`;
}

/** 모델 응답을 {name, amount, kcal, kcalFromLabel} 형태로 고정한다 */
function normalizeExtracted(rawItems) {
    if (!Array.isArray(rawItems)) return [];

    return rawItems
        .filter((m) => m && m.name)
        .map((m) => {
            const fromLabel = kcalFromLabel(m.label, m.eatenAmount);
            const guessed = Number(m.kcal) || 0;
            const amount = m.amount ? String(m.amount).trim() : "";

            return {
                name: String(m.name),
                // 못 알아본 양은 빈 문자열로 통일한다. 없는 필드와 null을 나눠 다루지 않기 위함
                amount,
                // 성분표에서 낸 값이 있으면 그것을 쓴다. 눈대중보다 언제나 낫다
                kcal: fromLabel || guessed,
                kcalFromLabel: fromLabel > 0,
                // 로그용. 기준이 30인지 100인지 400인지 보여야
                // "1회 제공량을 봉지 전체로 착각했는지"를 눈으로 가릴 수 있다
                basis: fromLabel > 0
                    ? `${Number(m.label.kcal)}/${Number(m.label.basisAmount)}×${Number(m.eatenAmount)}`
                    : "",
                // 이름과 열량이 서로 안 맞는 줄. 값은 그대로 두고 알리기만 한다
                mismatch: fromLabel > 0 ? amountMismatch(amount, m.eatenAmount) : "",
                // 개당 중량을 낼 때 쓴다. 문자열을 파싱하는 것보다 이 숫자가 정확하다
                eaten: fromLabel > 0 ? Number(m.eatenAmount) : 0
            };
        });
}

/** 낱개로 세는 단위. amount 앞머리에서 개수를 뽑을 때 쓴다 */
const COUNT_UNITS = "개|봉지|봉|캔|병|팩|조각|장|줄|마리|컵|잔|알|판|pcs?|pieces?";

/** amount에 적힌 개수와 그 단위. 없으면 null */
function countOf(amount) {
    const m = String(amount || "").match(
        new RegExp(`(\\d+(?:\\.\\d+)?)\\s*(${COUNT_UNITS})(?![a-z가-힣])`, "i")
    );
    if (!m) return null;

    const count = Number(m[1]);
    return Number.isFinite(count) && count > 0 ? { count, unit: m[2] } : null;
}

/** amount에 쓰인 무게·부피 단위를 적힌 그대로. 없으면 빈 문자열 */
function unitOf(amount) {
    const m = String(amount || "").match(/\d+(?:\.\d+)?\s*(kg|g|ml|l)(?![a-z])/i);
    return m ? m[1] : "";
}

/**
 * 입력창에 그대로 넣을 한 줄.
 *
 * 개수가 잡히는 항목은 **총량이 아니라 개당 값**으로 적는다. 열량도, 중량도.
 * 총합을 적으면 사용자가 "1개"를 "3개"로 고쳐도 숫자가 그대로라 어긋난다 —
 * analyzeDiet은 적힌 kcal을 그대로 쓰라는 지시를 받기 때문이다.
 * 개당으로 적으면 **개수만 고쳐도 열량과 중량이 함께 따라온다.**
 *
 * 개당 중량은 문자열이 아니라 eatenAmount(총량)를 개수로 나눠 낸다.
 * `500mL 2캔`은 500이 이미 개당이고 `3개 117g`은 117이 총량이라,
 * **적힌 순서만 보고는 어느 쪽인지 알 수 없다.** 총량 숫자를 나누는 쪽이 둘 다 맞는다.
 *
 * @param perUnitWord 언어별 "개당" (labels.js). 분석 모델이 이 말을 보고 개수를 곱한다
 */
function toInputText(items, perUnitWord = "개당") {
    return items
        .map((m) => {
            const head = m.amount ? `${m.name} ${m.amount}` : m.name;
            if (!m.kcalFromLabel) return head;

            const counted = countOf(m.amount);
            if (!counted) {
                // 개수로 세지 않는 것(82g 한 봉, 200ml)은 총 열량 그대로가 맞다
                return `${head} ${m.kcal}kcal`;
            }

            const { count, unit } = counted;
            const perKcal = Math.round(m.kcal / count);
            const amountUnit = unitOf(m.amount);
            const perAmount = amountUnit ? Math.round(m.eaten / count) : 0;

            const per = perAmount > 0
                ? `${perUnitWord} ${perAmount}${amountUnit} ${perKcal}kcal`
                : `${perUnitWord} ${perKcal}kcal`;

            return `${m.name} ${count}${unit} (${per})`;
        })
        .join(", ");
}

module.exports = { normalizeExtracted, toInputText, kcalFromLabel, amountMismatch, countOf };
