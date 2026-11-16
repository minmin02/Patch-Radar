# web

한 화면에서 모든 도구의 릴리스를 본다. React + Vite, 운영은 nginx.

- API는 `contracts/openapi/release-api.yaml`만 기준으로 쓴다. 개발 중 `/api`는 vite가 `localhost:8080`으로 프록시한다.
- release-svc가 준비되기 전에는 계약 예시대로 목 데이터를 만들어 개발한다.
- 화면: 도구 필터(다중 선택), 기간·검색·보안 필터, 최신순 목록, 상세(본문 Markdown/HTML 렌더링, 원문 링크), `/api/releases/stream`(SSE)으로 새 항목 자동 반영
- 본문 HTML은 그대로 넣지 말고 반드시 sanitize한다(외부 데이터).
- 의존성은 최소로: UI 프레임워크 없이 CSS로 충분하면 추가하지 않는다.
- 검증: `make test SERVICE=web`(빌드), `make lint`
