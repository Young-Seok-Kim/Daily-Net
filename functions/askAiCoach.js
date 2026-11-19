/**
 * 전역 AI 질문(askAiCoach).
 *
 * 앱의 조회 화면(일일 목록·일일 정산·월/주/연 정산·인바디·체중 추이)에는 우하단에 AI 버튼이 뜬다.
 * 누르면 **지금 보고 있는 화면**을 앱이 요약해 보내고, 사용자는 그 화면에 대해 바로 묻는다.
 *
 * 하루 분석(analyzeDiet)과 다른 점:
 *  - 숫자를 다시 계산하지 않는다. 화면이 보여주는 값(앱이 contextData로 요약해 보낸 것)만 근거로 쓴다.
 *    서버가 권장량·순칼로리를 새로 계산하면 화면과 답이 어긋난다.
 *  - 응답이 JSON이 아니라 **글**이다. 앱은 그 글을 말풍선에 그대로 넣는다.
 *  - 세션 안에서만 이어지는 대화라 저장하지 않는다. history는 앱이 들고 있다가 매 요청에 다시 보낸다.
 *  - 분석 횟수를 소모하지 않고 따로 센다 (quota.reserveAiAsk).
 *
 * 사용자 입력은 전부 프롬프트 구조를 깨지 못하게 다듬는다 — 화면 데이터의 줄바꿈을 눌러 한 덩어리로
 * 만들고 길이를 자른다(functions/aiContext.js 와 같은 이유).
 */
const { onRequest } = require("firebase-functions/v2/https");
const { rejectNonAppRequest } = require("./requestGuard");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { LABELS, resolveLang } = require("./labels");
const { retryReason, paidModel } = require("./gemini");
const { verifyUser, reserveAiAsk, refundAiAsk, withTimeout, QUOTA_TIMEOUT_MS } = require("./quota");
const { normalizeAiContext, aiContextPromptBlock } = require("./aiContext");
const admin = require("firebase-admin");

if (admin.apps.length === 0) {
    admin.initializeApp();
}

/** 질문 하나가 가리킬 수 있는 날짜 수. "어제랑 그제" 정도면 충분하고, 더 많으면 프롬프트만 길어진다 */
const MAX_ASKED_DATES = 3;

/** "N일 전"으로 거슬러 갈 수 있는 상한. 그보다 먼 날은 월간 화면으로 보내는 편이 낫다 */
const MAX_DAYS_BACK = 31;

/** 모델이 추가 데이터를 요청했을 때 저장소 읽기에 주는 시간. 넘기면 1차 답으로 돌아간다 */
const NEED_FETCH_TIMEOUT_MS = 6000;

/** [NEED:weight:N]의 N 범위와, 체중 점을 프롬프트에 넣을 때의 상한 */
const WEIGHT_DAYS_MIN = 7;
const WEIGHT_DAYS_MAX = 365;
const WEIGHT_POINTS_MAX = 90;

/** [NEED:months:yyyy]로 거슬러 갈 수 있는 햇수 */
const MONTHS_YEARS_BACK = 3;

/** 인바디 이력은 최근 몇 건까지 */
const INBODY_MAX = 6;

/** 질문 한 번의 길이 상한. 앱 입력창도 같은 값으로 자른다(AiAsk.MAX_QUESTION) */
const MAX_QUESTION_CHARS = 500;

/** 이어 보낼 수 있는 대화의 최대 왕복 수(질문+답 한 쌍을 1로 센다). 앱은 3왕복만 보낸다 */
const MAX_HISTORY_TURNS = 6;

/** history 한 줄의 길이 상한. 답변이 길어도 앞뒤 맥락만 남기면 충분하다 */
const MAX_HISTORY_CHARS = 600;

/** 화면 데이터 상한. 한 달치 일별 요약까지 들어가므로 넉넉하되 프롬프트가 무너지지 않게 자른다 */
const MAX_CONTEXT_CHARS = 2000;

/** 화면 이름(뱃지에 그대로 보이는 말)의 길이 상한 */
const MAX_TITLE_CHARS = 60;

/**
 * 앱이 보내는 화면 종류. 앱의 AiScreenTypes와 같아야 한다.
 * 여기 없는 값이 와도 거절하지 않고 "unknown"으로 두고 제목·데이터만으로 답한다
 * (앱이 먼저 배포돼 새 화면을 보내는 경우).
 */
const SCREEN_TYPES = [
    "daily_list",
    "daily_record",
    "month_report",
    "period_report",
    "inbody_list",
    "inbody_trend",
    "weight_trend"
];

/**
 * 모델에게 알려 주는 화면 안내. "그 값은 어디서 보나"에 답하고, 이동 표식(GOTO)을 고를 때 쓴다.
 * 앱의 AiScreenTypes 주석과 같은 뜻이어야 한다.
 */
const SCREEN_GUIDE = [
    "daily_list: 기록 목록(홈). 날짜별 순칼로리 목록과 주·월·연 정산 입구",
    "daily_record: 하루 정산 상세. 끼니별 음식·kcal, 운동, 체중, 걸음, AI 리포트. 특정 날짜면 daily_record:yyyy-MM-dd",
    "month_report: 월간 정산. 한 달 순칼로리 흐름·주별 비교·체중 그래프. 특정 달이면 month_report:yyyy-MM",
    "period_report: 주·연 정산. 그 기간의 합계와 AI 평가",
    "inbody_list: 인바디 측정 이력. 체지방률·골격근량 등 측정값",
    "inbody_trend: 인바디 추이 그래프",
    "weight_trend: 체중 추이 그래프"
];

/** 이동 표식 뒤에 붙는 날짜 인자의 꼴. 화면마다 다르고, 없는 화면은 인자를 받지 않는다 */
const NAV_ARG_SHAPE = {
    daily_record: /^\d{4}-\d{2}-\d{2}$/,
    month_report: /^\d{4}-\d{2}$/
};

