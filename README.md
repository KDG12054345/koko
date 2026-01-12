# Faust - 스마트폰 사용 습관 개선 앱

도파민 억제와 이성적 통제를 결합한 행동 수정 솔루션입니다. 경제적 페널티와 다중 감각 피드백을 통해 사용자의 스마트폰 사용 습관을 근본적으로 재설계합니다.

## 주요 기능 (MVP)

### 1. 앱 차단 시스템
- 차단된 앱 실행 시 4-6초 지연 후 유죄 협상 화면 표시
- 30초 대기 시간과 강행/철회 선택 제공

### 2. 포인트 채굴 시스템
- 차단되지 않은 앱 사용 시 포인트 적립
- Free 티어: 10분당 0.5 WP (0.5x 효율)
- 백그라운드 서비스로 자동 추적

### 3. 페널티 시스템
- **강행 (Launch):** 3 WP 삭감
- **철회 (Quit):** Free 티어는 차감 없음

### 4. 주간 정산
- 매주 월요일 00:00 자동 실행
- 보유 포인트 > 100 WP: 100 WP 제외하고 모두 몰수
- 보유 포인트 ≤ 100 WP: 전액 몰수

## 기술 스택

- **언어:** Kotlin
- **최소 SDK:** 26 (Android 8.0)
- **타겟 SDK:** 34 (Android 14)
- **아키텍처:** MVVM 패턴 준수
- **데이터베이스:** Room Database
- **백그라운드 작업:** Foreground Service, WorkManager
- **UI:** Material Design Components

## 프로젝트 구조

```
app/src/main/java/com/faust/
├── models/              # 데이터 모델
│   ├── BlockedApp.kt
│   ├── PointTransaction.kt
│   └── UserTier.kt
├── database/             # Room Database
│   ├── FaustDatabase.kt
│   ├── AppBlockDao.kt
│   └── PointTransactionDao.kt
├── services/             # 백그라운드 서비스
│   ├── AppBlockingService.kt
│   ├── PointMiningService.kt
│   ├── PenaltyService.kt
│   └── WeeklyResetService.kt
├── ui/                   # UI 컴포넌트
│   ├── MainActivity.kt
│   ├── GuiltyNegotiationOverlay.kt
│   ├── BlockedAppAdapter.kt
│   └── AppSelectionDialog.kt
└── utils/                # 유틸리티
    ├── PreferenceManager.kt
    └── TimeUtils.kt
```

## 필수 권한

1. **사용 통계 권한 (PACKAGE_USAGE_STATS)**
   - 앱 사용 추적 및 차단 기능에 필요
   - 설정 > 앱 > 특별 액세스 > 사용 통계 접근

2. **다른 앱 위에 표시 권한 (SYSTEM_ALERT_WINDOW)**
   - 유죄 협상 오버레이 표시에 필요
   - 설정 > 앱 > 특별 액세스 > 다른 앱 위에 표시

3. **포그라운드 서비스 권한**
   - 백그라운드 포인트 채굴에 필요
   - Android 14+ 특별 사용 권한 필요

## 빌드 및 실행

### 사전 요구사항
- Android Studio Hedgehog 이상
- JDK 17 이상
- Android SDK 34

### 빌드 방법

1. 프로젝트 클론
```bash
git clone <repository-url>
cd Faust
```

2. Android Studio에서 프로젝트 열기

3. Gradle 동기화

4. 앱 실행 (Shift+F10 또는 Run 버튼)

## 사용 방법

1. **앱 실행 후 권한 허용**
   - 사용 통계 권한
   - 다른 앱 위에 표시 권한

2. **차단할 앱 추가**
   - 메인 화면에서 "앱 추가" 버튼 클릭
   - Free 티어는 최대 1개 앱만 차단 가능

3. **서비스 시작**
   - 우측 하단 FAB 버튼 클릭
   - 앱 차단 및 포인트 채굴 시작

4. **포인트 확인**
   - 메인 화면 상단에 현재 포인트 표시
   - 차단되지 않은 앱 사용 시 자동 적립

## MVP 제한 사항

- Free 티어만 지원 (최대 1개 앱 차단)
- 음성 페르소나 미지원 (텍스트만)
- 상점 시스템 미구현
- Standard/Faust Pro 티어 미구현

## 향후 계획

- Standard 티어 구현 (3개 앱, 1.5x 효율)
- Faust Pro 티어 구현 (무제한, 2.0x 효율, 고위험 페널티)
- TTS 기반 음성 페르소나
- 상점 및 아이템 시스템
- 다차원 분석 프레임워크 (NDA)

## 라이선스

이 프로젝트는 개인 사용 목적으로 개발되었습니다.
