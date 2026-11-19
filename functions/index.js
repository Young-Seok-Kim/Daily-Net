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
// 인바디 결과지 사진에서 기초대사량 등을 읽는다. 설정 화면의 인바디 입력이 쓴다.
exports.extractInbody = require("./extractInbody").extractInbody;
// 인바디 결과 해석. 저장할 때 한 번 부르고, 분석 횟수는 소모하지 않는다.
exports.analyzeInbody = require("./analyzeInbody").analyzeInbody;
// 주·월·연 정산의 식단·운동 평가. 정산 목록의 구분선 패널과 월 정산 화면이 부른다.
exports.evaluatePeriod = require("./evaluatePeriod").evaluatePeriod;

// 구독 검증. 앱이 보낸 "나 구독자야"를 믿지 않고 서버가 Play에 직접 확인한다.
exports.verifySubscription = require("./subscription").verifySubscription;
exports.playSubscriptionEvent = require("./subscription").playSubscriptionEvent;

// 회원탈퇴. 서브컬렉션까지 통째로 지워야 해서 서버가 한다 (앱은 서브컬렉션 목록을 못 읽는다).
exports.withdrawAccount = require("./withdraw").withdrawAccount;

// 전역 AI 질문. 지금 보고 있는 화면(앱이 요약해 보낸 데이터)에 대해 묻는 짧은 대화를 답한다.
// 분석 횟수는 소모하지 않고 따로 센다 (quota.reserveAiAsk).
exports.askAiCoach = require("./askAiCoach").askAiCoach;