const GOTO_MARK = /\[GOTO:([a-z_]+)(?::([^\]]{0,20}))?\]/g;

/**
 * 마크다운 기호를 걷어낸다. 앱 말풍선은 글자를 그대로 그리므로 **굵게** 가 별표째 보인다.
 * 프롬프트에서 금지하지만 모델이 습관처럼 섞어 오므로 서버에서 한 번 더 지운다.
 * 불릿은 "- "로 통일해 남긴다 (글자 그대로 봐도 읽힌다).
 */
function stripMarkdown(text) {
    return String(text || "")
        .replace(/^\s{0,3}#{1,6}\s+/gm, "")          // 제목 #
        .replace(/\*\*(.+?)\*\*/g, "$1")             // **굵게**
        .replace(/__(.+?)__/g, "$1")                  // __굵게__
        .replace(/(^|[^*\w])\*(?!\s)([^*\n]+?)\*(?!\w)/g, "$1$2")  // *기울임* (불릿 "* "는 건드리지 않는다)
        .replace(/(^|[^_\w])_(?!\s)([^_\n]+?)_(?!\w)/g, "$1$2")
        .replace(/`([^`\n]*)`/g, "$1")                // `코드`
        .replace(/^\s*[*•·]\s+/gm, "- ")              // * 불릿 → - 불릿
        .replace(/^\s*\d+\.\s+/gm, "- ")             // 1. 번호 → - 불릿
        .replace(/^\s*>\s?/gm, "")                    // 인용
        .replace(/^\s*[-*_]{3,}\s*$/gm, "");           // 구분선
}

/**
 * 답변 끝의 이동 표식을 떼어 낸다.
 *
 * 모델은 "그 값은 체중 추이 화면에 있습니다"라고 말한 뒤 `[GOTO:weight_trend]`를 붙인다.
 * 앱은 이 값으로 말풍선 아래에 "체중 추이 보기" 버튼을 그린다. 표식 자체는 사용자에게 보이면 안 되므로
 * 자리와 상관없이 모두 지우고, 첫 번째로 **유효한** 표식만 쓴다.
 *
 * - 모르는 화면 코드, 지금 보고 있는 화면은 버린다 (같은 화면으로 가는 버튼은 의미가 없다)
 * - 날짜 인자는 화면별 꼴에 맞을 때만 남긴다. 틀린 인자는 버리되 화면 이동은 살린다
 *
 * @returns {{answer: string, navigateTo: string|null, navigateArg: string|null}}
 */
function splitNavigation(text, currentScreenType) {
    const raw = typeof text === "string" ? text : "";
    let navigateTo = null;
    let navigateArg = null;
    for (const match of raw.matchAll(GOTO_MARK)) {
        const [, screen, arg] = match;
        if (navigateTo) continue;
        if (!SCREEN_TYPES.includes(screen) || screen === currentScreenType) continue;
        navigateTo = screen;
        const shape = NAV_ARG_SHAPE[screen];
        navigateArg = shape && arg && shape.test(arg) ? arg : null;
    }
    const answer = stripMarkdown(raw.replace(GOTO_MARK, ""))
        .replace(/ {2,}/g, " ")          // 본문 중간에서 뗀 자리의 겹친 공백
        .replace(/[ \t]+$/gm, "")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
    return { answer, navigateTo, navigateArg };
}

/** 앱이 시간대를 안 보내거나 틀린 값을 보냈을 때 쓰는 기본 시간대 */
const DEFAULT_TIME_ZONE = "Asia/Seoul";

/**
 * 앱이 보낸 IANA 시간대("Asia/Seoul", "America/Los_Angeles"). 모르는 값이면 기본값.
 * 기록의 날짜는 앱이 **기기 시간대**로 매기므로(SimpleDateFormat 기본값), "어제"도 같은 시간대로 풀어야
 * 저장된 문서 ID(yyyy-MM-dd)와 맞는다. 해외에서 쓰는 사람에게 서울 자정은 엉뚱한 때다.
 */
function normalizeTimeZone(raw) {
    if (typeof raw !== "string" || !raw || raw.length > 64) return DEFAULT_TIME_ZONE;
    try {
        new Intl.DateTimeFormat("en-US", { timeZone: raw });
        return raw;
    } catch (_) {
        return DEFAULT_TIME_ZONE;
    }
}

/**
 * 프롬프트에 적을 "지금". 사용자 시간대 기준 날짜·요일·시각 ("2026-11-19 목요일 14:32").
 * 모델은 시계가 없다. 날짜가 없으면 "어제"를 못 풀고, 시각이 없으면 "저녁 추천"에 아침 메뉴를 낸다.
 * 시각은 서버가 만든다 — 폰 시계가 틀려도 영향이 없다. 시간대만 앱이 알려 준다.
 */
function nowText(date = new Date(), timeZone = DEFAULT_TIME_ZONE) {
    const parts = new Intl.DateTimeFormat("ko-KR", {
        timeZone,
        year: "numeric", month: "2-digit", day: "2-digit",
        weekday: "short", hour: "2-digit", minute: "2-digit", hour12: false
    }).formatToParts(date);
    const get = (type) => (parts.find((p) => p.type === type) || {}).value || "";
    // hour12:false 인데도 일부 런타임이 자정을 "24"로 낸다
    const hour = get("hour") === "24" ? "00" : get("hour");
    return `${get("year")}-${get("month")}-${get("day")} ${get("weekday")}요일 ${hour}:${get("minute")}`;
}

/** yyyy-MM-dd 에 일수를 더한다 (UTC 자정 기준이라 시간대 영향이 없다) */
function shiftDate(ymd, days) {
    const d = new Date(`${ymd}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
}

