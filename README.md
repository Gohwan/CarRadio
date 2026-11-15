# CarRadio

Pleos Connect에서 주변 참여 기기와 워키토키 음성, 도로 위험 신호, 도움 요청을 주고받는 Android 시제품입니다.
현재 구현은 같은 PC의 로컬 중계 서버를 사용하는 **디버그 시연 버전**입니다.

## 구현 범위

| 기능 | 현재 동작 |
| --- | --- |
| 채널·범위 | 일반 대화 / 도로 위험 / 도움 요청, 반경 1·3·5km, 수신 허용 |
| 문자 신호 | 위험·도움 상황을 선택하고 확인 후 전송, 메시지별 답장과 원문 인용 |
| 발신자 표시 | 사용자가 입력한 차량번호 뒤 4자리와 좌표로 계산한 거리. 차량 인증은 아님 |
| 워키토키 | 수신 동의 후 참여, 탭 시작·종료 또는 누르고 말하기, 한 번에 한 명, 최대 15초, 발언 후 2초 대기 |
| 음소거 | 현재 발언자를 이번 참여 동안 음소거. 문자 신호는 계속 수신 |
| 음성 메시지 | 최대 15초 녹음, 전송 전 미리듣기, 받은 음성 재생 |
| Pleos 음성 | 공식 STT·TTS SDK를 이용한 음성 작성·문자 읽기 |
| 주행 제한 | CarUxRestrictionsManager 연동, 제한 화면·상태 미확인 시 송신 제한, 디버그 화면 테스트 |

서버는 같은 채널, 양쪽 수신 범위와 수신 허용을 확인해 전달합니다. 받은 메시지는 최근 10건이며 전송 후 약 2분이 지나면 만료됩니다.
앱 수신 확인(ACK)은 프로그램의 메시지 처리 응답이고 사람의 열람 확인이 아닙니다. 워키토키는 반이중 방식으로 한 채널에서 한 기기만 발언권을 갖습니다.

## 시연 영상

[버튼 직접 조작 시연 영상 — MP4, 약 2분 55초](docs/CarRadio_demo_full.mp4)

영상에는 위험·도움 신호 전송, 수신한 이전 메시지에 답장, 워키토키 시험음 송수신·음소거, 주행 제한 화면 전환이 담겨 있습니다.
음성 구간은 시험음을 사용했습니다. 사람의 실제 발화 녹음을 시연한 영상이 아닙니다. 차량번호·좌표는 테스트 입력값이며, 주행 화면 전환은 디버그 시뮬레이션입니다.

## 개발·테스트 환경

| 항목 | 값 |
| --- | --- |
| IDE | Android Studio, Kotlin / Jetpack Compose |
| 에뮬레이터 | Pleos Connect v2.1.0, Android API 34 |
| 시연 화면 | 에뮬레이터 전체 화면 2560 × 1440, 앱은 우측 분할 영역 |
| Android SDK | compileSdk / targetSdk 37, minSdk 28 |
| Gradle / AGP | 9.6.0 / 9.5.1 |
| Gradle JVM | 프로젝트 toolchainVersion 25. Android Studio의 Gradle JDK 설정 확인 |
| Kotlin / Compose BOM | 2.2.10 / 2026.02.01 |
| 중계 서버 테스트 | Node.js v24.22.1, 외부 npm 패키지 없음 |
| Pleos SDK | FusedLocation 1.1.3, TextToSpeech 3.3.1, SpeechToText 3.3.1 |

SDK 의존성은 Gradle이 Google Maven, Maven Central, Pleos 공식 Maven 저장소에서 받습니다. 최초 동기화에는 네트워크가 필요합니다.

## 실행 방법

### 1. PC 중계 서버

Windows PowerShell에서 프로젝트 루트로 이동한 후 실행합니다.

```powershell
.\relay\Start-Relay.ps1
```

다른 운영체제나 별도 터미널에서는 다음 명령을 사용할 수 있습니다.

```sh
node relay/server.mjs
```

서버 주소는 PC에서 `http://127.0.0.1:8765`, Android 에뮬레이터에서 `http://10.0.2.2:8765`입니다.
PC 테스트 화면은 `http://127.0.0.1:8765/test-client`, 상태 확인은 `/health`입니다. 서버는 127.0.0.1에 바인딩되며 인터넷 서비스로 배포된 서버가 아닙니다.

