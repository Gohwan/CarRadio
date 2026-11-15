# CarRadio

Pleos Connect용 음성·메시지 앱입니다. 같은 채널에 참여한 기기와 워키토키 음성, 도로 위험 신호, 도움 요청을 주고받습니다.

현재는 Pleos 에뮬레이터와 PC의 로컬 중계 서버를 연결해 사용합니다.

[APK 다운로드](https://github.com/Gohwan/CarRadio/releases/download/v1.0.0-submission.20261115/CarRadio_release.apk) · [다운로드](https://github.com/Gohwan/CarRadio/releases/tag/v1.0.0-submission.20261115) · [시연 영상](docs/CarRadio_demo_full.mp4)

## 기능

| 기능 | 동작 |
| --- | --- |
| 채널과 수신 범위 | 일반 대화·도로 위험·도움 요청 채널, 반경 1·3·5km |
| 위험·도움 신호 | 상황 선택 후 전송, 받은 메시지에 원문을 인용해 답장 |
| 워키토키 | 한 채널에서 한 명씩 발언, 최대 15초, 발언 후 2초 대기 |
| 음성 메시지 | 최대 15초 녹음, 전송 전 미리듣기, 받은 음성 재생 |
| 음성 입력·읽기 | Pleos STT·TTS 연동 |
| 발신자와 거리 | 입력한 차량번호 뒤 4자리, 좌표로 계산한 거리 표시 |
| 주행 상태 | 플랫폼의 UX 제한과 연동해 송신 가능 여부 제어 |

서버는 채널, 양쪽 기기의 수신 범위와 수신 허용 상태를 확인해 메시지를 전달합니다. 수신함은 최근 10건을 보관하며 메시지는 약 2분 뒤 만료됩니다.

## 실행

### 중계 서버

Node.js가 설치된 PC에서 프로젝트 폴더를 열고 실행합니다.

```sh
node relay/server.mjs
```

Windows에서는 `relay/Start-Relay.ps1`도 사용할 수 있습니다.

| 접속 위치 | 주소 |
| --- | --- |
| PC 테스트 화면 | http://127.0.0.1:8765/test-client |
| 서버 상태 | http://127.0.0.1:8765/health |
| Android 에뮬레이터 | http://10.0.2.2:8765 |

### 앱

1. Pleos Connect 에뮬레이터에 APK를 설치합니다.
2. 위치·마이크 권한을 허용하고 설정에서 테스트 차량번호와 위치를 입력합니다.
3. **설정 → 개발 테스트 → 동의하고 연결**로 중계 서버에 접속합니다.
4. PC 테스트 화면에서 다른 번호로 접속하고 같은 채널과 가까운 좌표를 설정합니다.
5. 양쪽 기기에서 수신에 동의한 뒤 메시지와 워키토키를 사용합니다.

## 개발 환경

| 항목 | 설정 |
| --- | --- |
| 앱 | Kotlin, Jetpack Compose |
| 실행 환경 | Pleos Connect 2.1.0, Android 14 |
| Android SDK | compileSdk·targetSdk 37, minSdk 28 |
| 빌드 | Gradle 9.6.0, AGP 9.5.1, JDK toolchain 25 |
| 중계 서버 | Node.js, 외부 npm 패키지 없음 |

소스에서 실행하려면 Android Studio로 프로젝트를 열고 Gradle Sync 후 `app` 모듈을 실행합니다. SDK 의존성을 처음 받으려면 네트워크가 필요합니다.

[APK 빌드 방법](docs/RELEASE_BUILD.md) · [테스트](docs/VALIDATION.md)

## 사용 범위

- 로컬 중계 서버가 필요합니다. 인터넷 중계와 실제 주변 차량 검색은 구현 범위에 포함되지 않습니다.
- 차량번호는 테스트 입력값이며 차량 인증 정보가 아닙니다.
- 수신 확인은 앱의 처리 응답이며 사람이 읽었다는 뜻은 아닙니다.
- 앱 내부 주행 제한은 실차 사용 승인과 별개입니다.
- 시연 영상은 debug 빌드와 시험음·주행 상태 시뮬레이션을 사용합니다.

## 참고

- [Pleos 개발 문서](https://document.pleos.ai/)
- [CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)
