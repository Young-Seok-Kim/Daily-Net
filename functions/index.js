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