/**
 * 질문이 가리키는 날짜를 뽑는다 (오늘은 제외 — 화면 데이터가 이미 오늘일 때가 대부분이다).
 *
 * 모델은 "어제"를 못 풀고(시계가 없다), 설령 풀어도 그 날 기록이 프롬프트에 없으면 오늘 값을 어제 것처럼
 * 말한다(2026-11-19 실제 사례). 그래서 서버가 날짜를 먼저 알아내 그 날 기록을 읽어 넣어 준다.
 *
 * 받는 꼴: 어제·그제·그저께·엊그제·N일 전·일주일 전·N주 전·yyyy-MM-dd·M월 D일·yesterday·N days ago·N weeks ago.
 * "M월 D일"이 오늘보다 뒤면 작년으로 본다 (지난 기록만 있다).
 * "지난주"처럼 기간을 가리키는 말은 하루로 못 바꾸므로 여기서 다루지 않는다 (모델이 주간 정산으로 안내한다).
 *
 * @returns {string[]} yyyy-MM-dd, 오래된 날부터, 중복 없이 MAX_ASKED_DATES개까지
 */
function extractAskedDates(question, today) {
    const q = String(question || "");
    const found = new Set();
    const add = (ymd) => {
        if (!ymd || ymd === today || ymd > today) return;
        if (shiftDate(today, -MAX_DAYS_BACK) > ymd && !/^\d{4}-/.test(ymd)) return;
        found.add(ymd);
    };

    if (/어제|yesterday/i.test(q)) add(shiftDate(today, -1));
    if (/그제|그저께|엊그제/.test(q)) add(shiftDate(today, -2));
    // "일주일 전"·"한 주 전"·"a week ago" = 7일 전. 숫자가 붙으면 그 배수
    if (/(일주일|한\s*주)\s*전|\b(a|one)\s+week\s+ago/i.test(q)) add(shiftDate(today, -7));
    for (const m of q.matchAll(/(\d{1,2})\s*주(?:일)?\s*전/g)) {
        const n = Number(m[1]);
        if (n >= 1 && n * 7 <= MAX_DAYS_BACK) add(shiftDate(today, -7 * n));
    }
    for (const m of q.matchAll(/(\d{1,2})\s*weeks?\s*ago/gi)) {
        const n = Number(m[1]);
        if (n >= 1 && n * 7 <= MAX_DAYS_BACK) add(shiftDate(today, -7 * n));
    }
    for (const m of q.matchAll(/(\d{1,2})\s*일\s*전/g)) {
        const n = Number(m[1]);
        if (n >= 1 && n <= MAX_DAYS_BACK) add(shiftDate(today, -n));
    }
    for (const m of q.matchAll(/(\d{1,2})\s*days?\s*ago/gi)) {
        const n = Number(m[1]);
        if (n >= 1 && n <= MAX_DAYS_BACK) add(shiftDate(today, -n));
    }
    for (const m of q.matchAll(/(\d{4})-(\d{2})-(\d{2})/g)) {
        add(`${m[1]}-${m[2]}-${m[3]}`);
    }
    for (const m of q.matchAll(/(\d{1,2})\s*월\s*(\d{1,2})\s*일/g)) {
        const mm = String(m[1]).padStart(2, "0");
        const dd = String(m[2]).padStart(2, "0");
        const year = Number(today.slice(0, 4));
        let ymd = `${year}-${mm}-${dd}`;
        if (ymd > today) ymd = `${year - 1}-${mm}-${dd}`;
        add(ymd);
    }
    return [...found].sort().slice(0, MAX_ASKED_DATES);
}

/**
 * 질문이 특정 날짜를 가리켰으면 그 날 정산은 이미 프롬프트에 넣어 답했다. 그런데 모델이 "자세히는 정산 상세에서"라며
 * 기록 목록·정산 상세로 가는 버튼을 붙이면, 가서 같은 질문을 또 하게 된다 (2026-11-19 "일주일 전" 사례 — 홈으로 갔다).
 * 그래서 날짜를 물은 질문에서는 그 두 화면으로의 이동을 뗀다. 체중 추이·인바디처럼 다른 종류의 화면은 그대로 둔다.
 */
function suppressDailyNav(nav, askedDates) {
    if (!askedDates.length) return nav;
    if (nav.navigateTo !== "daily_list" && nav.navigateTo !== "daily_record") return nav;
    return { ...nav, navigateTo: null, navigateArg: null };
}

/**
 * 저장된 하루 정산을 프롬프트용 몇 줄로. 앱의 AiAskContext.dailyRecord와 같은 모양이라
 * 모델이 화면 데이터와 같은 눈으로 읽는다. 문서가 없으면 "(기록 없음)".
 */
function summarizeRecord(date, data) {
    if (!data) return `${date}: (기록 없음)`;
    const n = (v) => Math.round(Number(v) || 0);
    const lines = [`date ${date}`];
    if (n(data.weight) > 0) lines.push(`weight ${Number(data.weight).toFixed(1)}kg`);
    const intake = n(data.breakfastKcal) + n(data.lunchKcal) + n(data.dinnerKcal) + n(data.snackKcal);
    lines.push(intake > 0 || n(data.exerciseKcal) > 0
        ? `net ${n(data.netCalories)}kcal (in ${intake}kcal, out ${n(data.exerciseKcal)}kcal)`
        : `net ${n(data.netCalories)}kcal`);
    if (n(data.steps) > 0) lines.push(`steps ${n(data.steps)}`);
    const meal = (tag, text, kcal) => {
        const t = pressLine(text || "").slice(0, 200);
        if (!t) return;
        lines.push(n(kcal) > 0 ? `${tag}: ${t} (${n(kcal)}kcal)` : `${tag}: ${t}`);
    };
    meal("B", data.breakfast, data.breakfastKcal);
    meal("L", data.lunch, data.lunchKcal);
    meal("D", data.dinner, data.dinnerKcal);
    meal("S", data.snack, data.snackKcal);
    meal("exercise", data.exercise, data.exerciseKcal);
    const remark = pressLine(data.remark || "").slice(0, 120);
    if (remark) lines.push(`remark: ${remark}`);
    return lines.join("\n");
}