### 2. Android 앱

1. 이 폴더를 Android Studio에서 엽니다. Android SDK 37과 프로젝트의 Gradle JDK를 확인하고 Gradle Sync를 실행합니다.
2. Pleos Connect 에뮬레이터를 실행하고 `app` 모듈의 **debug** 변형을 Run합니다.
3. 앱의 **설정**에서 테스트 차량번호 뒤 4자리를 입력하고 위치를 연결합니다. 필요한 Pleos 위치 권한을 허용합니다.
4. **설정 → 개발 테스트 → 동의하고 연결**을 누릅니다.
5. PC 테스트 화면에서도 다른 테스트 번호로 접속하고 같은 채널·반경과 가까운 테스트 좌표를 설정합니다.
6. 위험·도움 신호를 보내고 받은 메시지마다 답장할 수 있는지 확인합니다.
7. 워키토키는 양쪽에서 수신에 동의하고 참여합니다. 말하기에는 마이크 권한과 PC 마이크 입력이 필요합니다. 개발 테스트의 1초 시험음으로 마이크 없이 전송 경로를 확인할 수도 있습니다.

Pleos 에뮬레이터가 주행 상태에서 앱 전체를 가리는 경우에는 P 상태로 돌아와 앱을 다시 실행합니다. 화면 제한만 확인하려면 앱의 **주행 테스트**를 사용합니다.

### 3. 자동 테스트

```sh
node --test relay/server.test.mjs relay/walkie-talkie.test.mjs relay/walkie-pcm.test.mjs
```

Android 정책 테스트 소스는 `app/src/test/`에 있습니다. Android Studio의 단위 테스트 실행 또는 Gradle의 `testDebugUnitTest`로 실행할 수 있습니다.
이 개발 세션에서는 정책 테스트 37개를 직접 컴파일해 실행했습니다. 이 결과를 Gradle 전체 빌드 성공으로 설명하지 않습니다.

## 검증 결과와 한계

- 2026-11-15: 중계 서버·워키토키·PCM 자동 테스트 47개 통과.
- 2026-11-14: Kotlin 정책 테스트 37개와 앱 소스의 Android/Compose/AAOS 컴파일 확인.
- 실제 에뮬레이터에서 버튼을 눌러 위험·도움 신호 전송, 이전 메시지 답장 전송, 시험음 수신·음소거와 앱 시험음 전송을 확인했습니다.
- 앱 시험음은 PC 수신기에서 100ms PCM 10프레임 수신을 확인했습니다. 주행 제한·상태 인식 불가 화면도 디버그 모드로 전환해 확인했습니다.
- 전체 APK 빌드는 이 실행 환경의 Gradle 생성 클래스 단계 오류로 완료하지 못했습니다. Android Studio에서 전체 빌드와 설치 결과를 별도로 확인해야 합니다.
- **실행 가능한 제출용 release APK는 아직 포함하지 않았습니다.** 현재 PC 로컬 연결, HTTP 테스트 설정과 일부 진단 기능은 debug 빌드에 한정됩니다. release 변형을 그대로 시연용 APK로 안내하지 않습니다.
- 차량번호 자동 인증·수정 방지 연동, 실제 주변 차량 검색, 인터넷 중계 서비스, 제조사 차량 계정 연동은 후속 개발 항목입니다.
- 매니페스트에 Distraction Optimization 선언이 없습니다. 앱 내부 제한 UI 구현은 실차 주행 중 앱 사용 승인이나 카테고리 심사 완료를 의미하지 않습니다.
- 화면을 벗어나면 워키토키와 통신을 종료하고 자동으로 마이크 송신을 재개하지 않습니다.

## 폴더

- `app/`: Android 앱, 리소스와 단위 테스트
- `relay/`: 로컬 중계 서버, PC 테스트 화면, 서버·PCM 테스트
- `gradle/`, `gradlew`, `gradlew.bat`: 재현용 Gradle Wrapper와 버전 설정
- `docs/`: 시연 영상과 검증 범위
- `apk/`: APK 준비 상태 안내. 현재 실행용 APK 파일 없음

## 공식 참고 자료

- [Pleos 공식 개발 문서](https://document.pleos.ai/)
- [Pleos 주행 제한 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [Android CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)
- [공모전 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
