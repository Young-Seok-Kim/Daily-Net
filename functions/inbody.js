/**
 * 인바디(체성분) 결과 처리.
 *
 * 앱은 설정에서 입력한 인바디 값을 `users/{uid}.inbody`에 두고, 분석 요청마다 같이 보낸다.
 * 여기서는 그 값을 검증하고, 분석이 쓸 기초대사량을 정하고, 인바디 해석(analyzeInbody)에 쓸
 * 수치 판정을 계산한다.
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
    weight: [20, 300],
    // b41부터. 결과지에 없으면 0
    visceralFatLevel: [1, 30],
    inbodyScore: [1, 100],
    bodyWater: [10, 100]
};

/** 정수로 두는 항목. 나머지는 소수 첫째 자리 */
const INTEGER_KEYS = new Set(["bmr", "visceralFatLevel", "inbodyScore"]);

/** 내장지방 레벨 표준 구간 (인바디 기준 1~9 표준, 10 이상 주의) */
const VISCERAL_STANDARD = [1, 9];

/**
 * 체지방률 표준 범위(%). 인바디 결과지의 표준 구간과 같다.
 * 골격근량은 절대값 표준이 키·체중마다 달라 체중 대비 비율로 본다 (대략적인 기준이다).
 */
const FAT_STANDARD = { male: [10, 20], female: [18, 28] };
const SMM_RATIO_STANDARD = { male: [40, 46], female: [33, 39] };

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
            out[key] = INTEGER_KEYS.has(key) ? Math.round(value) : Math.round(value * 10) / 10;
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
    if (inbody.visceralFatLevel > 0) parts.push(`내장지방 레벨 ${inbody.visceralFatLevel}`);
    if (parts.length === 0) return "";
    const when = inbody.measuredAt ? `, ${inbody.measuredAt} 측정` : "";
    return `- 인바디 (참고용 실측값${when}): ${parts.join(", ")}`;
}

/** 생년월일(YYYY-MM-DD)로 만 나이. 없거나 이상하면 29 (analyzeDiet과 같은 기본값) */
function ageFrom(birthDate, today = new Date()) {
    if (!birthDate || typeof birthDate !== "string") return 29;
    const birth = new Date(birthDate);
    if (Number.isNaN(birth.getTime())) return 29;
    let age = today.getFullYear() - birth.getFullYear();
    if (today.getMonth() < birth.getMonth() ||
        (today.getMonth() === birth.getMonth() && today.getDate() < birth.getDate())) {
        age--;
    }
    return age;
}

/** 값이 [lo, hi] 표준 구간의 어디에 있는지. 모르면 "unknown" */
function levelOf(value, [lo, hi]) {
    if (!(value > 0)) return "unknown";
    if (value < lo) return "low";
    if (value > hi) return "high";
    return "standard";
}

/**
 * 인바디 해석에 쓰는 수치 판정. 모델에게 계산을 맡기지 않으려고 서버가 먼저 센다.
 *
 * - bmi: 측정 체중(없으면 오늘 체중) 기준
 * - fatLevel: 체지방률의 표준 구간 대비 (low / standard / high / unknown)
 * - smmRatio·muscleLevel: 골격근량이 체중에서 차지하는 비율과 그 판정
 * - formulaBmr·bmrDiff: 공식 기초대사량과 인바디 값의 차이
 * - proteinTarget: 감량 중 근손실을 막는 단백질 목표 (nutrition.js와 같은 1.6g/kg)
 */
