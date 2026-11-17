/**
 * 기간(주·월·연) 정산 평가의 순수 계산부. evaluatePeriod.js가 쓴다.
 *
 * 식단 분석(analyzeDiet)·인바디 해석(analyzeInbody)과 같은 원칙이다.
 * **숫자는 서버가 센다.** 모델에게 30일치 기록을 주고 "평균 내 보라"고 하면 틀린다.
 * 여기서 합계·평균·권장 대비·체중 변화·흐름을 다 계산해 프롬프트에 적고,
 * 모델은 그 숫자를 **말로 풀어 평가하는 일**만 한다.
 *
 * 이 파일은 Firestore·Gemini를 모르는 순수 함수만 둔다. 그래야 테스트가 모델 없이 돈다.
 */
const { recommendedIntake } = require("./nutrition");

/** 한 요청에 받는 날짜 수 상한. 1년 + 여유. 그 이상은 앱이 보낼 일이 없다 */
const MAX_DAYS = 400;

/**
 * 한 날의 식단·운동 텍스트 상한(글자).
 *
 * 한 달치를 프롬프트에 그대로 넣으면 끼니마다 길게 적는 사용자는 수만 토큰이 된다.
 * 평가에 필요한 건 "무엇을 자주 먹었나"지 전문이 아니라서 앞부분만 남긴다.
 */
const MAX_TEXT = 80;

/** 자주 먹은 음식·운동 목록 상한 */
const MAX_TOP_ITEMS = 12;

/** 체지방 1kg의 열량. 순칼로리 합계를 "지방 몇 kg어치"로 환산할 때 쓴다 */
const KCAL_PER_KG_FAT = 7700;

const PERIOD_TYPES = ["week", "month", "year"];

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function num(v) {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
}

function text(v, max = MAX_TEXT) {
    if (typeof v !== "string") return "";
    const t = v.replace(/\s+/g, " ").trim();
    return t.length > max ? t.slice(0, max) + "…" : t;
}

/**
 * 앱이 보낸 일별 기록을 믿을 수 있는 형태로 고른다.
 * 날짜가 없거나 형식이 다른 줄은 버리고, 같은 날이 두 번 오면 뒤의 것을 쓴다. 날짜 오름차순.
 */
function normalizeDays(raw) {
    if (!Array.isArray(raw)) return [];
    const byDate = new Map();
    for (const d of raw) {
        if (!d || typeof d !== "object") continue;
        const date = typeof d.date === "string" ? d.date.trim() : "";
        if (!DATE_RE.test(date)) continue;
        byDate.set(date, {
            date,
            net: Math.round(num(d.net)),
            breakfastKcal: Math.max(0, Math.round(num(d.breakfastKcal))),
            lunchKcal: Math.max(0, Math.round(num(d.lunchKcal))),
            dinnerKcal: Math.max(0, Math.round(num(d.dinnerKcal))),
            snackKcal: Math.max(0, Math.round(num(d.snackKcal))),
            exerciseKcal: Math.max(0, Math.round(num(d.exerciseKcal))),
            carb: Math.max(0, num(d.carb)),
            protein: Math.max(0, num(d.protein)),
            fat: Math.max(0, num(d.fat)),
            bmr: Math.max(0, Math.round(num(d.bmr))),
            weight: Math.max(0, num(d.weight)),
            steps: Math.max(0, Math.round(num(d.steps))),
            hasExercise: !!d.hasExercise,
            breakfast: text(d.breakfast),
            lunch: text(d.lunch),
            dinner: text(d.dinner),
            snack: text(d.snack),
            exercise: text(d.exercise)
        });
    }
    return [...byDate.values()]
        .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
        .slice(0, MAX_DAYS);
}

/** "자주 먹은 음식" 같은 {name, count} 목록 정리. 이름 없는 것·0회는 버린다 */
function normalizeTopItems(raw) {
    if (!Array.isArray(raw)) return [];
    return raw
        .filter((it) => it && typeof it === "object")
        .map((it) => ({ name: text(it.name, 40), count: Math.round(num(it.count)) }))
        .filter((it) => it.name && it.count > 0)
        .sort((a, b) => b.count - a.count)
        .slice(0, MAX_TOP_ITEMS);
}

