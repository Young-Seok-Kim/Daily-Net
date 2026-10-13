/**
 * 인바디(체성분) 결과 처리.
 *
 * 앱은 설정에서 입력한 인바디 값을 `users/{uid}.inbody`에 두고, 분석 요청마다 같이 보낸다.
 * 여기서는 그 값을 검증하고, 분석이 쓸 기초대사량을 정한다.
 *
 * 기초대사량은 **인바디에 적힌 값이 있으면 그것을 그대로 쓴다.** 공식(Mifflin-St Jeor)은
 * 키·체중·나이로 평균을 추정한 값이고, 인바디는 그 사람의 근육량을 반영한 실측값이라
 * 같은 체중이라도 수백 kcal이 다를 수 있다. 사용자가 굳이 넣은 값보다 평균 공식을 믿을 이유가 없다.
 * 값이 없거나 말이 안 되는 숫자면 전처럼 공식으로 떨어진다.
 */
const { calculateBmr } = require("./nutrition");

/**
 * 받아들이는 범위. 벗어나면 그 칸은 0(없음)으로 본다.
 * 모델이 결과지를 잘못 읽어 체지방률 자리에 체중이 들어오는 식의 사고를 거른다.
 */
const RANGES = {
    bmr: [800, 4000],
    skeletalMuscle: [10, 80],
    bodyFat: [1, 120],
    bodyFatPercent: [1, 70],
    weight: [20, 300]
};

function num(value) {
    const n = Number(value);
    return Number.isFinite(n) ? n : 0;
}

/**
 * 앱이나 모델이 준 인바디 값을 정리한다. 쓸 수 있는 값이 하나도 없으면 null.
 *
 * 숫자는 범위 안에 있을 때만 남기고, 측정일은 YYYY-MM-DD 형식일 때만 남긴다.
 * 기초대사량은 정수, 나머지는 소수 첫째 자리까지다 (결과지에 그렇게 찍혀 있다).
 */
function normalizeInbody(raw) {
    if (!raw || typeof raw !== "object") return null;
    const out = {};
    for (const key of Object.keys(RANGES)) {
        const value = num(raw[key]);
        const [lo, hi] = RANGES[key];
        if (value < lo || value > hi) {
            out[key] = 0;
        } else {
            out[key] = key === "bmr" ? Math.round(value) : Math.round(value * 10) / 10;
        }
    }
    const date = typeof raw.measuredAt === "string" && /^\d{4}-\d{2}-\d{2}$/.test(raw.measuredAt.trim())
        ? raw.measuredAt.trim()
        : "";
    out.measuredAt = date;

    const hasAny = Object.keys(RANGES).some((key) => out[key] > 0);
    return hasAny ? out : null;
}

/**
 * 분석에 쓸 기초대사량. 인바디 값이 있으면 그것, 없으면 공식.
 * formula는 리포트에서 "공식으로는 얼마였는지" 비교해 보여줄 때 쓴다.
 */
function resolveBmr({ weight, height, age, isMale, inbody }) {
    const formula = calculateBmr(Number(weight) || 0, Number(height) || 0, Number(age) || 0, !!isMale);
    if (inbody && inbody.bmr > 0) {
        return { bmr: inbody.bmr, source: "inbody", formula };
    }
    return { bmr: formula, source: "formula", formula };
}

/**
 * 프롬프트의 [사용자 데이터]에 넣을 인바디 줄. 값이 없으면 빈 문자열.
 * 모델은 숫자를 다시 계산하지 않고(기초대사량은 서버가 이미 정했다) 근육량·체지방률을
 * 피드백의 근거로만 쓴다. 그래서 "참고"라고 못 박는다.
 */
function inbodyPromptLine(inbody) {
    if (!inbody) return "";
    const parts = [];
    if (inbody.bmr > 0) parts.push(`기초대사량 ${inbody.bmr}kcal(실측)`);
    if (inbody.skeletalMuscle > 0) parts.push(`골격근량 ${inbody.skeletalMuscle}kg`);
    if (inbody.bodyFat > 0) parts.push(`체지방량 ${inbody.bodyFat}kg`);
    if (inbody.bodyFatPercent > 0) parts.push(`체지방률 ${inbody.bodyFatPercent}%`);
    if (parts.length === 0) return "";
    const when = inbody.measuredAt ? `, ${inbody.measuredAt} 측정` : "";
    return `- 인바디 (참고용 실측값${when}): ${parts.join(", ")}`;
}

module.exports = { RANGES, normalizeInbody, resolveBmr, inbodyPromptLine };
