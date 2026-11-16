# Patch Radar

DevOps OSS의 공식 패치 뉴스를 수집해 한 화면에서 보여주는 MSA 서비스. 배경과 결정은 `PROJECT_PLAN.md`에 있다.

## 진실의 원천

- `contracts/`(도메인 규칙, 이벤트 스키마, OpenAPI)가 서비스 간 약속이다. 구현은 계약을 따른다.
- 계약 변경은 **architect 에이전트만** 한다. 다른 에이전트는 계약 문제를 발견하면 수정하지 말고 보고한다.

## 규칙

- 작업 범위는 맡은 디렉터리로 한정한다. 다른 서비스 코드는 수정하지 않는다.
- 서비스 간 소통은 Redis Stream 이벤트와 HTTP API로만 한다. 다른 서비스의 DB 스키마는 읽지 않는다.
- 이벤트 소비는 멱등이어야 한다. 키는 `(tool, releaseKey)`.
- 공개 저장소다. DevOpsit 사용 버전, 내부 구조, 토큰을 코드·문서·커밋에 넣지 않는다. `.env`는 수정하거나 출력하지 않는다.
- 완료 보고 전 `make test SERVICE=<이름>`이 통과해야 한다. 실패하면 실패 로그를 그대로 보고한다.
- 테스트는 개발 데이터를 건드리지 않는다: DB는 `patchradar_test`(build.gradle이 지정), Redis Stream 키는 테스트마다 고유값. 테스트가 남긴 행은 테스트가 지운다.
- gradle은 변경이 없으면 테스트를 건너뛴다(UP-TO-DATE). 검증 보고 전에는 `./gradlew test --rerun-tasks`로 실제 실행한다.
- 의존성 추가는 표준 라이브러리나 이미 있는 의존성으로 안 될 때만 한다.

## 명령

| 명령 | 설명 |
|---|---|
| `make infra` | postgres(호스트 15432), redis(6379)만 실행 |
| `make test SERVICE=collector-svc` | 서비스 테스트 (`release-svc`, `web`, 생략 시 전체) |
| `make up` / `make down` | 전체 실행(http://localhost:3000) / 종료 |
| `make lint` | web 린트 |

## 커밋

- 브랜치: `dev`에서 작업, `main`은 PR로만 병합.
- 메시지: `feat(collector): ...`, `fix(release): ...`, `docs: ...`, `chore: ...` 형식, 본문은 한국어.