/** yyyy-MM-dd → UTC 자정 Date. 형식이 틀리면 null */
function parseDate(s) {
    if (typeof s !== "string" || !DATE_RE.test(s)) return null;
    const d = new Date(s + "T00:00:00Z");
    return Number.isNaN(d.getTime()) ? null : d;
}

/** 두 날짜 사이의 일수 (양 끝 포함). 순서가 뒤집혔거나 못 읽으면 0 */
function daysBetween(start, end) {
    const a = parseDate(start);
    const b = parseDate(end);
    if (!a || !b || b < a) return 0;
    return Math.round((b - a) / 86400000) + 1;
}

/** ISO 주차 키 "yyyy-Www". 월요일 시작 — 앱의 getWeekIdentifier(월요일 시작, 첫 주 4일)와 같은 규칙 */
function isoWeekKey(dateStr) {
    const d = parseDate(dateStr);
    if (!d) return "";
    const day = d.getUTCDay() || 7;             // 월=1 … 일=7
    d.setUTCDate(d.getUTCDate() + 4 - day);     // 그 주의 목요일로
    const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
    const week = Math.ceil(((d - yearStart) / 86400000 + 1) / 7);
    return `${d.getUTCFullYear()}-W${String(week).padStart(2, "0")}`;
}

function average(values) {
    if (values.length === 0) return 0;
    return values.reduce((a, b) => a + b, 0) / values.length;
}

function round1(v) {
    return Math.round(v * 10) / 10;
}

/**
 * 기간 집계.
 *
 * @param days normalizeDays를 거친 목록 (오름차순)
 * @param period { type, start, end }
 * @param today yyyy-MM-dd. 진행 중인 기간의 "경과 일수"를 세는 기준
 * @param fallbackWeight 기간 안에 체중 기록이 없을 때 권장 단백질 계산에 쓸 체중
 */
function periodStats(days, period, today, fallbackWeight) {
    const type = PERIOD_TYPES.includes(period && period.type) ? period.type : "month";
    const start = period && period.start;
    const end = period && period.end;

    // 진행 중인 기간이면 오늘까지만 센다. 11월 10일에 11월을 평가하는데 "30일 중 10일 기록"이라고
    // 하면 20일을 빼먹은 것처럼 읽힌다.
    const inProgress = !!(today && end && today < end);
    const effectiveEnd = inProgress ? today : end;
    const totalDays = daysBetween(start, end);
    const elapsedDays = Math.max(daysBetween(start, effectiveEnd), days.length);

    const nets = days.map((d) => d.net);
    const netTotal = nets.reduce((a, b) => a + b, 0);
    const exerciseDays = days.filter((d) => d.hasExercise || d.exerciseKcal > 0).length;

    const withNutrition = days.filter((d) => d.bmr > 0);
    const weighed = days.filter((d) => d.weight > 0);
    const weightForTarget = weighed.length > 0 ? weighed[weighed.length - 1].weight : num(fallbackWeight);
    const avgBmr = Math.round(average(withNutrition.map((d) => d.bmr)));
    const recommended = avgBmr > 0 ? recommendedIntake(avgBmr, weightForTarget) : null;

    const intakeOf = (d) => d.breakfastKcal + d.lunchKcal + d.dinnerKcal + d.snackKcal;
    const withSteps = days.filter((d) => d.steps > 0);

    const best = days.length ? days.reduce((m, d) => (d.net < m.net ? d : m)) : null;
    const worst = days.length ? days.reduce((m, d) => (d.net > m.net ? d : m)) : null;

    return {
        type,
        inProgress,
        totalDays,
        elapsedDays,
        recordDays: days.length,
        exerciseDays,
        deficitDays: days.filter((d) => d.net < 0).length,
        surplusDays: days.filter((d) => d.net > 0).length,
        netTotal,
        netAvg: days.length ? Math.round(netTotal / days.length) : 0,
        intakeAvg: withNutrition.length ? Math.round(average(withNutrition.map(intakeOf))) : 0,
        burnAvg: withNutrition.length ? Math.round(average(withNutrition.map((d) => d.exerciseKcal))) : 0,
        stepsAvg: withSteps.length ? Math.round(average(withSteps.map((d) => d.steps))) : 0,
        nutritionDays: withNutrition.length,
        carbAvg: round1(average(withNutrition.map((d) => d.carb))),
        proteinAvg: round1(average(withNutrition.map((d) => d.protein))),
        fatAvg: round1(average(withNutrition.map((d) => d.fat))),
        avgBmr,
        recommended,
        weightFirst: weighed.length ? weighed[0].weight : 0,
        weightLast: weighed.length ? weighed[weighed.length - 1].weight : 0,
        weightDiff: weighed.length >= 2 ? round1(weighed[weighed.length - 1].weight - weighed[0].weight) : null,
        // 지방 환산은 음수가 감량이다. 반올림은 소수 첫째 자리까지
        estFatKg: round1(netTotal / KCAL_PER_KG_FAT),
        bestDay: best ? { date: best.date, net: best.net } : null,
        worstDay: worst && worst !== best ? { date: worst.date, net: worst.net } : null,
        buckets: bucketsOf(days, type)
    };
}

