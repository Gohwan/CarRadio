# 제출용 APK 실행 검증

2026-11-15 기존 APK의 시작 직후 종료 오류를 재현하고, R8 부분 최적화를 비활성화한 release APK를 재빌드했다.

| 항목 | 결과 |
| --- | --- |
| 환경 | Pleos Connect 2.1.0 / Android 14, CarRadio_Release_Test, 사용자 10 |
| 앱 ID | com.example.carradio.submission |
| 파일 크기 | 42,489,808 bytes |
| SHA-256 | `531b0ba0a23bee69e5a3c7074061275293bc7d180d966493d6df464b3a9fd84f` |
| 서명 및 APK 정렬 | 통과, 기존 제출용 인증서 유지 |
| 디버깅 | 비활성화 |
| 기존 오류 | AndroidX 초기화 중 Kotlin 클래스 접근 IllegalAccessError |
| 변경 | release optimization.enable=false |
| 설치 | 설치된 base.apk의 SHA-256이 제출 파일과 일치 |
| 실제 기동 | 프로세스 유지, Activity RESUMED, reportedDrawn=true, reportedVisible=true |
| 오류 로그 | 검증 중인 프로세스의 AndroidRuntime 치명적 오류 없음 |
| PC 서버 | 127.0.0.1:8765, protocol 3 |
| 에뮬레이터→PC HTTP | 10.0.2.2:8765/health에서 HTTP 200 |
| 기존 Kotlin 단위 테스트 | debug 변형 38개 통과, 이번 빌드에서 UP-TO-DATE 확인 |
| 서버·워키토키·PCM 테스트 | 이번 세션 47개 재실행, 전부 통과 |
| Android Lint | 미실행: 오프라인 캐시에 lint-gradle:32.5.1 없음 |

## 검증 범위

서명된 실제 release APK의 설치와 기동, 화면이 그려졌다는 시스템 보고, 프로세스 유지, 해당 프로세스의 오류 로그와 에뮬레이터에서 PC 서버로 연결되는 HTTP 경로를 확인했다. 시스템의 화면 표시 보고는 화면 내용을 사람이 확인한 결과와 구별한다.

화면 버튼 조작, 실제 발화의 STT·TTS, 마이크 양방향 음질·지연, release 앱과 상대 앱 간 전체 메시지 교환은 이번 세션에서 검증하지 못했다. 화면 접근 승인 시간 초과로 UI 검증을 완료하지 못했다. 기존 시연 영상은 debug 빌드와 시험음·시뮬레이션을 사용한 자료다. release에는 debug 주행 상태 선택 UI가 없다.

관련 이론은 회귀 테스트다. 빌드 설정을 바꾼 뒤 같은 시작 경로를 다시 실행하여 재현된 결함의 발생 여부를 검사했다. 통과한 범위를 전체 기능 정상 동작으로 확대 해석하지 않는다.

공식 근거:
- R8 부분 최적화와 변경 후 동작 검증: https://developer.android.com/topic/performance/app-optimization/optimize-specified-packages
- optimization.enable 설정: https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/Optimization

생성일시: 2026-11-15 22:05:02 KST
