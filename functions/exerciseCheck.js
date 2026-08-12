/**
 * 운동 소모 칼로리가 상식 밖인 항목을 찾아낸다. **로그로만 남기고 숫자는 손대지 않는다.**
 *
 * kcalCheck와 같은 방침이다. 잘못 짚어도 로그 한 줄이 늘 뿐이고, 값을 고치면
 * 멀쩡한 계산까지 망친다. 여기는 "봐야 할 것"만 알린다.
 *
 * 재는 방법은 **METs 역산**이다. 소모 칼로리는 [METs × 3.5 × 체중 ÷ 200 × 분]이므로,
 * 나온 칼로리와 이름에 적힌 시간으로 되짚으면 모델이 그 운동을 몇 METs로 봤는지 나온다.
 * 그 값이 운동 종류에 비해 터무니없으면 알린다.
 *
 * 실제로 89.5kg 사용자의 "MMA 스파링 1시간"이 300kcal로 나왔다. 역산하면 3.2 METs로
 * 천천히 걷기보다 가벼운 값이다. 그때는 이 검사도 로그도 없어서, 사용자가 직접
 * 이상하다고 말할 때까지 아무도 몰랐다.
 *
 * 이름에 시간이 적혀 있을 때만 잰다. `MMA 스파링`처럼 시간이 없으면 METs를 낼 수 없으므로
 * 그냥 넘어간다. (프롬프트가 시간을 함께 적도록 지시하고 있다)
 */

/** 이름에 적힌 시간. `1시간`, `1.5시간`, `1 hour` */
const HOURS = /(\d+(?:\.\d+)?)\s*(?:시간|hours?|hrs?|h)(?![a-z])/i;

/** 이름에 적힌 분. `30분`, `45 min`. 미터(`300m`)와 헷갈리지 않게 맨 `m`은 받지 않는다 */
const MINUTES = /(\d+(?:\.\d+)?)\s*(?:분|minutes?|mins?)(?![a-z])/i;

/** 걸음 항목의 걸음 수. `6199보`, `6,199 steps` */
const STEPS = /(\d[\d,]*)\s*(?:보|steps?)(?![a-z])/i;

/**
 * 운동 종류를 못 알아봤을 때 쓰는 최소·최대 METs.
 *
 * 넓게 잡는다. 종류를 모르는 상태에서 좁히면 멀쩡한 값이 걸린다.
 * 3 METs는 천천히 걷기라, 운동이라고 적어놓고 그보다 낮으면 무엇이든 이상하다.
 * (요가·당구처럼 진짜로 낮은 것들은 아래 "저강도" 밴드가 따로 받는다)
 *
 * ⚠️ 이 하한은 밴드에 걸리는 운동보다 훨씬 무르다. 문제였던 MMA가 3.2 METs였는데
 * 그건 격투기 밴드(6.0~)가 잡은 것이고, 여기로 떨어지는 낯선 운동은 3.2여도 통과한다.
 * 그래서 밴드에 안 걸린 항목도 **역산한 METs를 로그에 함께 남긴다.** 그물이 못 잡아도
 * 눈으로는 보이게 하는 것이 이 파일의 최종 방어선이다.
 */
const UNKNOWN_METS_MIN = 3.0;
const UNKNOWN_METS_MAX = 20.0;

/**
 * 걸음 하나에 체중 1kg당 소모되는 칼로리의 상·하한.
 *
 * 통상값은 0.0004 근처다(89.5kg이면 걸음당 약 0.036kcal). 아래위로 넉넉히 벌려둔 것은
 * 기초대사를 빼고 세는지 아닌지에 따라 정상 범위가 1.5배쯤 벌어지기 때문이다.
 */
const STEP_KCAL_MIN_PER_KG = 0.00025;
const STEP_KCAL_MAX_PER_KG = 0.0007;

/**
 * 운동별 METs 범위. 어긋나면 바로 티가 나는 것들만 넣는다.
 *
 * kcalCheck의 BANDS와 같은 이유로 넓게 잡지 않는다. 종류마다 강도 폭이 크므로
 * 하한은 "그 운동이라면 아무리 살살 해도 이보다는 높다" 선에 둔다.
 */
const BANDS = [
    {
        label: "격투기",
        keys: ["mma", "격투", "복싱", "킥복싱", "무에타이", "주짓수", "유도",
               "레슬링", "스파링", "태권도", "가라테", "합기도", "검도"],
        // 살살 하는 스파링도 6은 넘는다. 복싱 스파링이 7.8, 실전 격투기가 10.3이다
        min: 6.0,
        max: 14.0
    },
    {
        label: "달리기",
        keys: ["달리기", "러닝", "조깅", "마라톤", "런닝"],
        min: 6.0,
        max: 18.0
    },
    {
        label: "수영",
        keys: ["수영", "접영", "자유형", "평영", "배영"],
        min: 5.0,
        max: 14.0
    },
    {
        label: "자전거",
        keys: ["자전거", "사이클", "싸이클", "스피닝"],
        min: 3.5,
        max: 15.0
    },
    {
        label: "웨이트",
        keys: ["웨이트", "헬스", "근력", "무산소", "리프팅", "스쿼트", "데드리프트", "벤치프레스"],
        min: 3.0,
        max: 9.0
    },
    {
        label: "구기",
        keys: ["축구", "농구", "테니스", "배드민턴", "탁구", "스쿼시", "배구", "풋살"],
        min: 4.0,
        max: 13.0
    },
    {
        label: "등산",
        keys: ["등산", "하이킹", "클라이밍", "암벽"],
        min: 4.5,
        max: 12.0
    },
    {
        label: "걷기",
        keys: ["걷기", "산책", "도보", "워킹", "트레드밀", "런닝머신", "러닝머신"],
        min: 2.5,
        max: 7.0
    },
    {
        label: "줄넘기",
        keys: ["줄넘기", "로프"],
        min: 7.0,
        max: 15.0
    },
    {
        label: "유산소기구",
        keys: ["계단", "스텝퍼", "스테퍼", "로잉", "로워", "일립티컬", "사이클머신"],
        min: 3.5,
        max: 13.0
    },
    {
        // 종목이 아니라 **강도를 가리키는 말**이다. 무슨 운동이든 이 말이 붙으면
        // 숨이 차게 했다는 뜻이라, 낯선 종목이어도 하한을 걸 수 있다.
        label: "고강도",
        keys: ["크로스핏", "hiit", "타바타", "인터벌", "서킷", "버피", "전력"],
        min: 6.0,
        max: 16.0
    },
    {
        // 진짜로 낮은 운동들. 위의 UNKNOWN 하한(3.0)에 걸리지 않게 따로 받는다
        label: "저강도",
        keys: ["요가", "스트레칭", "필라테스", "당구", "볼링", "골프", "재활"],
        min: 1.8,
        max: 6.0
    }
];

