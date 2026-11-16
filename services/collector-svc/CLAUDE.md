# collector-svc

각 OSS 공식 릴리스 채널을 폴링해 `release.detected` 이벤트를 발행한다.

- 소스 목록과 수집 규칙: `contracts/domain.md`
- 이벤트 형식: `contracts/events/release-detected.schema.json` (Redis Stream `release.detected`, 필드 `payload`에 JSON 문자열)
- 패키지: `io.patchradar.collector` 아래 `source`(소스별 수집기), `publish`(이벤트 발행), `config`
- DB: `collector` 스키마. `fetch_state`(소스별 ETag, 마지막 확인 시각)와 발행 이력만 둔다. 마이그레이션은 `src/main/resources/db/migration/V{n}__*.sql`
- HTTP: Spring `RestClient`. GitHub은 `patchradar.github.token`이 있으면 `Authorization: Bearer` 헤더를 붙인다.
- 테스트: 실제 외부 호출 금지. 피드 샘플을 `src/test/resources/feeds/`에 두고 파싱·필터를 검증한다.
- 새 소스 추가는 `add-source` 스킬을 따른다.
