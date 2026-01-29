# Presentation Layer 아키텍처

## 책임 (Responsibilities)

Presentation Layer는 사용자 인터페이스와 상호작용을 담당합니다. MVVM 패턴을 따르며, ViewModel을 통해 데이터를 관찰하고 UI를 반응형으로 업데이트합니다.

---

## 컴포넌트 상세

### 1. MainActivity

**파일**: [`app/src/main/java/com/faust/presentation/view/MainActivity.kt`](app/src/main/java/com/faust/presentation/view/MainActivity.kt)

- **책임**: ViewPager2로 Fragment 통합, 권한 요청, 서비스 제어
- **의존성**: 
  - `MainViewModel`, `ShopViewModel` (데이터 관찰 및 비즈니스 로직)
  - `AppBlockingService`, `PointMiningService` (서비스 제어)
  - `PreferenceManager` (페르소나 설정 관리)
  - `DailyResetService`, `WeeklyResetService` (일일/주간 초기화 스케줄링)
  - `AppGroupService` (앱 그룹 초기화)
- **UI 구조**:
  - ViewPager2를 사용하여 3개의 Fragment 통합 (MainFragment, ShopFragment, SettingsFragment)
  - TabLayout으로 탭 네비게이션 제공
- **경량화**: 데이터베이스 직접 접근 제거, ViewModel을 통한 간접 접근

### 1.1 MainFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/MainFragment.kt`](app/src/main/java/com/faust/presentation/view/MainFragment.kt)

- **책임**: 차단 앱 목록 및 포인트 표시 (기존 MainActivity의 내용)
- **의존성**: 
  - `MainViewModel` (데이터 관찰 및 비즈니스 로직)
- **UI 업데이트**: 
  - ViewModel의 StateFlow를 관찰하여 UI 자동 업데이트
  - 포인트: `viewModel.currentPoints` StateFlow 구독
  - 차단 앱 목록: `viewModel.blockedApps` StateFlow 구독
- **페르소나 선택 기능**:
  - `showPersonaDialog()`: PersonaSelectionDialog를 표시하여 페르소나 선택 또는 등록 해제
  - 선택된 페르소나는 PreferenceManager에 저장

### 2. MainViewModel

**파일**: [`app/src/main/java/com/faust/presentation/viewmodel/MainViewModel.kt`](app/src/main/java/com/faust/presentation/viewmodel/MainViewModel.kt)

- **책임**: 데이터 관찰 및 비즈니스 로직 처리
- **의존성**:
  - `FaustDatabase` (데이터 소스)
  - `PreferenceManager` (설정 데이터)
- **StateFlow 관리**:
  - `currentPoints: StateFlow<Int>` - 포인트 합계
  - `blockedApps: StateFlow<List<BlockedApp>>` - 차단 앱 목록
  - `transactions: StateFlow<List<PointTransaction>>` - 거래 내역 (포인트 정산 로그 포함)
- **주요 메서드**:
  - `addBlockedApp()`: 차단 앱 추가
  - `removeBlockedApp()`: 차단 앱 제거
  - `getMaxBlockedApps()`: 티어별 최대 앱 개수 반환
- **티어별 최대 차단 앱 개수**:
  - `FREE`: 1개
  - `STANDARD`: 3개
  - `FAUST_PRO`: 무제한 (Int.MAX_VALUE)
- **테스트 모드**: `PreferenceManager.setTestModeMaxApps(10)`으로 설정 시 모든 티어에서 최대 10개까지 차단 가능 (실제 휴대폰 테스트용)
  - 기본값: 테스트 모드 활성화 (최대 10개)
  - 비활성화: `setTestModeMaxApps(null)` 호출

### 3. GuiltyNegotiationOverlay

**파일**: [`app/src/main/java/com/faust/presentation/view/GuiltyNegotiationOverlay.kt`](app/src/main/java/com/faust/presentation/view/GuiltyNegotiationOverlay.kt)

- **책임**: 시스템 오버레이로 유죄 협상 화면 표시
- **특징**:
  - `WindowManager`를 사용한 시스템 레벨 오버레이
  - 30초 카운트다운 타이머
  - 강행/철회 버튼 제공
  - Persona Module 통합: 능동적 계약 방식 (사용자 입력 검증)
  - 페르소나별 피드백 (시각, 촉각, 청각)
  - Safety Net: 기기 상태에 따른 피드백 모드 자동 조정
- **성능 최적화**:
  - 하드웨어 가속 활성화: `WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED` 플래그 사용
  - `PixelFormat.TRANSLUCENT`로 알파 채널 렌더링 시 가속 지원
  - `dimAmount = 0.5f`로 배경 어둡게 처리 (하드웨어 가속 시 부드러운 렌더링)
  - 앱 전체 하드웨어 가속: `AndroidManifest.xml`의 `<application>` 태그에 `android:hardwareAccelerated="true"` 설정