/**
 * 질문이 가리킨 날짜들의 정산을 읽어 프롬프트 블록으로 만든다. 없으면 빈 문자열.
 * 읽기에 실패하거나 느리면(withTimeout) 블록 없이 진행한다 — 부가 정보라 본 답변을 막지 않는다.
 */
async function fetchAskedRecords(uid, dates) {
    if (!dates.length) return "";
    const col = admin.firestore().collection("users").doc(uid).collection("settlements");
    const snaps = await Promise.all(dates.map((d) => col.doc(d).get()));
    const blocks = dates.map((d, i) => summarizeRecord(d, snaps[i].exists ? snaps[i].data() : null));
    return ["[질문이 가리킨 날짜의 기록 — 서버가 저장소에서 읽어 온 값]", ...blocks].join("\n\n");
}

const NEED_MARK = /\[NEED:([a-z]+)(?::([^\]]{0,12}))?\]/;

/**
 * 모델이 "답 대신" 낸 추가 데이터 요청을 읽는다. 없거나 틀리면 null.
 *
 * 화면에 없는 기록이 꼭 필요할 때 모델은 글을 쓰지 않고 [NEED:종류:인자] 한 줄만 낸다. 서버가 그걸 읽어
 * 저장소에서 가져다 붙이고 한 번 더 묻는다 — 사용자가 화면을 옮겨 같은 질문을 다시 하지 않게 하려는 것이다.
 *
 *  - day:yyyy-MM-dd   그 날 정산 (오늘 이전)
 *  - month:yyyy-MM    그 달의 일별 정산
 *  - months:yyyy      그 해의 월별 집계 (MONTHS_YEARS_BACK 년 전까지)
 *  - weight:N         최근 N일 체중 (WEIGHT_DAYS_MIN~MAX)
 *  - inbody           인바디 측정 이력
 *
 * @returns {{kind: string, arg: string|null}|null}
 */
function parseNeed(text, today) {
    const m = String(text || "").match(NEED_MARK);
    if (!m) return null;
    const [, kind, raw] = m;
    const arg = raw || null;
    const year = Number(today.slice(0, 4));
    switch (kind) {
        case "day":
            return arg && /^\d{4}-\d{2}-\d{2}$/.test(arg) && arg <= today ? { kind, arg } : null;
        case "month":
            return arg && /^\d{4}-\d{2}$/.test(arg) && arg <= today.slice(0, 7) ? { kind, arg } : null;
        case "months": {
            const y = Number(arg);
            return /^\d{4}$/.test(arg || "") && y <= year && y >= year - MONTHS_YEARS_BACK ? { kind, arg } : null;
        }
        case "weight": {
            const n = Number(arg);
            return Number.isInteger(n) && n >= WEIGHT_DAYS_MIN && n <= WEIGHT_DAYS_MAX ? { kind, arg: String(n) } : null;
        }
        case "inbody":
            return { kind, arg: null };
        default:
            return null;
    }
}

/** 월간·연간 블록의 하루 한 줄. 앱의 AiAskContext.dayLine과 같은 모양 */
function dayLine(d) {
    const n = (v) => Math.round(Number(v) || 0);
    const parts = [`net ${n(d.netCalories)}kcal`];
    if (Number(d.weight) > 0) parts.push(`weight ${Number(d.weight).toFixed(1)}kg`);
    if (d.hasExercise || pressLine(d.exercise || "")) parts.push("exercise yes");
    const meals = [];
    for (const [tag, key] of [["B", "breakfast"], ["L", "lunch"], ["D", "dinner"], ["S", "snack"]]) {
        const t = pressLine(d[key] || "").slice(0, 60);
        if (t) meals.push(`${tag}: ${t}`);
    }
    const head = `- ${d.date} ${parts.join(", ")}`;
    return meals.length ? `${head} | ${meals.join(" ")}` : head;
}

/** 한 달 일별 정산 블록. 기록은 date 오름차순으로 들어온다 */
function formatMonth(ym, records) {
    const title = `[${ym} 일별 정산 — 서버가 저장소에서 읽어 온 값]`;
    if (!records.length) return `${title}\n(기록 없음)`;
    const total = records.reduce((s, d) => s + (Math.round(Number(d.netCalories)) || 0), 0);
    const lines = [title, `recorded ${records.length} days, net total ${total}kcal, avg ${Math.round(total / records.length)}kcal/day`];
    records.forEach((d) => lines.push(dayLine(d)));
    return lines.join("\n");
}

/** 한 해를 달별로 묶는다. 기록은 date 오름차순 */
function formatMonths(yyyy, records) {
    const title = `[${yyyy}년 월별 집계 — 서버가 저장소에서 읽어 온 값]`;
    if (!records.length) return `${title}\n(기록 없음)`;
    const byMonth = new Map();
    for (const d of records) {
        const ym = String(d.date || "").slice(0, 7);
        if (!ym) continue;
        const b = byMonth.get(ym) || { days: 0, net: 0, exercise: 0, firstW: 0, lastW: 0 };
        b.days++;
        b.net += Math.round(Number(d.netCalories)) || 0;
        if (d.hasExercise || pressLine(d.exercise || "")) b.exercise++;
        const w = Number(d.weight) || 0;
        if (w > 0) {
            if (!b.firstW) b.firstW = w;
            b.lastW = w;
        }
        byMonth.set(ym, b);
    }
    const lines = [title];
    for (const [ym, b] of [...byMonth.entries()].sort()) {
        let line = `- ${ym}: ${b.days} days, net total ${b.net}kcal, avg ${Math.round(b.net / b.days)}kcal/day, exercise ${b.exercise} days`;
        if (b.firstW && b.lastW) line += `, weight ${b.firstW.toFixed(1)}→${b.lastW.toFixed(1)}kg`;
        lines.push(line);
    }
    return lines.join("\n");
}

