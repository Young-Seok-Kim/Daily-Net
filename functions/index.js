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

// 영양 캐시 채우기(warmCache.js)는 **일부러 내보내지 않는다.**
// 상시로 열어둘 필요가 없는 운영용 함수라, 쓸 때만 아래 줄을 살려 배포하고 끝나면 다시 지운다.
//   exports.warmCache = require("./warmCache").warmCache;
// 자세한 사용법은 warmCache.js 맨 위 주석 참고.

// 구독 검증. 앱이 보낸 "나 구독자야"를 믿지 않고 서버가 Play에 직접 확인한다.
exports.verifySubscription = require("./subscription").verifySubscription;
exports.playSubscriptionEvent = require("./subscription").playSubscriptionEvent;