/**
 * 기간 안의 흐름. 연은 달별, 월은 주별로 묶는다. 주는 이미 하루 단위라 묶지 않는다.
 * "앞 달은 잘하다 뒤로 갈수록 무너졌다" 같은 평가를 하려면 이게 있어야 한다.
 */
function bucketsOf(days, type) {
    if (type === "week") return [];
    const keyOf = type === "year" ? (d) => d.date.slice(0, 7) : (d) => isoWeekKey(d.date);
    const groups = new Map();
    for (const d of days) {
        const key = keyOf(d);
        if (!key) continue;
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(d);
    }
    return [...groups.entries()].map(([label, list]) => {
        const weighed = list.filter((d) => d.weight > 0);
        return {
            label,
            days: list.length,
            exerciseDays: list.filter((d) => d.hasExercise || d.exerciseKcal > 0).length,
            netAvg: Math.round(average(list.map((d) => d.net))),
            weightLast: weighed.length ? weighed[weighed.length - 1].weight : 0
        };
    });
}

function signed(n) {
    return (n > 0 ? "+" : "") + n;
}

function fmt(n) {
    return Math.round(n).toLocaleString("en-US");
}

/**
 * 프롬프트에 넣을 사실 목록. 모델이 계산할 거리를 남기지 않는 게 목적이라 다 적는다.
 * 없는 값(0)은 줄 자체를 빼서 "체중 0kg"처럼 읽히지 않게 한다.
 */