/** 최근 N일 체중. 체중이 있는 날만, 너무 많으면 고르게 솎아 WEIGHT_POINTS_MAX개 */
function formatWeight(days, records) {
    const title = `[최근 ${days}일 체중 — 서버가 저장소에서 읽어 온 값]`;
    let points = records.filter((d) => Number(d.weight) > 0);
    if (!points.length) return `${title}\n(기록 없음)`;
    if (points.length > WEIGHT_POINTS_MAX) {
        const step = points.length / WEIGHT_POINTS_MAX;
        const picked = [];
        for (let i = 0; i < WEIGHT_POINTS_MAX; i++) picked.push(points[Math.floor(i * step)]);
        picked[picked.length - 1] = points[points.length - 1];
        points = picked;
    }
    const first = Number(points[0].weight);
    const last = Number(points[points.length - 1].weight);
    const lines = [title, `${points.length} points, ${first.toFixed(1)}kg → ${last.toFixed(1)}kg (${(last - first >= 0 ? "+" : "")}${(last - first).toFixed(1)}kg)`];
    lines.push(points.map((d) => `${d.date} ${Number(d.weight).toFixed(1)}`).join(", "));
    return lines.join("\n");
}

/** 인바디 이력. 최근 INBODY_MAX건, 오래된 것부터 */
function formatInbody(records) {
    const title = "[인바디 측정 이력 — 서버가 저장소에서 읽어 온 값]";
    const rows = records
        .filter((r) => r && r.measuredAt && !r.isDummy)
        .sort((a, b) => String(a.measuredAt).localeCompare(String(b.measuredAt)))
        .slice(-INBODY_MAX);
    if (!rows.length) return `${title}\n(기록 없음)`;
    const f1 = (v) => (Number(v) || 0).toFixed(1);
    const lines = [title];
    for (const r of rows) {
        const parts = [`weight ${f1(r.weight)}kg`, `fat ${f1(r.bodyFatPercent)}%`, `muscle ${f1(r.skeletalMuscle)}kg`];
        if (Number(r.bmr) > 0) parts.push(`bmr ${Math.round(Number(r.bmr))}kcal`);
        if (Number(r.visceralFatLevel) > 0) parts.push(`visceral ${Math.round(Number(r.visceralFatLevel))}`);
        if (Number(r.inbodyScore) > 0) parts.push(`score ${Math.round(Number(r.inbodyScore))}`);
        lines.push(`- ${r.measuredAt}: ${parts.join(", ")}`);
    }
    return lines.join("\n");
}

/** settlements에서 날짜 범위를 읽는다 (문서의 date 필드 기준, 오름차순) */
async function readSettlements(uid, from, to) {
    const snap = await admin.firestore().collection("users").doc(uid).collection("settlements")
        .where("date", ">=", from).where("date", "<=", to).orderBy("date").get();
    return snap.docs.map((d) => d.data());
}

/** 모델의 요청(parseNeed 결과)을 저장소에서 읽어 블록으로 만든다 */
async function fetchNeeded(uid, need, today) {
    switch (need.kind) {
        case "day": {
            const snap = await admin.firestore().collection("users").doc(uid).collection("settlements").doc(need.arg).get();
            return ["[질문이 가리킨 날짜의 기록 — 서버가 저장소에서 읽어 온 값]", summarizeRecord(need.arg, snap.exists ? snap.data() : null)].join("\n\n");
        }
        case "month":
            return formatMonth(need.arg, await readSettlements(uid, `${need.arg}-01`, `${need.arg}-31`));
        case "months":
            return formatMonths(need.arg, await readSettlements(uid, `${need.arg}-01-01`, `${need.arg}-12-31`));
        case "weight":
            return formatWeight(Number(need.arg), await readSettlements(uid, shiftDate(today, -Number(need.arg)), today));
        case "inbody": {
            const snap = await admin.firestore().collection("users").doc(uid).collection("inbody").get();
            return formatInbody(snap.docs.map((d) => d.data()));
        }
        default:
            return "";
    }
}

/** 모든 공백·줄바꿈을 한 칸으로 눌러 한 줄로 만든다 */
function pressLine(raw) {
    return String(raw).replace(/\s+/g, " ").trim();
}

/**
 * 질문을 다듬는다. 비었으면 빈 문자열(=요청 거절).
 * 줄바꿈은 살린다 — 사용자가 여러 줄로 적은 질문은 그대로 읽어야 한다.
 */
function normalizeQuestion(raw) {
    if (typeof raw !== "string") return "";
    const text = raw.replace(/\r\n?/g, "\n").trim();
    if (!text) return "";
    return text.length > MAX_QUESTION_CHARS ? text.slice(0, MAX_QUESTION_CHARS) : text;
}

/**
 * 화면 종류와 화면 이름. 제목은 줄바꿈을 눌러 한 줄로 만든다(뱃지가 한 줄이다).
 * 제목이 비면 종류 이름을 그대로 쓴다.
 */
function normalizeScreen(body) {
    const raw = body && typeof body === "object" ? body : {};
    const type = SCREEN_TYPES.includes(raw.screenType) ? raw.screenType : "unknown";
    const title = pressLine(raw.screenTitle || "").slice(0, MAX_TITLE_CHARS);
    return { type, title: title || type };
}

