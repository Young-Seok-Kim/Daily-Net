/**
 * 사진에서 읽은 항목 정리 검증.
 *
 * 지켜야 할 계약은 하나다 — **영양성분표에서 읽은 열량만 텍스트에 나간다.**
 * 이 텍스트가 그대로 analyzeDiet으로 가고 거기서 "적힌 kcal은 그대로 쓰라"는 지시를 받으므로,
 * 눈대중 숫자가 새어 들어가면 추측이 정답 자리에 앉는다. 그래서 "안 나가야 하는" 검사가 핵심이다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

const { normalizeExtracted, toInputText, kcalFromLabel, amountMismatch, wholePackGuess, countOf } = require("../extractItems");

test("normalizeExtracted - 모델이 어떻게 보내든 모양을 고정한다", async (t) => {
    await t.test("빠진 필드를 채운다", () => {
        assert.deepEqual(normalizeExtracted([{ name: "사과" }]), [
            { name: "사과", amount: "", kcal: 0, kcalFromLabel: false, basis: "", mismatch: "", eaten: 0 }
        ]);
    });

    await t.test("이름이 없으면 버린다", () => {
        assert.deepEqual(normalizeExtracted([{ kcal: 100 }, { name: "", kcal: 50 }]), []);
    });

    await t.test("items가 배열이 아니어도 죽지 않는다", () => {
        assert.deepEqual(normalizeExtracted(undefined), []);
        assert.deepEqual(normalizeExtracted("음식"), []);
    });

    await t.test("성분표가 없으면 눈대중값을 그대로 쓴다", () => {
        const [item] = normalizeExtracted([{ name: "김치찌개", kcal: 300, label: null }]);
        assert.equal(item.kcal, 300);
        assert.equal(item.kcalFromLabel, false);
    });

    await t.test("성분표 값이 눈대중값을 이긴다", () => {
        // 모델이 눈대중으로 90을 냈어도 인쇄된 값에서 낸 400이 우선이다
        const [item] = normalizeExtracted([
            { name: "씨리얼", kcal: 90, label: { kcal: 500, basisAmount: 100 }, eatenAmount: 80 }
        ]);
        assert.equal(item.kcal, 400);
        assert.equal(item.kcalFromLabel, true);
    });
});

test("kcalFromLabel - 곱셈을 코드가 한다", async (t) => {
    await t.test("100g당 값을 먹은 양으로 환산한다", () => {
        // 사용자가 물어본 바로 그 경우: 100g당 100kcal짜리를 400g 먹으면 400
        assert.equal(kcalFromLabel({ kcal: 100, basisAmount: 100 }, 400), 400);
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 100 }, 80), 400);
    });

    await t.test("1회 제공량 기준도 그대로 환산된다", () => {
        // 30g당 150kcal인 80g 봉지를 다 먹으면 400. 1회 제공량을 봉지로 착각하면 150이 된다
        assert.equal(kcalFromLabel({ kcal: 150, basisAmount: 30 }, 80), 400);
    });

    await t.test("단위는 기준과 먹은 양이 같기만 하면 된다", () => {
        // 비율에서 약분되므로 ml을 g으로 바꿔 볼 필요가 없다
        assert.equal(kcalFromLabel({ kcal: 62, basisAmount: 100 }, 200), 124);
    });

    await t.test("0kcal 제품도 인정한다", () => {
        // 제로 음료의 정상값이다. 열량이 0이라고 못 읽은 것으로 보면 안 된다
        assert.equal(kcalFromLabel({ kcal: 0, basisAmount: 100 }, 355), 0);
    });

    await t.test("숫자가 하나라도 없으면 0을 준다", () => {
        assert.equal(kcalFromLabel(null, 80), 0);
        assert.equal(kcalFromLabel({ kcal: 500 }, 80), 0);
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 100 }, 0), 0);
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 0 }, 80), 0);
        assert.equal(kcalFromLabel({ kcal: "몰라", basisAmount: 100 }, 80), 0);
    });

    await t.test("말이 안 되는 양은 잘못 읽은 것으로 본다", () => {
        // 한 사람이 한 번에 5kg을 먹지 않는다. 자릿수를 잘못 읽은 것이다
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 100 }, 99999), 0);
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 100 }, -80), 0);
    });

    await t.test("한 항목이 하루치를 넘으면 버린다", () => {
        // 기준을 1g으로 잘못 읽으면 이런 값이 나온다
        assert.equal(kcalFromLabel({ kcal: 500, basisAmount: 1 }, 80), 0);
    });
});

test("amountMismatch - 이름과 열량이 어긋난 줄을 집는다", async (t) => {
    await t.test("실제로 나갔던 초코파이 건을 잡는다", () => {
        // 468g이라 적어놓고 39g치 열량이 붙었다. 그 자체로 앞뒤가 안 맞는다
        assert.match(amountMismatch("468g (39g x 12봉지)", 39), /468.*39/);
    });

    await t.test("맞게 잡았으면 아무 말 안 한다", () => {
        // 같은 날 바로 앞 실행에서 맞게 나왔던 값이다
        assert.equal(amountMismatch("336g (28g x 12봉)", 336), "");
    });

    await t.test("1개로 잡았어도 amount가 1개면 어긋난 게 아니다", () => {
        // N개입에서 1개만 먹은 것으로 잡는 것이 기본이다. amount와 서로 맞기만 하면 된다
        assert.equal(amountMismatch("1개 39g", 39), "");
        assert.equal(amountMismatch("3개 117g", 117), "");
    });

    await t.test("여러 개를 먹어 더 큰 것은 정상이다", () => {
        // "500mL 2캔"이면 먹은 양이 1000이다. 이걸 잡으면 로그만 시끄러워진다
        assert.equal(amountMismatch("500mL 2캔", 1000), "");
    });

    await t.test("반올림 오차는 넘어간다", () => {
        assert.equal(amountMismatch("100g", 98), "");
    });

    await t.test("잴 근거가 없으면 아무 말 안 한다", () => {
        // amount가 "1인분"이면 비교할 숫자가 없다. 짐작해서 재면 그게 오탐이 된다
        assert.equal(amountMismatch("1인분", 200), "");
        assert.equal(amountMismatch("", 200), "");
        assert.equal(amountMismatch("100g", 0), "");
    });

    await t.test("성분표를 안 읽은 항목에는 안 붙는다", () => {
        const [item] = normalizeExtracted([{ name: "닭강정", amount: "500g", kcal: 900, label: null }]);
        assert.equal(item.mismatch, "");
    });

    await t.test("성분표를 읽었으면 항목에 실려 나온다", () => {
        const [item] = normalizeExtracted([{
            name: "오리온 초코파이",
            amount: "468g (39g x 12봉지)",
            label: { kcal: 171, basisAmount: 39 },
            eatenAmount: 39
        }]);
        assert.equal(item.kcal, 171);
        assert.ok(item.mismatch, "어긋난 것을 알려야 한다");
    });
});

test("wholePackGuess - N개입을 통째로 센 것 같으면 알린다", async (t) => {
    await t.test("실제로 나갔던 오예스 건을 잡는다", () => {
        // 6개입 총 내용량 180g이 그대로 들어와 900kcal이 됐다. 180 = 30 × 6
        assert.match(wholePackGuess({ basisAmount: 30 }, 180, "180g"), /6배/);
    });

    await t.test("개수를 밝히고 셌으면 안 잡는다", () => {
        // "3개"라고 적었으면 규칙을 어긴 게 아니라 세어서 적은 것이다
        assert.equal(wholePackGuess({ basisAmount: 39 }, 117, "3개 117g"), "");
    });

    await t.test("한 개만 먹은 것은 당연히 안 잡는다", () => {
        assert.equal(wholePackGuess({ basisAmount: 39 }, 39, "1개 39g"), "");
    });

    await t.test("딱 떨어지지 않으면 안 잡는다", () => {
        // 100g당 기준으로 250g을 먹은 것은 개수와 무관하다
        assert.equal(wholePackGuess({ basisAmount: 100 }, 250, "250g"), "");
        assert.equal(wholePackGuess({ basisAmount: 100 }, 80, "80g"), "");
    });

    await t.test("잴 근거가 없으면 안 잡는다", () => {
        assert.equal(wholePackGuess(null, 180, "180g"), "");
        assert.equal(wholePackGuess({ basisAmount: 0 }, 180, "180g"), "");
    });
});

test("countOf - amount에서 개수와 단위를 뽑는다", async (t) => {
    await t.test("낱개 단위를 읽는다", () => {
        assert.deepEqual(countOf("1개 39g"), { count: 1, unit: "개" });
        assert.deepEqual(countOf("3개 117g"), { count: 3, unit: "개" });
        assert.deepEqual(countOf("500mL 2캔"), { count: 2, unit: "캔" });
        assert.deepEqual(countOf("12봉지"), { count: 12, unit: "봉지" });
    });

    await t.test("1회 제공량을 옮겨 적은 것도 개수로 센다", () => {
        // 실제로 `해태 오예스 1회 30g`으로 나왔는데 "회"가 빠져 있어 개당 표기가 안 붙었다
        assert.deepEqual(countOf("1회 30g"), { count: 1, unit: "회" });
        assert.deepEqual(countOf("3 servings"), { count: 3, unit: "servings" });
    });

    await t.test("무게·부피는 개수가 아니다", () => {
        assert.equal(countOf("82g"), null);
        assert.equal(countOf("200ml"), null);
        assert.equal(countOf("1인분"), null);
        assert.equal(countOf(""), null);
    });
});

test("toInputText - 입력창에 넣을 한 줄", async (t) => {
    await t.test("개수가 잡히면 열량도 중량도 개당으로 적는다", () => {
        const items = normalizeExtracted([{
            name: "오리온 초코파이", amount: "1개 39g",
            label: { kcal: 171, basisAmount: 39 }, eatenAmount: 39
        }]);
        assert.equal(toInputText(items), "오리온 초코파이 1개 (개당 39g 171kcal)");
    });

    await t.test("여러 개면 총량을 개수로 나눠 적는다", () => {
        // 3개분 117g·513kcal을 그대로 적으면 사용자가 개수를 고칠 때 둘 다 어긋난다
        const items = normalizeExtracted([{
            name: "초코파이", amount: "3개 117g",
            label: { kcal: 171, basisAmount: 39 }, eatenAmount: 117
        }]);
        assert.equal(items[0].kcal, 513);
        assert.equal(toInputText(items), "초코파이 3개 (개당 39g 171kcal)");
    });

    await t.test("무게가 앞에 와도 개당으로 맞춘다", () => {
        // "500mL 2캔"의 500은 이미 개당이다. 적힌 순서만 보면 총량인지 알 수 없어
        // eatenAmount(총량 1000)를 개수로 나눈다
        const items = normalizeExtracted([{
            name: "코카콜라", amount: "500mL 2캔",
            label: { kcal: 21, basisAmount: 100 }, eatenAmount: 1000
        }]);
        assert.equal(items[0].kcal, 210);
        assert.equal(toInputText(items), "코카콜라 2캔 (개당 500mL 105kcal)");
    });

    await t.test("중량 단위가 없으면 열량만 개당으로 적는다", () => {
        const items = normalizeExtracted([{
            name: "초코파이", amount: "2개",
            label: { kcal: 171, basisAmount: 39 }, eatenAmount: 78
        }]);
        assert.equal(toInputText(items), "초코파이 2개 (개당 171kcal)");
    });

    await t.test("1회 제공량 표기도 개당으로 적는다", () => {
        const items = normalizeExtracted([{
            name: "해태 오예스", amount: "1회 30g",
            label: { kcal: 150, basisAmount: 30 }, eatenAmount: 30
        }]);
        assert.equal(toInputText(items), "해태 오예스 1회 (개당 30g 150kcal)");
    });

    await t.test("언어별 낱말을 쓴다", () => {
        const items = normalizeExtracted([{
            name: "Choco Pie", amount: "1pc 39g",
            label: { kcal: 171, basisAmount: 39 }, eatenAmount: 39
        }]);
        assert.equal(toInputText(items, "each"), "Choco Pie 1pc (each 39g 171kcal)");
    });

    await t.test("개수로 세지 않는 것은 총 열량 그대로다", () => {
        // 82g 한 봉이나 200ml은 "개당"이 의미가 없다
        const items = normalizeExtracted([
            { name: "LOTTE 씨리얼 초코", amount: "80g", label: { kcal: 500, basisAmount: 100 }, eatenAmount: 80 }
        ]);
        assert.equal(toInputText(items), "LOTTE 씨리얼 초코 80g 400kcal");
    });

    await t.test("눈대중으로 낸 열량은 절대 안 적는다", () => {
        // 이게 새면 analyzeDiet이 모델의 추측을 포장에 인쇄된 값처럼 믿는다
        const items = normalizeExtracted([
            { name: "닭강정", amount: "1인분", kcal: 850, label: null }
        ]);
        assert.equal(toInputText(items), "닭강정 1인분");
    });

    await t.test("양을 못 잡았으면 이름만 남긴다", () => {
        const items = normalizeExtracted([{ name: "김치찌개", amount: "", kcal: 300 }]);
        assert.equal(toInputText(items), "김치찌개");
    });

    await t.test("여러 항목을 쉼표로 잇는다", () => {
        const items = normalizeExtracted([
            { name: "바나나", amount: "1개", kcal: 100 },
            { name: "칸쵸", amount: "43g", label: { kcal: 512, basisAmount: 100 }, eatenAmount: 43 }
        ]);
        assert.equal(toInputText(items), "바나나 1개, 칸쵸 43g 220kcal");
    });

    await t.test("빈 목록은 빈 문자열", () => {
        assert.equal(toInputText([]), "");
    });
});
