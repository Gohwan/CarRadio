# APK 빌드

Android Studio에서 프로젝트를 열고 Gradle Sync를 실행합니다. Android SDK 37과 프로젝트의 JDK toolchain 25 설정이 필요합니다.

## 빌드 명령

Windows:

```powershell
.\gradlew.bat assembleRelease
```

macOS·Linux:

```sh
./gradlew assembleRelease
```

서명 전 APK 경로는 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다.

Android Studio의 **Build → Generate Signed App Bundle or APK → APK**에서 `release` 변형에 서명할 수 있습니다. 명령행에서는 `zipalign` 적용 후 `apksigner sign`으로 서명하고 `apksigner verify`로 확인합니다. 키 파일과 비밀번호는 저장소 밖에 보관합니다.

## release 설정

| 항목 | 값 |
| --- | --- |
| applicationId | `com.example.carradio.submission` |
| versionName | `1.0-submission` |
| versionCode | `1` |
| 디버깅 | 비활성화 |
| R8 최적화 | 비활성화 |
| 로컬 중계 연결 | `LOCAL_DEMO=true` |
| 서버 주소 | `http://10.0.2.2:8765` |
| 평문 HTTP 허용 | `10.0.2.2`로 제한 |

debug와 release는 앱 ID가 달라 함께 설치할 수 있습니다. 위치·마이크 권한과 테스트 번호는 앱별로 설정합니다.

release에는 디버그 주행 상태 선택 UI가 없습니다. 주행 상태 테스트에는 Pleos Virtual Console의 차량 상태를 사용합니다. 주행 상태를 읽을 수 없으면 송신을 제한합니다.

[앱 실행](../README.md#실행) · [테스트](VALIDATION.md) · [Android 앱 서명](https://developer.android.com/studio/publish/app-signing)