/**
 * 화면 데이터. 앱이 만든 요약 텍스트를 그대로 받되, 제어문자를 걷어내고 길이를 자른다.
 * 빈 값이면 빈 문자열 — 프롬프트에 "화면 데이터 없음"으로 적힌다.
 */
function normalizeContextData(raw) {
    if (typeof raw !== "string") return "";
    // 탭은 살린다(앱이 만든 표 모양 요약이 읽기 좋다). 나머지 제어문자만 지운다.
    const cleaned = raw.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "").trim();
    return cleaned.length > MAX_CONTEXT_CHARS ? cleaned.slice(0, MAX_CONTEXT_CHARS) : cleaned;
}

/**
 * 이어 온 대화. role은 user/assistant 둘만 받고, 모르는 값은 버린다.
 * 오래된 것부터 남기고 최근 MAX_HISTORY_TURNS개만 쓴다 (맥락은 최근 쪽이 중요하다).
 */
function normalizeHistory(raw) {
    if (!Array.isArray(raw)) return [];
    const turns = [];
    for (const item of raw) {
        if (!item || typeof item !== "object") continue;
        const role = item.role === "user" || item.role === "assistant" ? item.role : null;
        if (!role) continue;
        const text = typeof item.text === "string" ? item.text.trim() : "";
        if (!text) continue;
        turns.push({ role, text: text.length > MAX_HISTORY_CHARS ? text.slice(0, MAX_HISTORY_CHARS) : text });
    }
    return turns.slice(-MAX_HISTORY_TURNS);
}

/** 대화 기록을 프롬프트 블록으로. 없으면 빈 문자열 */
function historyBlock(history) {
    if (!history.length) return "";
    const lines = history.map((t) => `${t.role === "user" ? "사용자" : "AI"}: ${t.text}`);
    return ["[지금까지의 대화]", ...lines].join("\n");
}

/**
 * 모델에 보낼 프롬프트. 순수 함수라 테스트가 모델 없이 돈다.
 *
 * @param {object} input
 *   - screen: {type, title}
 *   - contextData: 앱이 요약한 화면 데이터 (없으면 "")
 *   - aiContextBlock: 설정의 목표·활동량·메모 블록 (없으면 "")
 *   - history: 정리된 대화
 *   - question: 정리된 질문
 *   - outputLanguage: "한국어" / "English"
 *   - now: 사용자 시간대 기준 지금 ("2026-11-19 목요일 14:32"). 모델은 시계가 없으므로 "어제·저녁"을 이걸로 푼다
 *   - timeZone: 그 시간대 이름. 프롬프트에 함께 적는다
 *   - askedBlock: 질문이 가리킨 다른 날짜의 기록 (fetchAskedRecords). 없으면 ""
 *   - neededBlock: 모델이 1차에서 요청해 읽어 온 데이터 (fetchNeeded). 2차 질의에만 있다
 *   - allowNeed: 추가 데이터 요청([NEED])을 허용할지. 2차 질의에서는 false — 무한히 되묻지 않게
 */
function buildPrompt({ screen, contextData, aiContextBlock: contextBlock, history, question, outputLanguage, now, timeZone, askedBlock, neededBlock, allowNeed = true }) {
    const data = contextData || "(이 화면에서 앱이 보낸 데이터가 없습니다)";
    const tz = timeZone || DEFAULT_TIME_ZONE;
    const current = now || nowText(new Date(), tz);
    const today = current.slice(0, 10);
    const parts = [
        "당신은 다이어트 기록 앱 'DailyNet'의 AI 코치입니다.",
        `지금은 ${current} (사용자 시간대 ${tz}) 입니다. 오늘은 ${today}, 어제는 ${shiftDate(today, -1)}, 그제는 ${shiftDate(today, -2)} 입니다.`,
        `사용자가 지금 앱에서 보고 있는 화면은 '${screen.title}' (${screen.type}) 입니다.`,
        "",
        "[화면 데이터 — 앱이 지금 화면에 보여주고 있는 값. 이 화면의 날짜·기간 것만 들어 있습니다]",
        data
    ];
    if (askedBlock) {
        parts.push("", askedBlock);
    }
    if (neededBlock) {
        parts.push("", neededBlock);
    }
    if (contextBlock) {
        parts.push("", contextBlock);
    }
    const past = historyBlock(history);
    if (past) parts.push("", past);
    parts.push(
        "",
        "[질문]",
        question,
        "",
        "[지침]",
        "1. 위 [화면 데이터]에 있는 값만 근거로 답하십시오. 없는 수치를 만들거나 추측해서 단정하지 마십시오.",
        "   화면에 없는 값이 필요하면 \"이 화면에는 그 값이 없습니다\"라고 말하고, 어디서 볼 수 있는지 알려 주십시오.",
        "   질문이 화면 데이터와 다른 날·기간(어제, 지난주, 지난달 등)을 가리키면 반드시 날짜를 맞춰 보십시오.",
        "   [질문이 가리킨 날짜의 기록] 블록이 있으면 그 블록의 값으로 답하고 어느 날짜인지 밝히십시오.",
        "   그 블록이 없으면 규칙 8로 요청하고, \"(기록 없음)\"이면 화면 데이터를 그 날의 값인 것처럼 말하지 말고",
        "   그 날은 기록이 없다고 말하십시오. 어느 경우든 화면 데이터(date 줄)의 날짜와 질문의 날짜가 다르면 화면 값을 그 날 값으로 쓰면 안 됩니다.",
        "   그 날 기록을 자세히 볼 화면으로 안내할 때는 규칙 7의 표식에 날짜를 붙이십시오 (예: [GOTO:daily_record:2026-11-18]).",
        "2. 화면 데이터의 숫자(순칼로리·권장량·체중·체성분)는 앱이 이미 계산한 값입니다. 다시 계산하거나 바꾸지 말고 그대로 인용하십시오.",
        "3. 결론을 먼저 두 문장 안에 말하고, 근거가 되는 숫자를 한두 개 붙이십시오. 전체는 3~6문장으로 짧게.",
        "4. 제안이 필요하면 '- '로 시작하는 짧은 불릿 2~3개를 쓰십시오. 불릿 말고는 목록·표·제목을 쓰지 마십시오.",
        "   마크다운을 쓰지 마십시오 (**굵게**, *기울임*, # 제목, `코드`, 번호 목록 금지). 앱은 글자를 그대로 보여주므로 기호가 그대로 찍힙니다.",
        "5. 의학적 진단·약물 처방·극단적 단식 권유는 하지 마십시오. 필요하면 전문가 상담을 권하십시오.",
        `6. 모든 문장은 반드시 ${outputLanguage}로 쓰십시오. kcal·kg 같은 단위는 그대로 두십시오.`,
        "7. 답에 필요한 값이 다른 화면에 있거나 사용자가 그 화면을 직접 보는 편이 나으면, 답변 맨 마지막 줄에",
        "   [GOTO:화면코드] 를 한 번만 붙이십시오. 앱이 그 표식을 버튼으로 바꿔 줍니다 (표식은 사용자에게 보이지 않으니",
        "   본문에서는 화면 이름으로 안내하십시오). 지금 보고 있는 화면이나 필요 없는 경우에는 붙이지 마십시오.",
        "   화면코드와 각 화면에 있는 값:",
        ...SCREEN_GUIDE.map((line) => `   - ${line}`)
    );
    if (allowNeed) {
        parts.push(
            "8. 위에 없는 기록이 답에 꼭 필요하면, 답을 쓰지 말고 아래 중 하나를 **한 줄만** 내십시오. 서버가 그 데이터를 읽어",
            "   다시 묻습니다. 화면 데이터로 답할 수 있으면 쓰지 마십시오. 한 번에 하나만.",
            `   [NEED:day:yyyy-MM-dd] 특정 날의 정산 / [NEED:month:yyyy-MM] 그 달의 일별 정산 / [NEED:months:yyyy] 그 해의 월별 집계`,
            `   [NEED:weight:N] 최근 N일 체중(${WEIGHT_DAYS_MIN}~${WEIGHT_DAYS_MAX}) / [NEED:inbody] 인바디 측정 이력`
        );
    } else {
        parts.push(
            "8. 추가 데이터 요청([NEED])은 더 할 수 없습니다. 위에 있는 값만으로 답하고, 없으면 없다고 말하십시오."
        );
    }
    return parts.join("\n");
}