/** 이름에서 운동 시간을 분으로 뽑는다. 못 뽑으면 0 */
function minutesOf(rawName) {
    const name = String(rawName || "");

    let total = 0;
    const h = name.match(HOURS);
    if (h) total += Number(h[1]) * 60;
    const m = name.match(MINUTES);
    if (m) total += Number(m[1]);

    return Number.isFinite(total) && total > 0 ? total : 0;
}

/** 이름에서 걸음 수를 뽑는다. 걸음 항목이 아니면 0 */
function stepsOf(rawName) {
    const m = String(rawName || "").match(STEPS);
    if (!m) return 0;

    const steps = Number(m[1].replace(/,/g, ""));
    return Number.isFinite(steps) && steps > 0 ? steps : 0;
}

/** 받침에 따라 은/는을 고른다. 로그를 사람이 읽으므로 "격투기은"처럼 나오면 안 된다 */
function topicParticle(word) {
    const last = String(word).trim().slice(-1);
    const code = last.charCodeAt(0);

    // 한글이 아니면 어느 쪽도 확실하지 않다. 덜 어색한 쪽으로 둔다
    if (!(code >= 0xAC00 && code <= 0xD7A3)) return "는";

    return (code - 0xAC00) % 28 === 0 ? "는" : "은";
}

/** 나온 칼로리를 METs로 되짚는다. `kcal = METs × 3.5 × 체중 ÷ 200 × 분` */
function impliedMets(kcal, weightKg, minutes) {
    const perMet = (3.5 * weightKg / 200) * minutes;
    if (perMet <= 0) return 0;
    return Number((kcal / perMet).toFixed(1));
}

/**
 * 상식 밖인 운동 항목 목록. 없으면 빈 배열.
 *
 * @param data 모델 응답 (data.exercises를 본다)
 * @param weight 사용자 체중(kg). 없으면 잴 수 없으므로 빈 배열
 * @returns [{ name, kcal, minutes, mets, reason }] 또는 걸음 항목은 { name, kcal, steps, perStep, reason }
 */
function implausibleExercises(data, weight) {
    const found = [];

    const kg = Number(weight) || 0;
    if (kg <= 0) return found;

    const items = Array.isArray(data?.exercises) ? data.exercises : [];

    for (const item of items) {
        const name = String(item?.name || "");
        const kcal = Math.abs(Number(item?.kcal) || 0);
        if (!name || kcal <= 0) continue;

        // 걸음 항목에는 시간이 없다. METs 대신 걸음당 소모로 잰다
        const steps = stepsOf(name);
        if (steps > 0) {
            const perStep = kcal / steps;
            const min = STEP_KCAL_MIN_PER_KG * kg;
            const max = STEP_KCAL_MAX_PER_KG * kg;

            if (perStep < min || perStep > max) {
                found.push({
                    name, kcal, steps,
                    perStep: Number(perStep.toFixed(4)),
                    reason: `${kg}kg이면 걸음당 보통 ${min.toFixed(4)}~${max.toFixed(4)}`
                });
            }
            continue;
        }

        // 서버가 계산한 항목은 분·METs를 필드로 들고 있다(exerciseCalc). 그러면 그대로 쓴다.
        // 없으면 이름에서 시간을 뽑아 역산한다. 시간도 없으면 잴 수 없으니 넘어간다 —
        // 짐작해서 재면 그게 오탐이 된다
        const minutes = Number(item?.minutes) || minutesOf(name);
        if (minutes <= 0) continue;

        const mets = Number(item?.mets) || impliedMets(kcal, kg, minutes);
        const lower = name.toLowerCase();
        const band = BANDS.find((b) => b.keys.some((k) => lower.includes(k)));

        const min = band ? band.min : UNKNOWN_METS_MIN;
        const max = band ? band.max : UNKNOWN_METS_MAX;

        if (mets < min || mets > max) {
            const label = band ? band.label : "운동";
            found.push({
                name, kcal, minutes, mets,
                reason: `${minutes}분에 ${mets} METs ` +
                        `(${label}${topicParticle(label)} 보통 ${min}~${max})`
            });
        }
    }

    return found;
}

module.exports = { implausibleExercises, minutesOf, stepsOf, impliedMets };
