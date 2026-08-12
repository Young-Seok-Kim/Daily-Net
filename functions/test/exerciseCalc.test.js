/**
 * 운동 소모 칼로리 계산 검증.
 *
 * exerciseCheck(로그만 남기는 그물)와 달리 여기는 **실제로 사용자에게 나가는 숫자**다.
 * 틀리면 그날 결산이 통째로 틀리므로, 놓치는 것보다 정확한 쪽이 중요하다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { buildExercises, metsToKcal, stepKcal } = require("../exerciseCalc");

/** 실제로 문제가 났던 사용자 */
const WEIGHT = 89.5;
const STEPS = 6199;

const stepName = (steps) => `걸음 ${steps}보`;

test("metsToKcal - METs 공식을 그대로 쓴다", async (t) => {
    await t.test("모델이 못 하던 곱셈을 한다", () => {
        // 모델은 이 값을 554(70kg 기준 7.5 METs)로 냈다. 체중을 안 쓴 것이다
        assert.equal(metsToKcal(7.5, WEIGHT, 60), 705);
        assert.equal(metsToKcal(10.3, WEIGHT, 60), 968);
        assert.equal(metsToKcal(7.8, WEIGHT, 60), 733);
    });

    await t.test("시간에 비례한다", () => {
        assert.equal(metsToKcal(7.5, WEIGHT, 30), 352);
        assert.equal(metsToKcal(7.5, WEIGHT, 120), 1410);
    });

    await t.test("값이 없으면 0", () => {
        assert.equal(metsToKcal(0, WEIGHT, 60), 0);
        assert.equal(metsToKcal(7.5, 0, 60), 0);
        assert.equal(metsToKcal(7.5, WEIGHT, 0), 0);
        assert.equal(metsToKcal(null, undefined, "x"), 0);
    });
});

test("stepKcal - 걸음당 소모도 체중에 비례한다", async (t) => {
    await t.test("실제 값과 맞는다", () => {
        // 모델이 내던 값이 221이었다. 이건 맞게 나오던 쪽이라 큰 차이가 없어야 한다
        assert.equal(stepKcal(WEIGHT, STEPS), 222);
    });

    await t.test("체중이 다르면 값도 다르다", () => {
        assert.equal(stepKcal(70, STEPS), 174);
    });

    await t.test("걸음이 없으면 0", () => {
        assert.equal(stepKcal(WEIGHT, 0), 0);
        assert.equal(stepKcal(WEIGHT, null), 0);
    });
});

test("buildExercises - 모델 판단 + 서버 산수", async (t) => {
    await t.test("실제로 틀렸던 그날을 제대로 계산한다", () => {
        const data = {
            exercises: [{ name: "스파링 60분", minutes: 60, mets: 7.5 }],
            stepsInExercise: 0
        };

        const built = buildExercises(data, WEIGHT, STEPS, stepName);

        assert.deepEqual(built.items.map((i) => [i.name, i.kcal]), [
            ["스파링 60분", 705],
            ["걸음 6199보", 222]
        ]);
        // 모델이 곱했을 때는 450이었다
        assert.equal(built.total, 927);
        assert.equal(built.fellBack, false);
    });

    await t.test("걸음 항목은 서버가 붙인다", () => {
        const built = buildExercises({ exercises: [] }, WEIGHT, STEPS, stepName);
        assert.equal(built.items.length, 1);
        assert.equal(built.items[0].name, "걸음 6199보");
        assert.equal(built.total, 222);
    });

    await t.test("모델이 걸음 항목을 또 넣으면 버린다", () => {
        // 안 버리면 걸음이 두 번 세어진다
        const data = {
            exercises: [
                { name: "스파링 60분", minutes: 60, mets: 7.5 },
                { name: "걸음 6199보", minutes: 0, mets: 0, kcal: 220 }
            ]
        };

        const built = buildExercises(data, WEIGHT, STEPS, stepName);

        assert.equal(built.items.length, 2);
        assert.equal(built.total, 927);
        assert.deepEqual(built.droppedStepItems, ["걸음 6199보"]);
    });

    await t.test("운동 중에 걸은 걸음만 뺀다", () => {
        // 워치를 차고 걷는 운동을 하면 그 걸음이 걸음 수에도 잡힌다
        const data = {
            exercises: [{ name: "산책 30분", minutes: 30, mets: 3.5 }],
            stepsInExercise: 3000
        };

        const built = buildExercises(data, WEIGHT, STEPS, stepName);

        assert.equal(built.overlap, 3000);
        // 나머지 3199보는 그대로 센다. 통째로 버리지 않는다
        assert.equal(built.items[1].name, "걸음 3199보");
        assert.equal(built.items[1].kcal, stepKcal(WEIGHT, 3199));
    });

    await t.test("겹침이 걸음 수보다 커도 음수가 되지 않는다", () => {
        const data = { exercises: [], stepsInExercise: 999999 };
        const built = buildExercises(data, WEIGHT, STEPS, stepName);
        assert.equal(built.overlap, STEPS);
        assert.deepEqual(built.items, []);
        assert.equal(built.total, 0);
    });

    await t.test("METs 없이 kcal만 오면 그 값을 쓴다 (구버전 경로)", () => {
        // 운동을 통째로 0으로 만드는 것보다는 모델이 곱한 값이라도 쓰는 게 낫다
        const data = { exercises: [{ name: "운동 1시간", kcal: 300 }] };
        const built = buildExercises(data, WEIGHT, 0, stepName);

        assert.equal(built.items[0].kcal, 300);
        assert.equal(built.fellBack, true);
    });

    await t.test("이름이 없는 항목은 버린다", () => {
        const data = { exercises: [{ minutes: 60, mets: 7.5 }, { name: "  " }] };
        assert.deepEqual(buildExercises(data, WEIGHT, 0, stepName).items, []);
    });

    await t.test("운동도 걸음도 없으면 빈 목록", () => {
        const built = buildExercises({}, WEIGHT, 0, stepName);
        assert.deepEqual(built.items, []);
        assert.equal(built.total, 0);
    });

    await t.test("응답이 깨져도 죽지 않는다", () => {
        assert.equal(buildExercises(null, WEIGHT, 0, stepName).total, 0);
        assert.equal(buildExercises({ exercises: "이상함" }, WEIGHT, 0, stepName).total, 0);
        assert.equal(buildExercises({ exercises: [null] }, WEIGHT, 0, stepName).total, 0);
    });
});