/**
 * 글 답변을 받는다 (JSON 아님).
 *
 * gemini.js의 generateAndParse는 JSON 파싱까지 하므로 쓸 수 없다. 재시도 규칙은 그쪽과 같게 둔다 —
 * 다시 물어도 소용없는 에러(400·401·403·404)는 즉시 던지고, 과부하·한도·무응답은 예비 모델로 갈아탄다.
 *
 * @param {object} options
 *   - attemptTimeoutMs: 한 번의 호출 상한
 *   - totalBudgetMs: 대기까지 합친 전체 예산. 함수 타임아웃(60초)보다 짧아야 한다
 *   - fallbackModels: 앞에서부터 갈아탈 예비 모델
 */
async function generateText({ model, fallbackModels = [], prompt, attemptTimeoutMs = 20000, totalBudgetMs = 40000, maxRetries = 3, fallbackAfter = 2 }) {
    const chain = [model, ...fallbackModels.filter(Boolean)];
    const deadline = Date.now() + totalBudgetMs;
    let stage = 0;
    let overloads = 0;
    let lastError = null;

    for (let i = 0; i < maxRetries; i++) {
        const left = deadline - Date.now();
        if (left <= 0) {
            console.warn(`[askAiCoach] 예산 ${totalBudgetMs}ms 소진 - ${i}번 시도하고 포기`);
            break;
        }
        try {
            const result = await chain[stage].generateContent(
                { contents: [{ role: "user", parts: [{ text: prompt }] }] },
                { timeout: Math.min(attemptTimeoutMs, left) }
            );
            const text = (result.response.text() || "").trim();
            if (text) return text;
            lastError = new Error("[askAiCoach] 빈 응답");
        } catch (error) {
            lastError = error;
            const reason = retryReason(error);
            // 키·모델명·요청이 틀린 것은 몇 번을 물어도 같다
            if (!reason) throw error;
            if (/과부하|한도|응답 없음/.test(reason)) overloads++;
            if (stage < chain.length - 1 && overloads >= fallbackAfter) {
                stage++;
                overloads = 0;
                console.warn(`[askAiCoach] ${reason} ${fallbackAfter}번 연속 - 예비 모델 ${stage}단계로 갈아탐`);
                continue;
            }
        }
        if (i === maxRetries - 1) break;
        await new Promise((resolve) => setTimeout(resolve, 500 * (i + 1)));
    }

    throw lastError || new Error("[askAiCoach] 답을 만들지 못함");
}