function bodyMetrics({ inbody, height, weight, age, isMale }) {
    const h = num(height);
    const w = inbody.weight > 0 ? inbody.weight : num(weight);
    const bmi = h > 0 && w > 0 ? Math.round((w / ((h / 100) ** 2)) * 10) / 10 : 0;

    const sex = isMale ? "male" : "female";
    const fatStandard = FAT_STANDARD[sex];
    const fatLevel = levelOf(inbody.bodyFatPercent, fatStandard);

    const smmRatio = w > 0 && inbody.skeletalMuscle > 0
        ? Math.round((inbody.skeletalMuscle / w) * 1000) / 10
        : 0;
    const muscleStandard = SMM_RATIO_STANDARD[sex];
    const muscleLevel = levelOf(smmRatio, muscleStandard);

    const formulaBmr = calculateBmr(w, h, num(age), !!isMale);
    const bmrDiff = inbody.bmr > 0 ? inbody.bmr - formulaBmr : 0;

    const visceralLevel = levelOf(num(inbody.visceralFatLevel), VISCERAL_STANDARD);

    return {
        visceralLevel,
        visceralLabel: LEVEL_KO[visceralLevel],
        visceralStandard: `${VISCERAL_STANDARD[0]}~${VISCERAL_STANDARD[1]}`,
        weight: w,
        bmi,
        fatLevel,
        fatLabel: LEVEL_KO[fatLevel],
        fatStandard: `${fatStandard[0]}~${fatStandard[1]}%`,
        smmRatio,
        muscleLevel,
        muscleLabel: LEVEL_KO[muscleLevel],
        muscleStandard: `체중의 ${muscleStandard[0]}~${muscleStandard[1]}%`,
        formulaBmr,
        bmrDiff,
        proteinTarget: Math.round(w * 1.6)
    };
}

/** 프롬프트에 넣는 판정 글자 (프롬프트는 언제나 한국어다) */
const LEVEL_KO = { low: "표준 이하", standard: "표준", high: "표준 이상", unknown: "정보 없음" };

/**
 * 모델 응답 + 수치를 앱이 그대로 보여줄 글로 조립한다. 식단 리포트처럼 서버가 조립해야
 * 구버전·신버전이 같은 글을 본다. 모델이 칸을 비우면 그 칸은 뺀다.
 */
function inbodyReportText(data, metrics, inbody, L) {
    const d = data && typeof data === "object" ? data : {};
    const str = (v) => (typeof v === "string" ? v.trim() : "");
    const parts = [];

    const summary = str(d.summary);
    if (summary) parts.push(summary);

    const facts = [];
    if (metrics.bmi > 0) facts.push(`BMI ${metrics.bmi}`);
    if (inbody.bodyFatPercent > 0) facts.push(`${L.inbodyFatShort} ${inbody.bodyFatPercent}% (${L.inbodyLevel[metrics.fatLevel]}, ${L.inbodyStandard} ${metrics.fatStandard})`);
    if (inbody.skeletalMuscle > 0) facts.push(`${L.inbodyMuscleShort} ${inbody.skeletalMuscle}kg (${L.inbodyLevel[metrics.muscleLevel]})`);
    if (inbody.bmr > 0) facts.push(`${L.inbodyBmrShort} ${inbody.bmr}kcal (${L.inbodyFormula} ${metrics.formulaBmr})`);
    if (inbody.visceralFatLevel > 0) facts.push(`${L.inbodyVisceralShort} ${inbody.visceralFatLevel} (${L.inbodyLevel[metrics.visceralLevel]}, ${L.inbodyStandard} ${metrics.visceralStandard})`);
    if (inbody.inbodyScore > 0) facts.push(`${L.inbodyScoreShort} ${inbody.inbodyScore}`);
    if (inbody.bodyWater > 0) facts.push(`${L.inbodyWaterShort} ${inbody.bodyWater}L`);
    if (facts.length) parts.push(`${L.inbodyMetricsTitle}\n${facts.map((f) => `• ${f}`).join("\n")}`);

    const bodyFat = str(d.bodyFat);
    if (bodyFat && inbody.bodyFatPercent > 0) parts.push(`${L.inbodyFatTitle}\n${bodyFat}`);
    const muscle = str(d.muscle);
    if (muscle && inbody.skeletalMuscle > 0) parts.push(`${L.inbodyMuscleTitle}\n${muscle}`);
    const bmr = str(d.bmr);
    if (bmr && inbody.bmr > 0) parts.push(`${L.inbodyBmrTitle}\n${bmr}`);

    const actions = Array.isArray(d.actions) ? d.actions.map(str).filter(Boolean).slice(0, 3) : [];
    if (actions.length) parts.push(`${L.inbodyActionsTitle}\n${actions.map((a, i) => `${i + 1}. ${a}`).join("\n")}`);

    return parts.join("\n\n").trim();
}

module.exports = {
    RANGES, FAT_STANDARD, SMM_RATIO_STANDARD, VISCERAL_STANDARD,
    normalizeInbody, resolveBmr, inbodyPromptLine,
    ageFrom, bodyMetrics, inbodyReportText
};
