# 📅 DailyNet (데일리넷)
> **AI 기반 퍼스널 식단 & 운동 정산 플랫폼**  
> 사용자의 신체 정보와 활동량을 바탕으로 AI가 '순 칼로리(Net Calories)'를 정밀 분석하는 스마트 다이어트 솔루션입니다.

**▶ [Play 스토어에서 받기](https://play.google.com/store/apps/details?id=com.youngs.dailynet)**

---

## 🚀 프로젝트 개요
`DailyNet`은 단순한 기록형 다이어트 앱을 넘어, **Gemini AI 엔진**을 통해 하루의 에너지 수지를 공학적으로 분석합니다.
2026년 5월 개인 프로젝트로 시작해 Play 스토어에 출시했고, 구독 결제·사용량 제어·다국어·강제 업데이트까지
**실제 서비스 운영에 필요한 기능을 단계적으로 갖춰 나가고 있습니다.**

---

## ✨ 핵심 기능 (Key Features)

### 1. AI 스마트 정산 엔진
* **Net Calories 계산**: (섭취 칼로리) - (기초대사량 + 운동 소모 칼로리) 공식으로 당일의 신체 변화를 예측합니다.
* **맞춤형 피드백**: Mifflin-St Jeor 공식으로 BMR을 산출하고, 체중 기준 권장 단백질을 포함한 영양 조언을 제공합니다.
* **걸음 수 자동 반영**: Health Connect에서 그날의 걸음 수를 읽어 운동 소모 칼로리에 합산합니다.
* **구조화 응답**: 서버가 계산 결과를 구조화해 함께 내려주어, 끼니별 칼로리와 탄단지를 주 단위로 집계·시각화합니다.

### 2. 음식 사진 인식 (Multimodal AI)
* 사진을 찍거나 갤러리에서 고르면 Gemini가 메뉴를 인식해 입력창을 채웁니다.
* 결과는 **확정이 아니라 초안**으로 넣어 사용자가 고칠 수 있게 합니다. AI의 양 추정은 정확하지 않기 때문입니다.
* **사진은 저장하지 않습니다.** 텍스트로 바꾼 뒤 즉시 폐기해 스토리지 비용과 개인정보 부담을 없앴습니다.

### 3. 하이브리드 데이터 매니지먼트
* **Offline-First**: 로컬 DB(Room)를 통한 빠른 데이터 접근 및 오프라인 조회 지원.
* **Cloud Sync**: Firebase Firestore를 연동하여 기기 변경 시에도 데이터 유지 및 실시간 백업.
* **무중단 스키마 변경**: 컬럼 추가 시 Room 마이그레이션을 정의해 기존 사용자의 로컬 기록이 초기화되지 않도록 합니다.

### 4. 보안 지향형 아키텍처
* **Server-side Proxy**: Firebase Functions를 통해 AI 프롬프트를 은닉하여 비즈니스 로직 유출 및 프롬프트 인젝션을 원천 차단했습니다.
* **Firebase App Check**: 앱의 무결성을 검증하고 비인가 기기·위변조 앱의 백엔드 호출을 차단합니다.
* **서버 주도 사용량 제어**: 하루 분석 횟수를 앱이 아닌 **서버가 Firestore 트랜잭션으로** 차감합니다.
  앱 데이터 삭제만으로 우회되던 문제를 막았고, 분석이 실패하면 차감분을 자동 환불합니다.

### 5. 운영 (Operations)
* **강제 / 권장 업데이트**: Remote Config로 **앱 배포 없이** 구버전 사용자를 차단하거나 업데이트를 안내합니다.
* **Crashlytics**: 앱이 죽는 크래시뿐 아니라, 조용히 실패하는 경우(분석 실패·사진 인식 실패 등)도 함께 수집합니다.
* **정산 리마인더**: WorkManager로 그날 정산을 하지 않은 사용자에게 저녁에 알립니다. (서버 불필요)
* **다국어**: 한국어 / 영어. 요청에 기기 언어를 실어 보내 **AI 리포트까지 같은 언어로** 응답합니다.

---

## 🛠 기술 스택 (Tech Stack)

| 분류 | 기술 도구                                        |
| :--- |:---------------------------------------------|
| **Language** | Kotlin                                       |
| **UI Framework** | Jetpack Compose (Material 3)                 |
| **Architecture** | MVVM, Repository Pattern                     |
| **DI** | Hilt                                         |
| **Database** | Room (Local), Firebase Firestore (Remote)    |
| **Backend** | Firebase Functions (Node.js), App Check, Remote Config |
| **AI Engine** | Google Gemini 2.5 Flash (텍스트 + 이미지)      |
| **영양 데이터** | 식품의약품안전처 식품영양성분 DB (OpenAPI I2790) |
| **결제 / 헬스** | Play Billing, Health Connect                |
| **운영** | Crashlytics, WorkManager                          |
| **Library** | Coroutines, Flow, Retrofit2, Gson, Coil      |

| 항목 | 값 |
| :--- | :--- |
| **minSdk** | 26 (Android 8.0) |
| **targetSdk / compileSdk** | 36 |
| **JVM Target** | 11 |

---

## 🏗 시스템 아키텍처 (System Architecture)

본 프로젝트는 **보안(Security)**과 **확장성(Scalability)**을 극대화하기 위해 독립적인 역할 분담을 지향하는 **3-Tier 구조**를 채택하고 있습니다.

### 1. Client (Android)
* **역할:** 사용자의 신체 데이터 및 식단/운동 입력 UI/UX 처리
* **특징:** 원격 데이터베이스인 **Firebase Firestore**로부터 유저의 식단 및 운동 데이터를 실시간으로 가져와(Fetch) 화면에 즉각적으로 반영합니다. 사용자가 입력한 데이터 역시 Firestore에 실시간으로 반영되어 상시 동기화된 상태를 유지합니다.

### 2. Backend (Firebase)
* **Firebase Functions (Node.js)** — 함수 두 개, 공용 모듈 네 개로 구성
   * `analyzeDiet` : 식단·운동 분석. 인증 → 사용량 차감 → Gemini 호출 → 리포트 조립
   * `extractMeal` : 음식 사진에서 메뉴명 추출. 분석 횟수를 소모하지 않는 별도 경로
   * **보안 강화:** API Key 노출을 원천 차단하고 AI 프롬프트를 백엔드 내에 은닉합니다.
   * **비용 최적화:** Firebase ID 토큰을 검증해 사용자를 특정하고, Firestore 트랜잭션으로 사용량을 차감합니다.
* **Firebase Firestore**
   * **데이터 동기화:** 클라이언트와 원격지 간의 식단 및 운동 데이터를 실시간으로 동기화하고 안전하게 백업합니다.
   * **사용량 저장소:** 분석·사진 인식 횟수도 같은 사용자 문서에 기록되어 서버가 단일 기준으로 판단합니다.

```
[ Android App ]  Compose · MVVM · Hilt · Room(로컬 캐시)
       │  Firebase ID 토큰 + App Check
       ▼
[ Cloud Functions ]  analyzeDiet · extractMeal · quota
       ├─▶ [ Gemini 2.5 Flash ]  텍스트 + 이미지
       ├─▶ [ 식약처 식품영양성분 DB ]  브랜드 제품 칼로리 보정
       └─▶ [ Firestore ]  기록 · 프로필 · 사용량 · 영양 캐시

앱은 AI API Key를 갖지 않는다. 모든 호출은 서버를 거치고, 사용량도 서버가 센다.
```

### 사용량 정책

| | 분석 | 사진 인식 |
| :--- | :--- | :--- |
| 무료 | 하루 3회 | 하루 3회 |
| 구독 | 무제한 | 하루 30회 |

* 결과를 내지 못한 요청(서버 오류, 음식 인식 실패)은 **차감분을 자동 환불**합니다.
* 사진은 환불과 별개로 **시도 횟수**를 따로 세어(하루 30회) 무한 재시도 악용을 막습니다.
* 날짜 기준은 **Asia/Seoul**입니다. 함수 런타임이 UTC라 그대로 두면 한국 자정 이후 9시간 동안 어제로 계산됩니다.

### 3. AI Engine (Gemini)
* **역할:** 서버(Functions)로부터 전달받은 가공 데이터를 바탕으로 정밀 분석 결과를 생성합니다.
* **특징:** Mifflin-St Jeor 공식을 적용한 정교한 BMR 산출 및 사용자 맞춤형 영양 조언을 피드백으로 제공합니다.

### 4. 영양 데이터 (식약처 식품영양성분 DB)

칼로리는 원래 **모델의 기억**에서 나왔습니다. 브랜드 가공식품처럼 정답이 정해진 것까지
추정으로 답하고 있었기에, 실제 DB를 조회해 바로잡는 단계를 넣었습니다.

```
사용자 입력 "스타벅스 아메리카노 톨 1잔, 베이글"
   │
   ├─ Gemini 분석 ──▶ 메뉴 단위로 분리 + 칼로리 추정
   │                   (문장 그대로는 DB를 검색할 수 없어 모델이 먼저 갈라준다)
   │
   └─ 식약처 조회 ──▶ 이름이 확실히 맞는 것만 DB 값으로 교체
                       └─ Firestore `foodCache`에 저장 (같은 메뉴 재조회 없음)
```

지켜야 할 원칙이 둘 있습니다. 둘 다 **"애매하면 손대지 않는다"** 입니다.

* **이름이 확실할 때만 교체합니다** (`MIN_SCORE`). 잘못 붙인 제품의 정확한 숫자는
  대충 맞는 추정값보다 나쁩니다. 사용자는 그 숫자가 DB에서 왔다는 걸 모르므로 틀려도 의심하지 않습니다.
* **모델 추정과 2배 넘게 벌어지면 교체하지 않습니다** (`MAX_RATIO`). DB는 1회 제공량 기준인데
  사용자는 "3캔"을 먹었을 수 있습니다. 그 경우 모델 쪽이 맞습니다.

`FOOD_API_KEY`가 없거나 조회에 실패하면 **아무것도 바뀌지 않고** 기존 추정값이 그대로 나갑니다.
영양 DB는 정확도를 올리는 보조 수단이지 분석의 전제 조건이 아닙니다.

데이터 출처는 **공공데이터포털의 [식품의약품안전처_식품영양성분DB정보](https://www.data.go.kr/data/15127578/openapi.do)** 입니다.
식품안전나라(foodsafetykorea)에도 같은 데이터가 있지만 남은 서비스가 전부 파일 다운로드 유형이라
OpenAPI 이용신청 자체가 되지 않습니다. 실시간 조회가 가능한 경로는 포털뿐입니다.

> ⚠️ 성분을 `AMT_NUM1`, `AMT_NUM2` … 처럼 **번호로만** 주는데 **데이터셋마다 순서가 다릅니다.**
> (열량·탄수화물·단백질·지방 순인 표가 있고, 열량·수분·단백질·지방·회분·탄수화물 순인 표가 있습니다)
> 틀린 자리를 읽어도 숫자는 그럴듯해 보여 배포 후에는 못 잡습니다.
> **키를 발급받은 직후 `functions/scripts/probeFoodDb.js`를 돌려 확인하십시오.**
> 이 스크립트는 엔드포인트 후보를 자동으로 찔러보고 동작하는 주소와 응답 필드를 알려줍니다.
>
> 마지막 방어선으로 값이 상식 밖이면(열량 2,000kcal 초과, 탄단지 300g 초과) 보정을 포기합니다.

---

## ⚙️ 빌드 및 실행 (Getting Started)

### 1. 필수 설정 파일

보안상 아래 파일들은 저장소에 포함되어 있지 않습니다. 클론 후 직접 추가해야 빌드가 가능합니다.

| 파일 | 위치 | 용도 |
| :--- | :--- | :--- |
| `google-services.json` | `app/` | Firebase 프로젝트 연결 |
| `local.properties` | 프로젝트 루트 | Android SDK 경로, `GOOGLE_WEB_CLIENT_ID` |
| `keystore.properties` | 프로젝트 루트 | 릴리즈 서명 정보 (릴리즈 빌드 시에만 필요) |
| `dailynet-key.jks` | `app/` | 릴리즈 서명 키 (릴리즈 빌드 시에만 필요) |

`keystore.properties` 형식은 다음과 같습니다.

```properties
storeFile=app/dailynet-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

> 서명 정보를 `local.properties`에 두면 Secrets Gradle Plugin이 이를 `BuildConfig` 필드로 생성하여
> 비밀번호가 APK에 평문으로 포함됩니다. 반드시 별도 파일로 분리하십시오.

### 2. 빌드

```bash
# 디버그 빌드
./gradlew assembleDebug

# 릴리즈 AAB (Play Console 업로드용)
./gradlew bundleRelease
```

릴리즈 AAB는 `app/build/outputs/bundle/release/` 에 `DailyNet_v{versionName}_b{versionCode}-release.aab` 형식으로 생성됩니다.
`keystore.properties`가 없으면 **서명 없이** 빌드되므로, 배포 전 서명 여부를 확인하십시오.

```bash
keytool -printcert -jarfile <생성된_AAB_경로> | grep SHA256
```

### 3. 서버(Cloud Functions) 배포

서버가 쓰는 키는 소스가 아니라 **Firebase Secret Manager**에 둡니다.

| 시크릿 | 용도 | 없으면 |
|---|---|---|
| `GEMINI_API_KEY` | Gemini 호출 | 분석·사진 인식이 동작하지 않음 |
| `FOOD_API_KEY` | 식약처 식품영양성분 DB 조회 | 조회를 건너뛰고 모델 추정값을 그대로 씀 (분석은 정상) |

```bash
# 값을 물어보면 발급받은 키를 붙여넣습니다 (입력값은 화면에 남지 않습니다)
firebase functions:secrets:set FOOD_API_KEY

# 등록 확인
firebase functions:secrets:access FOOD_API_KEY

firebase deploy --only functions
```

> 시크릿을 새로 넣거나 바꾸면 **함수를 다시 배포해야** 반영됩니다.
> 실행 중인 인스턴스는 기존 값을 들고 있습니다.

`functions/` 는 역할별로 나뉘어 있고, `index.js` 는 배포 대상만 모아 내보냅니다.

| 파일 | 역할 |
|---|---|
| `index.js` | 배포할 함수 목록 (진입점) |
| `analyzeDiet.js` | 하루 식단·운동 분석 |
| `extractMeal.js` | 음식 사진 → 메뉴명 추출 |
| `quota.js` | 인증, 하루 사용 횟수 제한 |
| `labels.js` | 리포트 언어별 고정 문구 |
| `gemini.js` | Gemini 응답 JSON 파싱·재시도 |
| `foodDb.js` | 식약처 영양 DB 조회·캐시·칼로리 보정 |
| `scripts/probeFoodDb.js` | 식약처 응답 필드 확인용 (배포 대상 아님) |

함수 하나만 고쳤다면 그것만 올릴 수 있습니다.

```bash
firebase deploy --only functions:analyzeDiet
```

> ⚠️ `quota.js` · `labels.js` · `gemini.js` 처럼 **두 함수가 함께 쓰는 파일**을 고쳤다면
> 반드시 `--only functions` 로 둘 다 올려야 합니다. 한쪽만 올리면 서버에 서로 다른 버전이 남습니다.

> ⚠️ **배포 출력에서 함수별 결과를 반드시 확인하십시오.**
> CLI가 `Skipped (No changes detected)` 로 건너뛰는데 실제로는 고친 함수일 때가 있습니다.
> (고치지 않은 함수가 갱신되고, 고친 함수가 건너뛰어지는 경우를 실제로 겪었습니다)
> 건너뛴 함수가 있으면 이름을 직접 지정해 다시 올리면 확실히 반영됩니다.
>
> ```bash
> firebase deploy --only functions:extractMeal
> ```

배포 직후 첫 요청은 콜드 스타트라 평소보다 오래 걸립니다. 응답 속도를 측정할 때는 1분쯤 뒤에 재시도하십시오.

---

## 🔒 하위 호환 규칙 (서버를 고치기 전에 반드시 읽을 것)

스토어에 나간 앱은 되돌릴 수 없습니다. 서버만 고쳐도 **구버전 앱이 조용히 깨지거나 잘못된 값을 보여줄 수 있습니다.**

**안전한 변경**

* 응답에 **필드 추가** — 구버전은 모르는 필드를 무시합니다 (Gson)
* 프롬프트·계산 로직 수정 — 응답 형식이 그대로면 안전합니다
* 요청에 optional 필드 추가 — 서버가 없을 때를 처리하면 됩니다

**깨지는 변경**

* 응답 필드의 **이름·타입 변경 또는 삭제** → 구버전에서 NPE 또는 파싱 실패
* 함수(엔드포인트) 이름 변경, 기존 함수 삭제 → 구버전은 그 URL을 계속 호출합니다
* 요청 필수 검증 추가 후 400 반환

**형식을 정말 바꿔야 한다면**

1. 새 함수(`analyzeDietV2`)를 추가하고 **기존 함수는 삭제하지 않습니다**
2. 새 앱을 스토어에 **100% 출시 완료**한 뒤
3. Remote Config `min_version_code`를 올려 구버전을 정리합니다

> ⚠️ 스토어 출시가 끝나기 전에 `min_version_code`를 올리면, 사용자가 업데이트 버튼을 눌러도
> 받을 버전이 없어 앱이 영구히 잠깁니다. **순서를 반드시 지키십시오.**

`functions/quota.js`의 `REQUIRE_AUTH`는 현재 `false`입니다.
토큰을 보내지 않는 구버전을 통과시키기 위한 것이며, 구버전이 정리된 뒤 `true`로 바꾸면 사용량 우회가 완전히 차단됩니다.

---

## ⚙️ Remote Config 파라미터

Firebase 콘솔 → **DevOps 및 사용자 참여 → Remote Config** 에서 관리합니다. 값을 바꾼 뒤 **[변경사항 게시]** 를 눌러야 반영됩니다.

| 키 | 유형 | 설명 |
| :--- | :--- | :--- |
| `min_version_code` | Number | 이 값 **미만**은 닫을 수 없는 강제 업데이트 |
| `recommend_version_code` | Number | 이 값 미만은 하루 한 번 권장 안내 |
| `force_update_message` | String | 강제 안내 문구 (비우면 앱 내장 문구) |
| `recommend_update_message` | String | 권장 안내 문구 (비우면 앱 내장 문구) |

* 앱 기본값은 `res/xml/remote_config_defaults.xml`에 **0** 으로 두어, 값을 못 받아도 앱이 잠기지 않습니다.
* 문구는 **비워두는 것을 권장**합니다. 앱 내장 문구가 기기 언어를 정확히 따라가기 때문입니다.
  (Remote Config의 언어 조건은 기기 언어 기준이라, 앱 언어를 따로 지정한 사용자와 어긋날 수 있습니다)

---

## 🗂 Firestore 구조

```
users/{uid}
  email, googleName, height, initialWeight, isMale, birthDate
  createdAt              최초 로그인 시각 (Timestamp)
  profileCompleted       false = 가입만 하고 온보딩 이탈
  isSubscribed           구독 여부
  todayAnalysisCount     오늘 분석 횟수      ← 서버가 트랜잭션으로 갱신
  lastAnalyzedDate       기준 날짜 (Asia/Seoul)
  todayPhotoCount        오늘 사진 인식 횟수  ← 환불 대상
  todayPhotoAttempts     오늘 사진 시도 횟수  ← 환불하지 않음 (악용 방지)
  lastPhotoDate

  settlements/{yyyy-MM-dd}    하루 정산 기록
```

> 보안 규칙은 로그인한 사용자가 **자신의 문서에만** 접근하도록 최소 권한으로 걸려 있습니다. (`firestore.rules` 참고)

---
