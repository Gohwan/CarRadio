# CarRadio 실행 안내

[앱 소개](README.md) · [APK 다운로드](https://github.com/Gohwan/CarRadio/releases/download/v1.0.0-submission.20261115/CarRadio_release.apk) · [다운로드](https://github.com/Gohwan/CarRadio/releases/tag/v1.0.0-submission.20261115)

1. PC에서 `node relay/server.mjs`를 실행합니다.
2. Pleos Connect 에뮬레이터에 APK를 설치합니다.
3. 앱의 위치·마이크 권한을 허용하고 테스트 차량번호와 위치를 설정합니다.
4. **설정 → 개발 테스트 → 동의하고 연결**로 서버에 접속합니다.
5. PC의 [테스트 화면](http://127.0.0.1:8765/test-client)에서 다른 번호로 접속합니다.
6. 같은 채널과 가까운 좌표를 선택하고 양쪽에서 수신을 허용합니다.

에뮬레이터에서 PC 서버에 접속하는 주소는 `http://10.0.2.2:8765`입니다.

[빌드 방법](docs/RELEASE_BUILD.md) · [시연 영상](docs/CarRadio_demo_full.mp4) · [테스트](docs/VALIDATION.md)
