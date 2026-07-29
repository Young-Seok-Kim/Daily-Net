# 📅 DailyNet (데일리넷)
> **AI 기반 퍼스널 식단 & 운동 정산 플랫폼**  
> 사용자의 신체 정보와 활동량을 바탕으로 AI가 '순 칼로리(Net Calories)'를 정밀 분석하는 스마트 다이어트 솔루션입니다.

---

## 🚀 프로젝트 개요
`DailyNet`은 단순한 기록형 다이어트 앱을 넘어, **Gemini AI 엔진**을 통해 하루의 에너지 수지를 공학적으로 분석합니다. 5년 차 안드로이드 개발자의 아키텍처 설계 노하우를 바탕으로 **안정적인 데이터 동기화**와 **서버 측 프롬프트 보안**을 강화했습니다.

---

## ✨ 핵심 기능 (Key Features)

### 1. AI 스마트 정산 엔진
* **Net Calories 계산**: (섭취 칼로리) - (기초대사량 + 운동 소모 칼로리) 공식을 통해 당일의 신체 변화를 예측합니다.
* **상세 분석 로그**: 분석 결과를 IDE 터미널 로그 형식의 테이블로 제공하여 시각적 즐거움과 전문성을 동시에 제공합니다.
* **맞춤형 피드백**: Mifflin-St Jeor 공식을 적용한 정교한 BMR 산출 및 사용자 맞춤형 영양 조언을 제공합니다.

### 2. 하이브리드 데이터 매니지먼트
* **Offline-First**: 로컬 DB(Room)를 통한 빠른 데이터 접근 및 오프라인 입력 지원.
* **Cloud Sync**: Firebase Firestore를 연동하여 기기 변경 시에도 데이터 유지 및 실시간 백업.

### 3. 보안 지향형 아키텍처
* **Server-side Proxy**: Firebase Functions를 통해 AI 프롬프트를 은닉하여 비즈니스 로직 유출 및 프롬프트 인젝션을 원천 차단했습니다.
* **Firebase App Check 도입:** 앱의 무결성을 검증하고 승인되지 않은 비인가 기기나 위변조된 앱으로부터의 백엔드(Firestore, Functions) API 호출을 차단하여 자원 어뷰징 및 비용 리스크를 방지했습니다.

---

## 🛠 기술 스택 (Tech Stack)

| 분류 | 기술 도구                                        |
| :--- |:---------------------------------------------|
| **Language** | Kotlin                                       |
| **UI Framework** | Jetpack Compose                              |
| **Architecture** | MVVM, Clean Architecture, Repository Pattern |
| **DI** | Hilt                                         |
| **Database** | Room (Local), Firebase Firestore (Remote)    |
| **Backend** | Firebase Functions (Node.js), Firebase App Check |
| **AI Engine** | Google Gemini 2.5 Flash                      |
| **Library** | Coroutines, Flow, Retrofit2, Serialization   |

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
* **Firebase Functions (Node.js)**
   * **보안 강화:** API Key 노출을 원천 차단하고 AI 프롬프트를 백엔드 내에 은닉하여 비즈니스 로직 유출을 방지합니다.
   * **비용 최적화:** 사용자별 호출 제한(Rate Limiting)을 구현하여 무분별한 AI 엔진 API 호출 및 비용 리스크를 관리합니다.
* **Firebase Firestore**
   * **데이터 동기화:** 클라이언트와 원격지 간의 식단 및 운동 데이터를 실시간으로 동기화하고 안전하게 백업합니다.

### 3. AI Engine (Gemini)
* **역할:** 서버(Functions)로부터 전달받은 가공 데이터를 바탕으로 정밀 분석 결과를 생성합니다.
* **특징:** Mifflin-St Jeor 공식을 적용한 정교한 BMR 산출 및 사용자 맞춤형 영양 조언을 피드백으로 제공합니다.

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

```bash
firebase deploy --only functions
```

`functions/` 는 역할별로 나뉘어 있고, `index.js` 는 배포 대상만 모아 내보냅니다.

| 파일 | 역할 |
|---|---|
| `index.js` | 배포할 함수 목록 (진입점) |
| `analyzeDiet.js` | 하루 식단·운동 분석 |
| `extractMeal.js` | 음식 사진 → 메뉴명 추출 |
| `quota.js` | 인증, 하루 사용 횟수 제한 |
| `labels.js` | 리포트 언어별 고정 문구 |
| `gemini.js` | Gemini 응답 JSON 파싱·재시도 |

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
