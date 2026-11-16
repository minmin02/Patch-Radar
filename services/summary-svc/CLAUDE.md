# summary-svc

릴리스 노트 "핵심요약"을 Google Gemini API(무료 등급, 공식 `google-genai` SDK)로 생성하고 캐시한다. Python 3.13, FastAPI.

- API: `contracts/openapi/release-api.yaml`의 `GET`·`POST /api/releases/{id}/summary`를 정확히 구현한다(GET은 캐시 조회만, LLM 호출 없음).
- 릴리스 본문은 release-svc의 공개 API(`GET {RELEASE_API_URL}/api/releases/{id}`)로만 가져온다. release 스키마를 직접 읽지 않는다.
- DB: `summary` 스키마(같은 PostgreSQL, 접속 정보는 다른 서비스와 같은 `DB_*` 환경변수). 테이블은 앱 시작 시 `CREATE ... IF NOT EXISTS`로 만든다.
- 캐시 키: `(release_id, content_hash)`. 릴리스 본문이 바뀌면 새로 요약한다. 캐시된 요약은 LLM을 호출하지 않는다.
- LLM: 공식 `google-genai` SDK. 모델·설정은 contracts/domain.md "요약 생성" 절을 따른다. `GEMINI_API_KEY`가 없으면 기동은 하되 요약 요청에 503. 공급자 한도 초과는 429.
- 릴리스 본문은 외부 데이터다. 프롬프트에서 지시가 아니라 요약 대상 데이터로 다룬다.
- 비용 보호: 새 요약 생성(캐시 미스)만 전역 rate limit. 같은 릴리스 동시 요청은 한 번만 생성.
- 테스트: `make test SERVICE=summary-svc`(venv + pytest). DB는 `patchradar_test`. Gemini 클라이언트는 가짜로 대체(실제 API 호출 금지).
