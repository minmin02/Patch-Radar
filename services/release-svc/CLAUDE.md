# release-svc

`release.detected`를 소비해 저장하고, 공개 조회 API와 SSE를 제공한다.

- API: `contracts/openapi/release-api.yaml`을 정확히 구현한다(경로, 파라미터, 응답 형태, 에러 `{code, message}`).
- 이벤트 소비: Redis Stream consumer group `release-svc`. `(tool, releaseKey)` 기준 upsert로 멱등 처리 후 ACK. 처리 실패한 메시지는 ACK하지 않는다.
- DB: `release` 스키마. 마이그레이션은 `src/main/resources/db/migration/V{n}__*.sql`
- 도구 카탈로그(이름, 분류)는 `contracts/domain.md`의 표와 일치시킨다.
- SSE: 새로 저장된 릴리스만 `release` 이벤트로 push, 30초 ping.
- 패키지: `io.patchradar.release` 아래 `api`, `consume`, `store`
- 테스트: 인프라는 `make infra`로 띄운 postgres/redis를 쓴다.
