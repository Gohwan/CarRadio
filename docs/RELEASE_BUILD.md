# 공모전 제출용 release 빌드

이 release 변형은 Pleos Connect 에뮬레이터와 PC의 로컬 중계 서버로 실행하는 시제품입니다. 인터넷 중계 서버나 실제 주변 차량 인증 기능이 포함된 배포 서비스는 아닙니다.

## 빌드

1. Android Studio에서 프로젝트를 열고 Gradle Sync를 완료합니다.
2. 프로젝트 루트에서 `./gradlew assembleRelease`를 실행합니다. Windows에서는 `./gradlew.bat assembleRelease`를 사용합니다.
3. 서명 전 파일은 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다.
4. Android Studio의 **Build → Generate Signed App Bundle or APK → APK**에서 기존 제출용 키를 선택하고 `release` 변형을 서명할 수 있습니다. 키 파일과 암호는 프로젝트 밖에 보관합니다.
5. 명령행으로 서명할 때에는 Android SDK의 `zipalign`을 먼저 적용한 뒤 `apksigner sign`으로 서명하고 `apksigner verify --verbose --print-certs`로 검증합니다.

공식 서명 문서: https://developer.android.com/studio/publish/app-signing

## 변형 설정

| 항목 | 값 |
| --- | --- |
| applicationId | `com.example.carradio.submission` |
| 버전 | `1.0-submission`, versionCode `1` |
| Android 디버깅 | 비활성화 (`debuggable=false`) |
| R8 코드·리소스 최적화 | 비활성화 (`optimization.enable=false`) |
| 로컬 시연 통신 | `LOCAL_DEMO=true` |
| PC 중계 서버 | `node relay/server.mjs` |
| 에뮬레이터 접속 주소 | `http://10.0.2.2:8765` |
| 평문 HTTP 예외 | 에뮬레이터 호스트 주소 `10.0.2.2`만 허용 |
| 주행 상태 | 플랫폼 UX 제한 사용, 디버그 주행 상태 선택 UI 미포함 |

앱 ID에 `.submission`을 붙여 기존 개발용 앱과 함께 설치할 수 있도록 했습니다. 에뮬레이터의 앱 설정과 위치·마이크 권한은 제출용 앱에서 별도로 설정합니다. 신호 송수신에는 PC 중계 서버, 유효한 위치와 테스트 번호 입력, 연결 동의가 필요합니다.

2026-11-15 설치 검증에서 부분 R8 최적화가 적용된 APK가 AndroidX 초기화 중 `IllegalAccessError`로 종료되는 현상을 확인했습니다. 제출 변형의 최적화를 꺼서 다시 빌드합니다. 파일 크기보다 실제 기동 검증을 우선한 설정입니다. 공식 설정 설명: https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/Optimization

기존 디버그 영상의 **주행 테스트** 버튼은 release에 포함하지 않습니다. release에서 주행 제한을 확인할 때에는 Pleos Virtual Console에서 플랫폼의 실제 상태를 변경합니다. 상태를 읽지 못하면 기존 제한 정책에 따라 송신을 중단합니다.

## 제출

공모전 안내에 따라 제출 시 APK 파일명을 `팀명_CarRadio_release.apk`로 지정합니다. APK와 일치하는 소스 코드, 실행 방법, 최종 커밋을 제출합니다. 서명키와 암호 파일은 공개 저장소나 공모전 제출물에 포함하지 않습니다.

공식 제출 안내: https://appdevchallenge.auto-hmg.io/guide/submission