const askAiCoach = onRequest({
    region: "asia-northeast3",
    cors: true,
    secrets: ["GEMINI_API_KEY"],
    enforceAppCheck: true,
    timeoutSeconds: 60,
}, async (req, res) => {
    if (rejectNonAppRequest(req, res)) return;

    const body = req.body || {};
    const lang = resolveLang(body.language);
    const L = LABELS[lang];

    // 질문이 비면 모델을 부르지 않는다 (비용·지연 모두 낭비다)
    const question = normalizeQuestion(body.question);
    if (!question) {
        return res.status(400).json({ answer: "", error: L.askEmpty });
    }

    try {
        // 앱이 만든 데이터는 인증 전에 정리해 둔다 (검증 실패해도 모델은 부르지 않는다)
        const screen = normalizeScreen(body);
        const contextData = normalizeContextData(body.contextData);
        const history = normalizeHistory(body.history);
        const aiContext = normalizeAiContext(body.aiContext);

        const user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ answer: "", error: L.authRequired });
        }

        // 무료 3회 제한은 로그인한 사용자에게만 의미가 있다. 토큰 없는 요청은 위에서 이미 끊겼다
        const quota = await withTimeout(reserveAiAsk(user), QUOTA_TIMEOUT_MS);
        if (!quota.allowed) {
            console.warn(`[askAiCoach] uid=${user.uid} 오늘 질문 상한 ${quota.limit}회 도달`);
            return res.status(429).json({
                answer: "",
                error: L.askLimitReached,
                remainingFreeCount: quota.remainingFreeCount,
                limit: quota.limit
            });
        }

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const generationConfig = {
            temperature: 0.5,
            maxOutputTokens: 900,
            thinkingConfig: { thinkingLevel: "minimal" }
        };
        const model = genAI.getGenerativeModel({ model: "gemini-3.5-flash-lite", generationConfig });
        const fallbackModels = [
            genAI.getGenerativeModel({ model: "gemini-3.5-flash", generationConfig }),
            paidModel("gemini-3.5-flash-lite", generationConfig)
        ];

        // 질문이 어제·특정 날짜를 가리키면 그 날 정산을 읽어 넣는다. 화면 데이터만으로는 모델이 오늘 값을
        // 그 날 것처럼 답하기 때문이다. 읽기 실패·지연은 본 답변을 막지 않는다
        const timeZone = normalizeTimeZone(body.timeZone);
        const current = nowText(new Date(), timeZone);
        const askedDates = extractAskedDates(question, current.slice(0, 10));
        let askedBlock = "";
        if (askedDates.length) {
            try {
                askedBlock = await withTimeout(fetchAskedRecords(user.uid, askedDates), QUOTA_TIMEOUT_MS);
            } catch (error) {
                console.warn(`[askAiCoach] 날짜 기록 읽기 실패 (${askedDates.join(",")}):`, error.message);
            }
        }

        const prompt = buildPrompt({
            screen,
            contextData,
            aiContextBlock: aiContextPromptBlock(aiContext),
            history,
            question,
            outputLanguage: L.outputLanguage,
            now: current,
            timeZone,
            askedBlock
        });

        try {
            const today = current.slice(0, 10);
            let generated = await generateText({ model, fallbackModels, prompt, totalBudgetMs: 30000 });

            // 모델이 답 대신 데이터를 요청했으면 읽어 붙여 한 번 더 묻는다. 읽기 실패·2차 실패는 "못 찾았다"로 끝낸다
            const need = parseNeed(generated, today);
            if (need) {
                let neededBlock = "";
                try {
                    neededBlock = await withTimeout(fetchNeeded(user.uid, need, today), NEED_FETCH_TIMEOUT_MS);
                } catch (error) {
                    console.warn(`[askAiCoach] 요청 데이터 읽기 실패 (${need.kind}:${need.arg}):`, error.message);
                }
                if (neededBlock) {
                    const secondPrompt = buildPrompt({
                        screen, contextData, aiContextBlock: aiContextPromptBlock(aiContext), history, question,
                        outputLanguage: L.outputLanguage, now: current, timeZone, askedBlock, neededBlock, allowNeed: false
                    });
                    try {
                        generated = await generateText({ model, fallbackModels, prompt: secondPrompt, totalBudgetMs: 20000, maxRetries: 2 });
                    } catch (error) {
                        console.warn("[askAiCoach] 2차 질의 실패:", error.message);
                        generated = "";
                    }
                } else {
                    generated = "";
                }
                console.log(`[askAiCoach] uid=${user.uid} 추가 데이터 ${need.kind}:${need.arg || "-"} ${neededBlock ? neededBlock.length + "자" : "실패"} → ${generated ? "답변" : "못 찾음"}`);
            }
            if (!generated || parseNeed(generated, today)) {
                generated = L.askDataMissing;
            }

            const { answer, navigateTo, navigateArg } = suppressDailyNav(
                splitNavigation(generated, screen.type), askedDates
            );
            console.log(`[askAiCoach] uid=${user.uid} 화면=${screen.type} 질문=${question.length}자 답변=${answer.length}자 이동=${navigateTo || "-"} 날짜=${askedDates.join(",") || "-"} 남은=${quota.remainingFreeCount}`);
            return res.status(200).json({
                answer,
                navigateTo,
                navigateArg,
                remainingFreeCount: quota.remainingFreeCount,
                limit: quota.limit
            });
        } catch (error) {
            // 답을 못 만들었으면 센 횟수를 돌려준다. 실패한 질문까지 세면 사용자만 손해다.
            await refundAiAsk(user.uid);
            throw error;
        }
    } catch (error) {
        console.error("askAiCoach Error:", error.message);
        res.status(500).json({ answer: "", error: L.error });
    }
});

module.exports = {
    askAiCoach,
    splitNavigation,
    stripMarkdown,
    nowText,
    normalizeTimeZone,
    shiftDate,
    extractAskedDates,
    suppressDailyNav,
    summarizeRecord,
    parseNeed,
    formatMonth,
    formatMonths,
    formatWeight,
    formatInbody,
    WEIGHT_POINTS_MAX,
    INBODY_MAX,
    MAX_QUESTION_CHARS,
    MAX_HISTORY_TURNS,
    MAX_HISTORY_CHARS,
    MAX_CONTEXT_CHARS,
    MAX_TITLE_CHARS,
    SCREEN_TYPES,
    normalizeQuestion,
    normalizeScreen,
    normalizeContextData,
    normalizeHistory,
    buildPrompt,
    generateText
};
