# APK 테스트 결과

| 항목 | 값 |
| --- | --- |
| APK | `CarRadio_release.apk` |
| 파일 크기 | 42,489,808 bytes |
| SHA-256 | `531b0ba0a23bee69e5a3c7074061275293bc7d180d966493d6df464b3a9fd84f` |
| 앱 ID | `com.example.carradio.submission` |
| 버전 | `1.0-submission` |
| 환경 | Pleos Connect 2.1.0, Android 14 |
| 테스트 일자 | 2026-11-15 |

| 항목 | 결과 |
| --- | --- |
| APK 서명·정렬 | 통과 |
| 설치 | 설치된 APK와 배포 파일의 해시 일치 |
| 기동 | Activity RESUMED, reportedDrawn·reportedVisible=true |
| 프로세스 | 테스트 구간 동안 유지, 해당 프로세스의 치명적 오류 없음 |
| 에뮬레이터 → PC 서버 | `10.0.2.2:8765/health`에서 HTTP 200 |
| Kotlin 단위 테스트 | debug 변형 38개 통과 |
| 서버·워키토키·PCM 테스트 | 47개 통과 |
| Android Lint | 미실행 — 오프라인 캐시에 `lint-gradle:32.5.1` 없음 |

release의 화면 버튼 조작, 실제 발화의 STT·TTS, 마이크 양방향 음질·지연, 앱 간 전체 메시지 교환은 테스트 범위에 포함되지 않습니다. 화면 표시 결과는 시스템 보고를 기준으로 합니다.

[상세 데이터](RELEASE_VALIDATION.json) · [테스트 명령](VALIDATION.md)
