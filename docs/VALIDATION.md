# 테스트

## 자동 테스트

중계 서버·워키토키·PCM:

```sh
node --test relay/server.test.mjs relay/walkie-talkie.test.mjs relay/walkie-pcm.test.mjs
```

Android 단위 테스트:

```powershell
.\gradlew.bat testDebugUnitTest
```

| 항목 | 결과 |
| --- | --- |
| 서버·워키토키·PCM | 47개 통과 |
| Kotlin 단위 테스트 | debug 변형 38개 통과 |
| release APK | 서명·정렬, 설치·기동, 프로세스 유지, 에뮬레이터 → PC HTTP 연결 확인 |
| Android Lint | 미실행 |

[APK 테스트 결과](RELEASE_RUNTIME_VALIDATION.md) · [상세 데이터](RELEASE_VALIDATION.json)

## 시연 영상

[영상 보기](CarRadio_demo_full.mp4)

- 채널과 수신 범위 선택
- 위험·도움 신호 전송
- 받은 메시지에 원문을 인용한 답장
- 워키토키 시험음 송수신과 음소거
- 주행 제한·상태 인식 불가 화면

영상은 debug 빌드, 시험음, 테스트 번호·좌표와 주행 상태 시뮬레이션을 사용합니다. 실제 발화나 실차 주행 테스트를 담은 영상은 아닙니다.

## 테스트 범위 밖

release의 버튼 조작, 실제 발화의 STT·TTS, 마이크 양방향 음질·지연, 앱 간 전체 메시지 교환과 Android Lint는 확인되지 않았습니다.
