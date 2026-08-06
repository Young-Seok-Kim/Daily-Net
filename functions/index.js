/**
 * Firebase가 배포할 함수 목록.
 *
 * 실제 구현은 파일별로 나눠 두고 여기서는 내보내기만 한다.
 * 한 파일에 다 넣으면 700줄이 넘어가 어디를 고쳐야 할지 찾기 어려웠다.
 *
 * 서버 배포 명령어 : firebase deploy --only functions
 */
exports.analyzeDiet = require("./analyzeDiet").analyzeDiet;
exports.extractMeal = require("./extractMeal").extractMeal;

// 구독 검증. 앱이 보낸 "나 구독자야"를 믿지 않고 서버가 Play에 직접 확인한다.
exports.verifySubscription = require("./subscription").verifySubscription;
exports.playSubscriptionEvent = require("./subscription").playSubscriptionEvent;