function factLines(stats, period, topFoods, topExercises) {
    const typeName = { week: "한 주", month: "한 달", year: "일 년" }[stats.type];
    const lines = [];

    lines.push(`- 기간: ${period.title || period.label || ""} (${period.start} ~ ${period.end}, ${typeName})`);
    if (stats.inProgress) {
        lines.push(`- 아직 진행 중인 기간입니다. 전체 ${stats.totalDays}일 중 ${stats.elapsedDays}일이 지났습니다`);
    }
    lines.push(`- 기록한 날 ${stats.recordDays}일 / 지난 날 ${stats.elapsedDays}일 (기록하지 않은 날은 "안 먹은 날"이 아니라 "안 적은 날"입니다)`);
    lines.push(`- 운동한 날 ${stats.exerciseDays}일`);
    lines.push(`- 순칼로리 합계 ${signed(stats.netTotal)} kcal, 하루 평균 ${signed(stats.netAvg)} kcal (적자 ${stats.deficitDays}일 / 흑자 ${stats.surplusDays}일)`);
    if (stats.recordDays > 0) {
        lines.push(`- 순칼로리 합계를 체지방으로 환산하면 약 ${signed(stats.estFatKg)} kg (7,700 kcal = 1 kg, 음수가 감량)`);
    }
    if (stats.nutritionDays > 0) {
        lines.push(`- 하루 평균 섭취 ${fmt(stats.intakeAvg)} kcal, 운동 소모 ${fmt(stats.burnAvg)} kcal (영양 집계가 있는 ${stats.nutritionDays}일 기준)`);
        const rec = stats.recommended;
        const recText = rec ? ` / 권장 탄 ${rec.carb}g · 단 ${rec.protein}g · 지 ${rec.fat}g (권장 섭취 ${fmt(rec.calories)} kcal)` : "";
        lines.push(`- 하루 평균 탄단지: 탄 ${stats.carbAvg}g · 단 ${stats.proteinAvg}g · 지 ${stats.fatAvg}g${recText}`);
    }
    if (stats.stepsAvg > 0) lines.push(`- 하루 평균 걸음 ${fmt(stats.stepsAvg)}보`);
    if (stats.weightDiff !== null) {
        lines.push(`- 체중 ${stats.weightFirst}kg → ${stats.weightLast}kg (${signed(stats.weightDiff)} kg)`);
    } else if (stats.weightLast > 0) {
        lines.push(`- 체중 ${stats.weightLast}kg (기간 안에 한 번만 적어 변화는 알 수 없음)`);
    }
    if (stats.bestDay) lines.push(`- 가장 잘한 날 ${stats.bestDay.date} (${signed(stats.bestDay.net)} kcal)`);
    if (stats.worstDay) lines.push(`- 가장 많이 먹은 날 ${stats.worstDay.date} (${signed(stats.worstDay.net)} kcal)`);

    if (stats.buckets.length > 1) {
        const unit = stats.type === "year" ? "달" : "주";
        lines.push(`- ${unit}별 흐름:`);
        for (const b of stats.buckets) {
            const w = b.weightLast > 0 ? `, 체중 ${b.weightLast}kg` : "";
            lines.push(`    ${b.label}: 기록 ${b.days}일, 운동 ${b.exerciseDays}일, 하루 평균 ${signed(b.netAvg)} kcal${w}`);
        }
    }
    if (topFoods.length) {
        lines.push(`- 자주 먹은 음식: ${topFoods.map((f) => `${f.name}(${f.count}회)`).join(", ")}`);
    }
    if (topExercises.length) {
        lines.push(`- 자주 한 운동: ${topExercises.map((f) => `${f.name}(${f.count}회)`).join(", ")}`);
    }
    return lines;
}

/**
 * 일별 기록 줄. 주·월만 넣는다. 연은 365줄이 되어 흐름(달별)으로 대신한다.
 * 비어 있는 끼니는 적지 않는다.
 */
function dayLines(days, type) {
    if (type === "year") return [];
    return days.map((d) => {
        const meals = [];
        if (d.breakfast) meals.push(`아침: ${d.breakfast}`);
        if (d.lunch) meals.push(`점심: ${d.lunch}`);
        if (d.dinner) meals.push(`저녁: ${d.dinner}`);
        if (d.snack) meals.push(`간식: ${d.snack}`);
        const ex = d.exercise ? ` / 운동: ${d.exercise}` : (d.hasExercise ? " / 운동 있음" : "");
        const w = d.weight > 0 ? ` / ${d.weight}kg` : "";
        return `    ${d.date} 순 ${signed(d.net)} kcal${w} | ${meals.join(" · ") || "(식단 없음)"}${ex}`;
    });
}

/**
 * 모델 응답을 앱이 그릴 형태로 고른다. 없는 필드는 빈 값, 점수는 0~100 정수.
 * 전부 비어 있으면 null — 그러면 호출한 쪽이 실패로 다룬다.
 */
function normalizeEvaluation(data) {
    const d = data && typeof data === "object" ? data : {};
    const str = (v) => (typeof v === "string" ? v.trim() : "");
    const scoreRaw = Number(d.score);
    const score = Number.isFinite(scoreRaw) ? Math.min(100, Math.max(0, Math.round(scoreRaw))) : 0;
    const actions = Array.isArray(d.actions) ? d.actions.map(str).filter(Boolean).slice(0, 3) : [];
    const result = {
        score,
        headline: str(d.headline),
        summary: str(d.summary),
        diet: str(d.diet),
        exercise: str(d.exercise),
        actions
    };
    if (!result.summary && !result.diet && !result.exercise) return null;
    return result;
}

module.exports = {
    MAX_DAYS, MAX_TEXT, PERIOD_TYPES, KCAL_PER_KG_FAT,
    normalizeDays, normalizeTopItems, periodStats, factLines, dayLines, normalizeEvaluation,
    isoWeekKey, daysBetween
};
