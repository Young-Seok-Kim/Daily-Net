/**
 * 상식 밖 운동 칼로리 찾기 검증.
 *
 * kcalCheck와 같은 계약이다 — **조용해야 한다.** 로그만 남기는 그물이라 놓치는 건 괜찮지만,
 * 멀쩡한 값이 자꾸 걸리면 로그가 시끄러워지고 시끄러운 로그는 아무도 안 본다.
 * 그래서 "안 걸려야 하는 것" 쪽 검사가 더 많다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { implausibleExercises, minutesOf, stepsOf, impliedMets } = require("../exerciseCheck");

/** 실제로 문제가 났던 사용자 체중 */
const WEIGHT = 89.5;

const ex = (name, kcal) => ({ exercises: [{ name, kcal }] });

test("minutesOf - 이름에 적힌 시간을 분으로 읽는다", async (t) => {
    await t.test("시간과 분을 모두 읽는다", () => {
        assert.equal(minutesOf("MMA 스파링 1시간"), 60);
        assert.equal(minutesOf("러닝 30분"), 30);
        assert.equal(minutesOf("자전거 1.5시간"), 90);
        assert.equal(minutesOf("등산 1시간 30분"), 90);
    });

    await t.test("영어 표기도 읽는다", () => {
        assert.equal(minutesOf("MMA sparring 1 hour"), 60);
        assert.equal(minutesOf("Running 45 min"), 45);
    });

    await t.test("시간이 없으면 0", () => {
        assert.equal(minutesOf("MMA 스파링"), 0);
        assert.equal(minutesOf("걸음 6199보"), 0);
        assert.equal(minutesOf(null), 0);
    });

    await t.test("거리를 시간으로 오인하지 않는다", () => {
        // "300m"의 m을 분으로 읽으면 멀쩡한 항목이 통째로 오탐이 된다
        assert.equal(minutesOf("수영 300m"), 0);
        assert.equal(minutesOf("러닝 5km"), 0);
        // 단어 안의 h도 시간이 아니다
        assert.equal(minutesOf("hiking"), 0);
    });
});

test("stepsOf - 걸음 항목의 걸음 수를 읽는다", async (t) => {
    await t.test("걸음 수를 뽑는다", () => {
        assert.equal(stepsOf("걸음 6199보"), 6199);
        assert.equal(stepsOf("Walking 6,199 steps"), 6199);
    });

    await t.test("걸음 항목이 아니면 0", () => {
        assert.equal(stepsOf("MMA 스파링 1시간"), 0);
        assert.equal(stepsOf(null), 0);
    });
});

test("impliedMets - 나온 칼로리를 METs로 되짚는다", async (t) => {
    await t.test("실제로 나갔던 MMA 건을 역산한다", () => {
        // 89.5kg이 1시간에 300kcal이면 3.2 METs. 천천히 걷기보다 가볍다
        assert.equal(impliedMets(300, WEIGHT, 60), 3.2);
    });

    await t.test("제대로 된 값은 제대로 나온다", () => {
        // 10.3 METs × 3.5 × 89.5 ÷ 200 × 60 ≈ 968kcal
        assert.equal(impliedMets(968, WEIGHT, 60), 10.3);
    });
});

