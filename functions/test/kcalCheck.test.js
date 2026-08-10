/**
 * 상식 밖 칼로리 찾기 검증.
 *
 * 지켜야 할 계약은 **조용함**이다. 이건 로그만 남기는 그물이라 놓치는 건 괜찮지만,
 * 멀쩡한 값이 자꾸 걸리면 로그가 시끄러워지고 시끄러운 로그는 아무도 안 본다.
 * 그래서 "안 걸려야 하는 것" 쪽 검사가 더 많다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { implausibleItems, gramsOf, statedKcals } = require("../kcalCheck");

const snack = (name, kcal) => ({ meals: { snack: [{ name, kcal }] } });

test("gramsOf - 이름에 적힌 중량을 g으로 읽는다", async (t) => {
    await t.test("단위를 g으로 맞춘다", () => {
        assert.equal(gramsOf("LOTTE 씨리얼 초코 42g"), 42);
        assert.equal(gramsOf("삼겹살 1.5kg"), 1500);
        assert.equal(gramsOf("우유 200ml"), 200);
        assert.equal(gramsOf("콜라 1.5L"), 1500);
    });

    await t.test("합쳐진 항목은 개수만큼 곱한다", () => {
        // "코카콜라 355ml ×2"는 kcal도 두 개분이라 중량도 두 개분이어야 맞는다
        assert.equal(gramsOf("코카콜라 355ml ×2"), 710);
    });

    await t.test("중량이 없으면 0", () => {
        assert.equal(gramsOf("아메리카노 1잔"), 0);
        assert.equal(gramsOf("닭강정 0.5마리"), 0);
        assert.equal(gramsOf(null), 0);
    });
});

test("implausibleItems - 상식 밖인 것만 집는다", async (t) => {
    await t.test("실제로 나갔던 씨리얼 건을 잡는다", () => {
        // 42g에 40kcal = 100g당 95. 과자가 그럴 수는 없다
        const found = implausibleItems(snack("LOTTE 씨리얼 초코 42g", 40));
        assert.equal(found.length, 1);
        assert.equal(found[0].per100, 95);
        assert.match(found[0].reason, /과자류/);
    });

    await t.test("순수 지방보다 높으면 무엇이든 잡는다", () => {
        const found = implausibleItems(snack("김밥 100g", 1200));
        assert.equal(found.length, 1);
        assert.match(found[0].reason, /순수 지방/);
    });

    await t.test("덜 극단적으로 낮은 것도 잡는다", () => {
        // 씨리얼이 30g에 90kcal(100g당 300)로 나온 적이 있다. 40kcal만큼 터무니없진
        // 않지만 과자로는 여전히 낮다. 기준이 300이었으면 딱 걸쳐서 놓쳤을 값이다
        const found = implausibleItems(snack("LOTTE 씨리얼 초코 30g", 90));
        assert.equal(found.length, 1);
        assert.equal(found[0].per100, 300);
    });

    await t.test("제대로 나온 과자는 안 걸린다", () => {
        // 콘플레이크는 100g당 380 근처다. 기준을 더 올리면 이게 매번 걸린다
        assert.deepEqual(implausibleItems(snack("콘플레이크 시리얼 40g", 152)), []);
        // 같은 날 로그에서 맞게 나왔던 값들이다. 이게 걸리면 그물이 너무 촘촘한 것
        assert.deepEqual(implausibleItems(snack("과자 시리얼 50g", 200)), []);
        assert.deepEqual(implausibleItems(snack("롯데 치토스 매콤달콤한맛 82g", 440)), []);
    });

    await t.test("폭이 큰 음식은 아예 재지 않는다", () => {
        // 밥·국·과일은 100g당 값이 넓게 퍼져 있어 기준을 두면 멀쩡한 게 걸린다
        assert.deepEqual(implausibleItems(snack("잡곡밥 300g", 390)), []);
        assert.deepEqual(implausibleItems(snack("김치찌개 400g", 244)), []);
        assert.deepEqual(implausibleItems(snack("삼겹살 900g", 2340)), []);
    });

    await t.test("열량이 없는 음료는 안 걸린다", () => {
        // 0kcal은 제로 음료의 정상값이다. 이걸 잡으면 매번 찍힌다
        assert.deepEqual(implausibleItems(snack("코카콜라 제로 355ml", 0)), []);
        assert.deepEqual(implausibleItems(snack("아메리카노 350ml", 10)), []);
    });

    await t.test("중량이 안 적힌 항목은 건너뛴다", () => {
        // 100g당 값을 낼 수가 없다. 짐작해서 재면 그게 오탐이 된다
        assert.deepEqual(implausibleItems(snack("닭강정 0.5마리", 30)), []);
    });

    await t.test("끼니가 비어도 죽지 않는다", () => {
        assert.deepEqual(implausibleItems({}), []);
        assert.deepEqual(implausibleItems({ meals: { snack: null } }), []);
    });

    await t.test("어느 끼니에서 나왔는지 함께 준다", () => {
        const data = { meals: { lunch: [{ name: "쿠키 50g", kcal: 20 }] } };
        assert.equal(implausibleItems(data)[0].meal, "lunch");
    });
});

test("statedKcals - 입력에 적힌 열량을 뽑는다", async (t) => {
    await t.test("kcal이 붙은 숫자를 모은다", () => {
        const got = statedKcals(["씨리얼 초코 80g 400kcal, 우유 200ml", "삼각김밥 190kcal"]);
        assert.deepEqual([...got].sort((a, b) => a - b), [190, 400]);
    });

    await t.test("띄어쓰기와 대소문자를 가리지 않는다", () => {
        assert.ok(statedKcals(["과자 400 KCAL"]).has(400));
    });

    await t.test("kcal이 없는 숫자는 안 잡는다", () => {
        // 중량이나 개수를 열량으로 오인하면 멀쩡한 검사가 통째로 꺼진다
        const got = statedKcals(["닭강정 500g 2개"]);
        assert.equal(got.size, 0);
    });

    await t.test("빈 입력에도 죽지 않는다", () => {
        assert.equal(statedKcals([null, undefined, ""]).size, 0);
    });
});

test("implausibleItems - 성분표에서 읽은 값은 밴드로 재지 않는다", async (t) => {
    // 인쇄된 값이 내 어림 기준보다 정확하다. 밴드로 트집 잡으면 로그만 시끄러워진다
    const data = () => ({ meals: { snack: [{ name: "씨리얼 초코 80g", kcal: 80 }] } });

    await t.test("적힌 값이 아니면 걸린다", () => {
        // 100g당 100이라 과자 밴드(350~650)에 한참 못 미친다
        assert.equal(implausibleItems(data()).length, 1);
    });

    await t.test("입력에 적혀 있던 값이면 건너뛴다", () => {
        assert.deepEqual(implausibleItems(data(), statedKcals(["씨리얼 초코 80g 80kcal"])), []);
    });

    await t.test("적혀 있어도 순수 지방을 넘으면 걸린다", () => {
        // 이건 제품 특성이 아니라 잘못 읽었다는 뜻이라 그대로 알린다
        const wild = { meals: { snack: [{ name: "씨리얼 초코 80g", kcal: 4000 }] } };
        const found = implausibleItems(wild, statedKcals(["씨리얼 초코 80g 4000kcal"]));
        assert.equal(found.length, 1);
        assert.match(found[0].reason, /순수 지방/);
    });
});
