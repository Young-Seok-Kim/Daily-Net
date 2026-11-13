# 📅 DailyNet (데일리넷)
> **AI 기반 식단 · 운동 정산 앱**
> 사용자가 적은 하루 식단과 운동을 서버가 Gemini로 분석해 섭취·소모·순칼로리와 탄단지를 돌려주고,
> 그 기록을 차트·위젯·구글 캘린더로 이어 주는 안드로이드 앱입니다.

**▶ [Play 스토어에서 받기](https://play.google.com/store/apps/details?id=com.youngs.dailynet)** · 홈페이지 [dailynet.web.app](https://dailynet.web.app/)

---

## 🚀 개요

2026년 5월 개인 프로젝트로 시작해 Play 스토어에 출시했고, 지금은 **main 브랜치에 머지하면 서버·앱·스토어까지
자동으로 배포되는** 상태로 운영 중입니다. 앱은 Kotlin + Jetpack Compose, 서버는 Firebase Cloud Functions(Node 24),
AI는 Gemini를 씁니다. 앱은 AI 키를 갖지 않고 모든 분석은 서버를 거칩니다.

---

## ✨ 기능 (기술 관점)

### 정산 분석
* 아침·점심·저녁·간식·운동·비고·걸음·체중을 한 화면에서 입력하면 서버가 Gemini로 분석합니다.
* 모델은 항목별 **단위 열량과 개수**만 내고, 합계·끼니 총량·탄단지·순칼로리·운동 소모량은 **서버가 다시 계산**합니다.
  모델이 만든 걸음 항목은 버리고 실제 걸음 수로 바꿔 넣는 식으로, 숫자의 최종 책임은 서버 코드에 둡니다.
* 응답은 텍스트가 아니라 **구조화된 JSON**으로 내려오고, 앱은 그 구조로 리포트를 그립니다.
  그래서 항목 하나의 열량을 손으로 고치면 서버에 다시 묻지 않고 기기에서 합계를 재계산합니다.
* 기초대사량은 Mifflin-St Jeor 공식이 기본이고, 인바디 측정값이 있으면 그 값이 우선합니다.

### 음식 사진 인식
* 앱 안의 CameraX 화면으로 찍거나 갤러리에서 여러 장을 골라 한 번에 보냅니다.
  (시스템 카메라를 띄우면 셔터음을 끌 수 없어 직접 구현)
* 사진은 긴 변 768px로 줄여 Base64로 보내고 **서버에도 앱에도 저장하지 않습니다**. 텍스트로 바꾼 뒤 바로 버립니다.
* 결과는 입력창에 **초안**으로 들어가 사용자가 고칠 수 있습니다. 사진에 **인쇄된 열량**(영양표·메뉴판)만 숫자로 신뢰합니다.

### 인바디
* 결과지 사진에서 숫자를 읽거나 직접 입력해 측정 이력을 쌓고, 서버가 BMI·체지방 구간을 계산해 해석 리포트를 만듭니다.
* 최신 측정값의 기초대사량이 모든 식단 분석에 들어가며, 항목별 추이 차트를 제공합니다.

### 기록 · 통계
* 홈 화면은 주·월 단위로 묶인 정산 목록과 최근 순칼로리 추이 차트, 월간 리포트(일별 순칼로리·주간 비교·체중 추이)로 구성됩니다.
* Room을 **오프라인 우선** 로컬 캐시로 두고 Firestore와 동기화합니다. 컬럼이 늘 때마다 마이그레이션을 정의해
  기존 사용자의 기록이 초기화되지 않게 합니다(현재 DB 버전 15).
* 자주 쓰는 메뉴·운동은 분석 결과에서 **자동으로 수집**해 입력창 아래 칩으로 보여 줍니다. 고정·숨김·직접 추가만 클라우드에 동기화합니다.

### 기기 연동
* **Health Connect**: 그날의 걸음 수를 읽어 운동 소모량에 반영합니다. 직접 입력한 값은 덮어쓰지 않습니다.
* **구글 캘린더**: 설정에서 켜면 정산할 때마다 운동이 **하루 종일 일정**으로 들어갑니다. 제목을 운동 내용으로 할지
  고정 제목에 메모로 넣을지 고를 수 있고, 과거 기록을 한 번에 넣거나 제목 방식에 맞게 고치는 동기화가 있습니다.
  범위는 `calendar.app.created` 하나라 **앱이 만든 "데일리넷 운동" 캘린더만** 읽고 쓸 수 있고, 민감하지 않은 범위라
  구글 OAuth 심사 없이 배포합니다. 토큰은 로그인과 별개로 `AuthorizationClient`로 받고, 일정마다 숨은 속성에
  기록 날짜를 적어 같은 날을 다시 정산하면 기존 일정을 고칩니다.
* **홈 위젯(Glance)**: 정산 전에는 "정산하기", 정산 후에는 오늘 순칼로리를 보여 주고 Room 변경을 따라 바로 갱신됩니다.
* **정산 리마인더**: WorkManager로 매일 21시에 정산하지 않은 날만 알립니다. 서버가 필요 없습니다.
* 기기 캘린더의 공휴일을 읽어 목록의 날짜 색을 칠합니다.

### 운영
* **강제 / 권장 업데이트**: Remote Config의 최소·권장 버전 코드로 **앱 배포 없이** 구버전을 막거나 안내합니다.
  기본값은 0이라 값을 못 받아도 앱이 잠기지 않습니다.
* **Crashlytics**: 크래시뿐 아니라 조용히 삼킨 예외(분석 실패, 사진 인식 실패 등)도 위치 태그와 함께 모읍니다.
* **구독**: Play Billing으로 결제하고, 구독 여부는 앱이 아니라 **서버가 Play Developer API와 RTDN으로** 판단합니다.
  현재는 무료 개방 스위치가 켜져 있어 모든 사용자가 구독자 한도를 씁니다(서버 상수 하나로 되돌림).
* **다국어**: 한국어 / 영어. 요청에 기기 언어를 실어 보내 AI 리포트까지 같은 언어로 받습니다.

---

## 🛠 기술 스택

| 영역 | 사용 |
| :--- | :--- |
| 언어 · UI | Kotlin 2.2, Jetpack Compose + Material 3, 단일 Activity와 직접 구현한 백스택 |
| 아키텍처 | MVVM, Hilt, Room(로컬 캐시), Retrofit + OkHttp |
| Firebase | Auth(Credential Manager 구글 로그인), Firestore, Cloud Functions(Node 24), Remote Config, Crashlytics, App Check(Play Integrity), Hosting |
| AI | Gemini (gemini-3.5-flash-lite 기본, 과부하 시 flash로 대체), 서버에서만 호출 |
| 기기 연동 | Health Connect, Google Calendar API(앱 직접 호출), Glance 위젯, WorkManager, CameraX |
| 결제 | Play Billing 9, 서버 검증(Play Developer API + RTDN) |
| 배포 | GitHub Actions → Firebase CLI, Play Developer API, GitHub Release |

---

## 🏗 아키텍처

```
[ Android App ]  Compose · MVVM · Hilt · Room(오프라인 우선 캐시)
       │  Firebase ID 토큰 + App Check
       ├─▶ [ Google Calendar API ]  운동 일정 (앱 전용 보조 캘린더, 서버를 거치지 않음)
       ├─▶ [ Firestore ]  기록 · 프로필 · 즐겨찾기 · 인바디 (본인 문서만, 서버 전용 필드는 쓰기 차단)
       ▼
[ Cloud Functions · asia-northeast3 ]
       ├─ analyzeDiet        식단·운동 분석 → 서버 재계산 → 구조화 응답
       ├─ extractMeal        음식 사진 → 메뉴 텍스트
       ├─ extractInbody      인바디 결과지 사진 → 숫자
       ├─ analyzeInbody      인바디 해석 리포트
       ├─ verifySubscription 구매 토큰 검증
       └─ playSubscriptionEvent  Play RTDN(Pub/Sub) 수신
              ├─▶ [ Gemini ]      텍스트 + 이미지, 재시도·대체 모델·시간 예산 관리
              ├─▶ [ Firestore ]   사용량 트랜잭션, 구독 상태
              └─▶ [ Play Developer API ]  구독 검증
```

* 앱은 AI 키를 갖지 않습니다. AI 호출은 모두 서버를 거치고, 사용량도 서버가 셉니다.
* 캘린더만 예외로 앱이 사용자 토큰으로 구글에 직접 씁니다. 서버가 사용자 캘린더 토큰을 들고 있을 이유가 없기 때문입니다.

### 서버 모듈 구성
`functions/`는 역할별 파일로 나뉘고 `index.js`가 배포 대상만 내보냅니다. 배포되는 함수 여섯 개 외의 파일은 공용 모듈입니다.

| 묶음 | 파일 | 역할 |
|---|---|---|
| 진입점 | `analyzeDiet` `extractMeal` `extractInbody` `analyzeInbody` `subscription` | 배포되는 함수 |
| 정책 | `quota` `requestGuard` | 인증, 하루 사용량(Firestore 트랜잭션, 실패 시 환불), 봇·스캐너 요청 거절 |
| 계산 | `exerciseCalc` `dailyTotals` `shareItems` `nutrition` `inbody` | 운동 소모량(METs·걸음), 끼니 합계·탄단지, 나눠 먹은 양, 권장 섭취량, 인바디 지표 |
| 입력 정리 | `splitInput` `mergeItems` `extractItems` | 입력 분리, 중복 항목 병합, 사진 결과를 입력 텍스트로 변환 |
| 검증 | `kcalCheck` `exerciseCheck` | 열량·운동 강도가 상식 범위인지 로그로만 점검(값은 바꾸지 않음) |
| AI · 문구 | `gemini` `labels` | 모델 호출·JSON 복구·재시도, 언어별 리포트 문구 |

서버 테스트는 `functions/test/`에 모듈별로 있고 `node --test`로 돌립니다. 앱 유닛 테스트는 리포트 편집 계산,
즐겨찾기 순위, 월 범위, 체중 추이 등 **순수 함수**에 집중돼 있습니다.

### 사용량 정책

| | 분석 | 사진 인식 |
| :--- | :--- | :--- |
| 무료 | 하루 3회 | 제한 없음 |
| 구독 | 무제한 | 제한 없음 |

* 분석 횟수는 **서버가 Firestore 트랜잭션으로** 차감하고, 결과를 내지 못하면 자동 환불합니다. 앱 데이터 삭제로 우회할 수 없습니다.
* 사진 인식은 횟수 제한이 없고 계정 탈취·스크립트 사고만 막는 하루 시도 상한(300장)이 있습니다.
* 날짜 기준은 Asia/Seoul입니다. 함수 런타임이 UTC라 그대로 두면 한국 자정 이후 9시간 동안 어제로 계산됩니다.

---

## 🔒 보안

* **App Check(Play Integrity)**: 위변조 앱·비인가 기기의 함수 호출을 차단합니다.
* **서버 측 프록시**: AI 키와 프롬프트는 서버에만 있습니다.
* **요청 가드**: 외부 스캐너의 GET·빈 요청이 Gemini 호출까지 가서 한도를 소진한 적이 있어, 모델을 부르기 전에
  메서드·본문을 검사해 405/400으로 거절합니다.
* **Firestore 규칙**: 본인 문서만 읽고 쓸 수 있고, 사용량·구독 상태처럼 **서버만 써야 하는 필드**는 앱 쓰기를 거부합니다.
* **구독 신뢰는 서버에**: 앱은 구매 토큰만 넘기고, 서버가 Play에 확인한 결과와 만료 시각으로만 판단합니다.
* 서명 키, `google-services.json`, 클라이언트 ID는 저장소에 없고 CI 시크릿으로만 주입됩니다.

---

## ⚙️ 빌드 및 실행

### 필수 설정 파일 (저장소에 없음)

| 파일 | 내용 |
|---|---|
| `app/google-services.json` | Firebase 콘솔에서 받은 앱 설정 |
| `local.properties` | `GOOGLE_WEB_CLIENT_ID=` 구글 로그인용 웹 클라이언트 ID (secrets-gradle-plugin이 BuildConfig로 넣음) |
| `keystore.properties` | 릴리스 서명 키 경로·비밀번호·별칭. 없으면 서명 없이 빌드됨 |

### 빌드

```bash
./gradlew :app:assembleDebug                       # 디버그
./gradlew :app:assembleRelease :app:bundleRelease  # 릴리스 APK · AAB (유닛 테스트와 같은 명령에 섞지 않음)
./gradlew testDebugUnitTest                        # 앱 유닛 테스트
```

릴리스 빌드는 R8 난독화가 켜져 있습니다. Gson으로 JSON을 주고받는 모델 클래스는 `app/proguard-rules.pro`의
keep 규칙에 들어 있어야 합니다. 빠뜨리면 디버그에서는 되고 스토어 빌드에서만 깨집니다.

### 서버(Cloud Functions) 배포

```bash
cd functions && npm ci && npm test
firebase functions:secrets:set GEMINI_API_KEY     # 최초 1회. 바꾸면 함수를 다시 배포해야 반영
firebase deploy --only functions                  # 또는 functions:analyzeDiet 처럼 하나만
```

> 공용 모듈(`quota` `gemini` `labels` 등)을 고쳤다면 `--only functions`로 전부 올려야 합니다. 한쪽만 올리면 서버에
> 서로 다른 버전이 남습니다. CLI가 "No changes detected"로 건너뛴 함수가 있으면 이름을 지정해 다시 올리십시오.

---

## 🔁 CI/CD — main에 머지하면 배포

브랜치는 두 개입니다. **작업은 `main_qa`, 배포는 `main`** 이고 main에 직접 커밋하지 않습니다.

```
main_qa 에 push ──▶ ci.yml       서버·앱 유닛 테스트
                └─▶ auto-pr.yml  main 으로 가는 PR 생성 (이미 있으면 갱신)
                                      │
                               PR 머지 (= 배포 결정, CI 통과가 조건)
                                      ▼
main 에 push ─────▶ deploy.yml
                      ├─ functions/ hosting/ 이 바뀌었으면 → 서버 테스트 → firebase deploy
                      ├─ versionCode 가 올랐으면 → 유닛 테스트 → bundleRelease → Play 업로드
                      │     트랙은 app/play-track 이 정함: production(바로 출시) 또는 internal(내부 테스트만)
                      ├─ store-listing/ 이 바뀌었으면 → Play 스토어 등록정보 갱신
                      └─ 앱 업로드가 성공하면 v{versionName}-b{versionCode} 태그 + GitHub 릴리스
```

사람이 하는 일은 `main_qa`에 커밋하고 PR을 머지하는 것뿐입니다. 서버·앱·등록정보·태그는 바뀐 것만 골라 나갑니다.

| 워크플로 | 트리거 | 하는 일 |
|---|---|---|
| `ci.yml` | main · main_qa push, PR | 서버(`node --test`)·앱(`testDebugUnitTest`) 테스트 |
| `auto-pr.yml` | main_qa push | main_qa → main PR 생성·갱신. 본문은 main에 없는 커밋 목록 |
| `deploy.yml` | main push | 서버 배포, AAB 빌드 → Play 업로드(트랙은 `app/play-track`), 등록정보, 태그·릴리스 |
| `promote.yml` | 수동 | 내부 테스트 트랙의 번들을 프로덕션으로 승격(재업로드 없음) |
| `preflight.yml` | 수동 · 워크플로 파일 변경 | 시크릿이 실제 쓰이는 방식대로 유효한지 검사(변경 없음) |

* 앱 배포 조건이 **versionCode 변경**인 이유: 같은 versionCode를 Play에 두 번 올리면 거부되고, 버전을 안 올린 커밋이
  스토어로 가면 안 되기 때문입니다.
* 출시 노트는 `CHANGELOG.md` 맨 위 항목의 `### 스토어 문구` 블록을 그대로 씁니다(500자 초과 시 실패).
* **어느 트랙에 올릴지는 `app/play-track` 파일**이 정합니다. `production`이면 바로 출시(심사 뒤 공개),
  `internal`이면 내부 테스트 트랙에만 올라가 테스터 목록의 계정이 심사 없이 즉시 받습니다. 내부 테스트로 확인을
  끝낸 빌드는 Actions의 "Play 프로덕션 승격"을 수동 실행해 같은 번들을 그대로 출시합니다.
* `store-listing/` 폴더가 곧 Play 등록정보(앱 이름·설명·스크린샷)입니다.
* 필요한 저장소 시크릿은 `deploy.yml` 머리 주석에 있습니다. 서명 키가 GitHub 시크릿에 있으므로 저장소 접근 권한은 최소로 유지하십시오.

> 서버 변경은 main에 들어가는 **즉시 구버전 앱까지 전부**에 적용됩니다. 반쯤 된 서버 변경을 머지하면 안 됩니다.

---

## 🔒 하위 호환 규칙 (서버를 고치기 전에)

스토어의 구버전 앱이 같은 서버를 계속 부릅니다. 다음은 **금지**입니다.

* 응답 필드 이름 변경·삭제, 타입 변경
* 함수 이름 변경, 기존 함수 삭제
* 새 필수 요청 검증을 추가하고 400으로 거절

형식을 정말 바꿔야 하면 새 함수를 추가하고 기존 함수는 두며, 새 앱이 스토어에 100% 출시된 뒤 Remote Config의
최소 버전을 올려 구버전을 정리합니다. 순서를 어기면 사용자가 업데이트 버튼을 눌러도 받을 버전이 없어 앱이 잠깁니다.

서버의 인증 필수 스위치는 아직 꺼져 있습니다. 토큰을 보내지 않는 구버전을 통과시키기 위한 것이며, 구버전이
정리된 뒤 켜면 사용량 우회가 완전히 차단됩니다.

---

## 📝 운영 메모

### 출시 절차
1. `main_qa`에서 버전을 올리고(`versionCode` +1, 새 기능은 가운데 자리, 수정은 마지막 자리) `CHANGELOG.md` 맨 위에
   스토어 문구와 상세 내역을 적습니다.
2. `app/play-track`을 정합니다. 테스터만 먼저 받게 하려면 `internal`, 바로 출시하려면 `production`.
3. PR을 머지합니다. CI 통과가 조건이고, 머지가 곧 배포입니다.
4. `internal`로 올렸다면 확인이 끝난 뒤 Actions의 **"Play 프로덕션 승격"** 을 실행합니다. 같은 번들이 재업로드 없이
   프로덕션으로 가고, 출시 노트는 그 버전의 CHANGELOG 항목에서 가져옵니다.

내부 테스트를 받을 계정은 Play Console > 테스트 > 내부 테스트 > 테스터에 넣고, 참여 링크를 그 기기에서 한 번 열어야
스토어에 테스트 버전이 보입니다. 테스트 버전은 심사 없이 몇 분 안에 풀립니다.

### 릴리스 빌드에서만 나는 문제
* R8이 켜져 있어 Gson 모델 클래스의 필드 이름이 난독화됩니다. 새 JSON 모델을 만들면 `app/proguard-rules.pro`에
  keep 규칙을 추가해야 합니다. 디버그 빌드로는 재현되지 않으므로 외부 API를 새로 붙였다면 **릴리스 APK로 한 번 확인**합니다.
* 서명이 다른 빌드(스토어 설치본 위에 로컬 릴리스 등)는 덮어 설치가 안 됩니다. 지우고 설치해야 합니다.

### 구글 캘린더 연동의 제약
* `calendar.app.created` 범위로는 **앱이 만든 캘린더에 쓰기**와 그 캘린더의 일정 조회만 됩니다.
  캘린더 목록 조회(calendarList.list)는 403, 목록 항목 조회(calendarList.get)는 문서와 달리 401이 납니다.
  그래서 캘린더 ID를 기기와 Firestore에 함께 저장해 두고, 재설치 때는 Firestore에서 되찾습니다.
* 사용자가 캘린더를 지워도 구글이 삭제를 전파하기까지 몇 분은 그 ID로 읽기·쓰기가 그대로 됩니다. 앱은 작업 전마다
  캘린더를 한 번 읽어 404면 새로 만들지만, 그 몇 분 사이에 쓴 일정은 잃습니다.
* 토큰은 Play 서비스가 캐시합니다. 사용자가 구글 계정 설정에서 권한을 지우면 캐시된 토큰으로 401이 나는데, 앱은
  토큰을 비우고 다시 승인받되 동의가 필요하면 동의 화면을 띄웁니다.
* 승인 요청에 로그인 계정을 지정하지 않으면 계정 선택 화면이 뜹니다. 앱은 Firebase 로그인 이메일로 고정합니다.

---

## 📁 저장소 구성

```
app/            안드로이드 앱 (Kotlin, Compose)
functions/      Cloud Functions (Node 24) + 테스트
hosting/        홈페이지 · 개인정보처리방침 (Firebase Hosting, dailynet.web.app)
store-listing/  Play 스토어 등록정보 (배포 워크플로가 올림)
firestore.rules Firestore 보안 규칙
CHANGELOG.md    버전별 변경 이력. 맨 위 항목의 스토어 문구가 출시 노트가 됨
.github/        CI/CD 워크플로
```