test("implausibleExercises - 상식 밖인 것만 집는다", async (t) => {
    await t.test("실제로 나갔던 MMA 건을 잡는다", () => {
        const found = implausibleExercises(ex("MMA 스파링 1시간", 300), WEIGHT);
        assert.equal(found.length, 1);
        assert.equal(found[0].mets, 3.2);
        assert.match(found[0].reason, /격투기/);
    });

    await t.test("제대로 나온 격투기는 안 걸린다", () => {
        // 휴식 섞인 1시간 수업이면 6~8 METs가 현실적이다. 이게 걸리면 그물이 너무 촘촘한 것
        assert.deepEqual(implausibleExercises(ex("MMA 스파링 60분", 564), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("MMA 스파링 60분", 750), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("복싱 스파링 1시간", 733), WEIGHT), []);
    });

    await t.test("다른 운동도 제대로 나오면 안 걸린다", () => {
        // 같은 날 같이 나올 법한 값들이다
        assert.deepEqual(implausibleExercises(ex("웨이트 트레이닝 1시간", 470), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("조깅 30분", 330), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("요가 1시간", 235), WEIGHT), []);
    });

    await t.test("종류를 몰라도 천천히 걷기보다 낮으면 잡는다", () => {
        // 밴드에 없는 운동이라도 3 METs 밑은 운동이라 부를 수 없다
        const found = implausibleExercises(ex("훌라후프 1시간", 250), WEIGHT);
        assert.equal(found.length, 1);
        assert.match(found[0].reason, /운동은 보통/);
    });

    await t.test("종류를 모르면 어지간해선 안 걸린다", () => {
        // 모르는 운동에 좁은 기준을 대면 오탐만 는다
        assert.deepEqual(implausibleExercises(ex("훌라후프 1시간", 400), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("서핑 1시간", 500), WEIGHT), []);
    });

    await t.test("강도를 가리키는 말이 붙으면 낯선 종목도 하한을 건다", () => {
        // 종목을 다 열거할 수는 없다. 대신 "크로스핏·인터벌·타바타"처럼 강도를 뜻하는
        // 말이 붙으면 숨이 차게 했다는 뜻이라 낮은 값을 잡을 수 있다.
        // MMA와 똑같이 3.2 METs로 나온 건인데, 예전 기준(2.0)이었으면 그냥 통과했다
        const found = implausibleExercises(ex("크로스핏 1시간", 300), WEIGHT);
        assert.equal(found.length, 1);
        assert.match(found[0].reason, /고강도/);

        assert.equal(implausibleExercises(ex("타바타 30분", 100), WEIGHT).length, 1);
        // 제대로 나온 크로스핏은 안 걸린다
        assert.deepEqual(implausibleExercises(ex("크로스핏 1시간", 800), WEIGHT), []);
    });

    await t.test("진짜로 가벼운 운동은 하한을 올려도 안 걸린다", () => {
        // UNKNOWN 하한을 3.0으로 올린 탓에 요가·당구가 걸리면 안 된다.
        // 89.5kg 기준 1시간에 1 METs가 94kcal이므로 요가(2.5)는 235가 제값이다
        assert.deepEqual(implausibleExercises(ex("요가 1시간", 235), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("스트레칭 30분", 108), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("당구 1시간", 235), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("필라테스 1시간", 282), WEIGHT), []);
    });

    await t.test("새로 넣은 밴드들이 제 값에 안 걸린다", () => {
        assert.deepEqual(implausibleExercises(ex("줄넘기 30분", 500), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("계단 오르기 30분", 350), WEIGHT), []);
        assert.deepEqual(implausibleExercises(ex("로잉머신 30분", 400), WEIGHT), []);
    });

    await t.test("시간이 안 적힌 항목은 건너뛴다", () => {
        // METs를 낼 수가 없다. 짐작해서 재면 그게 오탐이 된다
        assert.deepEqual(implausibleExercises(ex("MMA 스파링", 300), WEIGHT), []);
    });

    await t.test("과대추정도 잡는다", () => {
        // 한쪽만 보면 반대 방향으로 틀어질 때 놓친다
        const found = implausibleExercises(ex("걷기 1시간", 900), WEIGHT);
        assert.equal(found.length, 1);
        assert.match(found[0].reason, /걷기/);
    });
});

test("implausibleExercises - 걸음 항목은 걸음당 소모로 잰다", async (t) => {
    await t.test("실제로 나갔던 값은 안 걸린다", () => {
        // 6199보에 150kcal은 걸음당 0.024. 기초대사를 뺀 순감으로 보면 정상 범위다
        assert.deepEqual(implausibleExercises(ex("걸음 6199보", 150), WEIGHT), []);
        // 통상값(걸음당 0.036)도 당연히 통과해야 한다
        assert.deepEqual(implausibleExercises(ex("걸음 6199보", 222), WEIGHT), []);
    });

    await t.test("걸음을 거의 안 센 것은 잡는다", () => {
        const found = implausibleExercises(ex("걸음 6199보", 30), WEIGHT);
        assert.equal(found.length, 1);
        assert.equal(found[0].steps, 6199);
        assert.match(found[0].reason, /걸음당/);
    });

    await t.test("걸음을 과하게 센 것도 잡는다", () => {
        assert.equal(implausibleExercises(ex("걸음 6199보", 900), WEIGHT).length, 1);
    });
});

test("implausibleExercises - 잴 수 없으면 조용히 넘어간다", async (t) => {
    await t.test("체중이 없으면 아무것도 재지 않는다", () => {
        // 체중 없이 METs를 내면 전부 오탐이 된다
        assert.deepEqual(implausibleExercises(ex("MMA 스파링 1시간", 300), 0), []);
        assert.deepEqual(implausibleExercises(ex("MMA 스파링 1시간", 300), undefined), []);
    });

    await t.test("운동이 없거나 비어도 죽지 않는다", () => {
        assert.deepEqual(implausibleExercises({}, WEIGHT), []);
        assert.deepEqual(implausibleExercises({ exercises: null }, WEIGHT), []);
        assert.deepEqual(implausibleExercises({ exercises: [] }, WEIGHT), []);
        assert.deepEqual(implausibleExercises({ exercises: [{}] }, WEIGHT), []);
    });

    await t.test("0kcal은 재지 않는다", () => {
        // 운동을 안 한 날의 정상값이다. 이걸 잡으면 매번 찍힌다
        assert.deepEqual(implausibleExercises(ex("휴식 1시간", 0), WEIGHT), []);
    });

    await t.test("음수로 와도 크기로 잰다", () => {
        // 리포트가 -300kcal로 보여주다 보니 모델이 부호를 붙여 보내는 일이 있다
        assert.equal(implausibleExercises(ex("MMA 스파링 1시간", -300), WEIGHT).length, 1);
    });
});
