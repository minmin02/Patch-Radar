---
name: add-source
description: Patch Radar에 새 OSS 릴리스 소스(도구)를 추가하는 절차. "소스 추가", "도구 추가", "OO도 수집해줘" 같은 요청에 사용.
---
# 새 소스 추가

1. **계약** (architect): `contracts/domain.md` 도구 표에 id·이름·분류·소스·위치 추가, 이벤트 스키마와 OpenAPI의 tool enum에 id 추가.
2. **피드 확인**: 실제 URL을 한 번 호출해 형식(GitHub Releases / RSS / Atom)과 제목·태그 패턴을 확인한다. 사전 릴리스와 잡음 태그 예시를 기록한다.
3. **collector-svc** (collector-dev): 소스 설정 추가. 파서가 새 형식이면 수집기 추가. 받은 피드 일부를 `src/test/resources/feeds/<id>.*`에 저장하고 필터 테스트 작성.
4. **release-svc** (release-dev): 도구 카탈로그에 이름·분류 추가.
5. **web**: 도구 목록은 `/api/tools`에서 오므로 보통 수정 불필요. 확인만 한다.
6. `make test` 전체 통과 → `make up` 후 화면에서 새 도구 릴리스가 보이는지 확인.
