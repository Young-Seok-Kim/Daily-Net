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
* 
---

## 🛠 기술 스택 (Tech Stack)

| 분류 | 기술 도구                                        |
| :--- |:---------------------------------------------|
| **Language** | Kotlin                                       |
| **UI Framework** | Jetpack Compose                              |
| **Architecture** | MVVM, Clean Architecture, Repository Pattern |
| **DI** | Hilt                                         |
| **Database** | Room (Local), Firebase Firestore (Remote)    |
| **Backend** | Firebase Functions (Node.js) ,Firebase App Check               |
| **AI Engine** | Google Gemini 2.5 Flash                      |
| **Library** | Coroutines, Flow, Retrofit2, Serialization   |

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