### 4. ShopFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/ShopFragment.kt`](app/src/main/java/com/faust/presentation/view/ShopFragment.kt)

- **책임**: 프리 패스 아이템 구매 및 사용 UI 제공
- **의존성**:
  - `ShopViewModel` (데이터 관찰 및 구매/사용 로직)
- **UI 구성**:
  - 아이템 목록: 도파민 샷, 스탠다드 티켓, 시네마 패스 표시
  - 각 아이템의 가격, 보유 수량, 쿨타임 표시
  - 구매 버튼 (쿨타임/잔액 부족 시 비활성화)
  - 인벤토리 섹션: 보유 아이템 표시 및 사용 버튼
- **주요 기능**:
  - 아이템 구매: `viewModel.purchaseItem(itemType)`
  - 아이템 사용: `viewModel.useItem(itemType)`
  - 구매/사용 결과 Toast 메시지 표시

### 5. SettingsFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/SettingsFragment.kt`](app/src/main/java/com/faust/presentation/view/SettingsFragment.kt)

- **책임**: 사용자 지정 일일 리셋 시간 설정
- **의존성**:
  - `PreferenceManager` (사용자 지정 리셋 시간 저장/조회)
  - `DailyResetService` (알람 재스케줄링)
- **주요 기능**:
  - "나의 하루가 끝나는 시간" 설정
  - TimePickerDialog로 시간 선택 (HH:mm 형식)
  - 현재 설정된 시간 표시
  - 설정 변경 시 DailyResetService 알람 재스케줄링

### 6. ShopViewModel

**파일**: [`app/src/main/java/com/faust/presentation/viewmodel/ShopViewModel.kt`](app/src/main/java/com/faust/presentation/viewmodel/ShopViewModel.kt)

- **책임**: 상점 데이터 관찰 및 구매/사용 로직 처리
- **의존성**:
  - `FaustDatabase` (데이터 소스)
  - `FreePassService` (구매/사용 로직)
  - `ActivePassService` (활성 패스 관리)
- **StateFlow 관리**:
  - `items: StateFlow<List<FreePassItemType>>` - 아이템 목록
  - `itemQuantities: StateFlow<Map<FreePassItemType, Int>>` - 각 아이템의 보유 수량
  - `itemCooldowns: StateFlow<Map<FreePassItemType, Long>>` - 각 아이템의 쿨타임 (밀리초)
  - `currentPoints: StateFlow<Int>` - 현재 포인트
  - `purchaseResult: StateFlow<PurchaseResult?>` - 구매 결과
  - `useResult: StateFlow<UseResult?>` - 사용 결과
- **주요 메서드**:
  - `purchaseItem(itemType)`: 아이템 구매 (도파민 샷은 구매 시 즉시 활성화)
  - `useItem(itemType)`: 아이템 사용 및 활성 패스 활성화
  - `getItemPrice(itemType)`: 아이템 가격 계산 (누진 가격 포함)
  - `canPurchaseItem(itemType)`: 구매 가능 여부 확인

### 7. PersonaSelectionDialog

**파일**: [`app/src/main/java/com/faust/presentation/view/PersonaSelectionDialog.kt`](app/src/main/java/com/faust/presentation/view/PersonaSelectionDialog.kt)

- **책임**: 페르소나 선택 및 등록 해제 다이얼로그
- **기능**:
  - 모든 페르소나 타입(STREET, CALM, DIPLOMATIC, COMFORTABLE)을 리스트로 표시
  - 각 페르소나에 대한 설명 표시
  - 현재 선택된 페르소나 표시 (체크마크)
  - "등록 해제" 옵션 제공 (랜덤 텍스트 사용)
- **의존성**:
  - `PreferenceManager` (페르소나 타입 저장/조회)
- **주요 메서드**:
  - `onCreateDialog()`: 다이얼로그 생성 및 페르소나 리스트 표시
  - `PersonaAdapter`: 페르소나 리스트 어댑터

---

## MVVM 패턴 구현

### 데이터 흐름

```
UI Component (MainActivity)
    ↓
ViewModel (MainViewModel)
    ↓
Database Flow (getTotalPointsFlow, getAllBlockedApps)
    ↓
ViewModel StateFlow 업데이트
    ↓
UI Update (Reactive)
```

### StateFlow 관찰

- **포인트 업데이트**: `viewModel.currentPoints.collect { }`
- **차단 앱 목록 업데이트**: `viewModel.blockedApps.collect { }`
- **거래 내역 업데이트**: `viewModel.transactions.collect { }`

---

## 관련 문서

- [마스터 아키텍처 문서](../ARCHITECTURE.md)
- [도메인 레이어 아키텍처](./arch_domain_persona.md)
- [데이터 레이어 아키텍처](./arch_data.md)
- [이벤트 정의 문서](./arch_events.md)
