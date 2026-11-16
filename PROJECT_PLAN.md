# Patch Radar: DevOps OSS 패치 뉴스 통합 조회 서비스 (기획안)

> 최초 작성 2026-11-13 · 개정 2026-11-16 · 상태: 기획 (개발 미착수)
> 가칭 "Patch Radar"

---

## 1. 목표와 범위

DevOpsit이 연동하는 외부 OSS(GitLab, Jenkins, ArgoCD 등)의 **공식 패치 뉴스(릴리스 노트)**를 수집해 **한 화면에서** 볼 수 있게 한다. 유지보수 담당자가 도구마다 릴리스 노트를 따로 찾아다니지 않아도 되게 하는 것이 목적이다.

| MVP 포함 | 확장 과제 (나중에) | 제외 |
|---|---|---|
| 8개 OSS 릴리스 수집 (5~10분 폴링) | LLM 분석 파이프라인 (8절). 이 중 온디맨드 핵심요약은 구현됨(ADR-004) | 팀즈 알림 |
| 한 화면 목록: 도구·기간 필터, 검색, 원문 링크 | 설치된 도구의 버전 비교 (내부 전용) | 로그인 |
| 새 릴리스 화면 자동 반영 (SSE) | | DevOpsit 내부 정보 노출 |

---

## 2. 지금까지의 진행 (세션 요약)

- **프로토타입**: `find_version.py`(Python 표준 라이브러리만 사용, 피드 폴링 → 새 항목 감지)와 `test_find_version.py`(통과). 수집 로직은 collector-svc로 옮길 예정.
- **피드 검증**: 8개 소스 모두 인증 없이 수집 가능. 단 gitlab.com API는 403 → GitLab 공식 RSS로 대체.
- **발견 사례**: 프로토타입 실행 중 운영 환경에 해당하는 보안 패치를 피드에서 바로 발견했다. 서비스가 필요한 이유를 보여준 첫 사례 (구체 버전은 내부 정보라 기록하지 않음).
- **방향 변화**: 팀즈 알림 스크립트 → LLM 분석 서비스 구상 → **LLM은 확장 과제로 미루고, 패치 뉴스 통합 조회를 MVP로 확정** → MSA는 확장 계획을 근거로 채택(ADR-001).
- **개발 방식**: Claude Code 멀티 에이전트 구조로 진행(6절).

---

## 3. 데이터 소스: 각 OSS의 공식 릴리스 채널

패치 뉴스는 설치된 도구의 API(예: GitLab `/api/v4/version`)로는 얻을 수 없다. 그 API가 알려주는 건 현재 설치된 버전뿐이다. 새 버전과 변경 내용은 **각 프로젝트가 공식적으로 공개하는 릴리스 채널**에만 있다. 대부분의 OSS는 소스 저장소가 있는 GitHub의 Releases에 릴리스 노트를 게시한다.

| 분류 | OSS | 공식 릴리스 채널 | 수집 방식 |
|---|---|---|---|
| SCM | GitLab | `https://about.gitlab.com/all-releases.xml` (패치), `releases.xml` (월간) | RSS |
| SCM | Gitea | GitHub `go-gitea/gitea` Releases | GitHub REST API |
| CI | Jenkins | `https://www.jenkins.io/changelog-stable/rss.xml` (LTS) | RSS |
| 품질 분석 | SonarQube | GitHub `SonarSource/sonarqube` Releases | GitHub REST API |
| 레지스트리 | Harbor | GitHub `goharbor/harbor` Releases | GitHub REST API |
| 배포 | ArgoCD | GitHub `argoproj/argo-cd` Releases | GitHub REST API |
| 보안 | Dependency-Track | GitHub `DependencyTrack/dependency-track` Releases | GitHub REST API |
| 인프라 | k3s / Kubernetes | GitHub `k3s-io/k3s`, `kubernetes/kubernetes` Releases | GitHub REST API |

**수집 규칙**
- GitHub REST API: `GET /repos/{owner}/{repo}/releases`로 본문(Markdown)까지 함께 받는다. 인증 없이 시간당 60회, 토큰을 쓰면 5000회. `ETag` 조건부 요청을 쓴다.
- 정식 버전만 수집한다. `rc`, `alpha`, `beta`는 제외하고, ArgoCD의 `stable`과 `gitops-engine/*` 태그도 제외한다. k3s의 `+k3s1` 접미사는 허용한다.
- 멱등 키는 `(tool, version)`. GitLab처럼 한 항목에 여러 버전이 들어 있는 경우는 항목 URL을 키로 쓴다.

---

## 4. 아키텍처 결정 기록

### ADR-001: MSA 채택 (확장 계획 기반)

- **상황**: MVP 범위(개발자 1명, 소스 8개, 낮은 트래픽)만 보면 모놀리스가 적합하다. 업계 기준도 같다(Martin Fowler, "MonolithFirst", "Microservice Premium"; Sam Newman, 『Monolith to Microservices』).
- **결정**: 그럼에도 **최소 단위의 MSA**를 채택한다.
- **근거**: 확장 과제인 **LLM 분석 서비스**는 기존 기능과 성격이 다르다.
  - 처리가 느리고 외부 API 비용이 들며 실패가 잦다 → 장애 격리가 필요하다.
  - Python AI 생태계를 써야 한다 → 기술 스택을 분리해야 한다.
  - 분석량에 따라 따로 확장해야 한다 → 독립 확장이 필요하다.

  이 서비스를 **기존 서비스를 고치지 않고 이벤트 구독만으로 추가**할 수 있게 지금부터 서비스 경계와 이벤트 계약을 만들어 둔다.
- **보조 근거**: 수집(스케줄에 따라 외부로 요청)과 조회(사용자 요청에 응답)는 실패 양상과 배포 주기가 다르다.
- **감수하는 비용**: 운영 복잡도(서비스 간 통신, 이벤트 정합성, 배포 단위 증가). 이를 줄이려고 서비스 수를 최소로 유지한다.
- **검증 시점**: LLM 분석 서비스를 붙일 때 기존 서비스의 코드 변경이 0에 가까우면 이 결정이 맞았다고 본다.

### ADR-002: 메시지 브로커는 Redis Streams

- Kafka는 1인 운영과 소형 인프라에 무겁다. Redis Streams로도 Consumer Group, 재처리, 영속성이 된다.
- 처리량이 커지면 Kafka로 바꾼다. 이벤트 계약(JSON Schema)이 브로커와 독립적이므로 교체 범위가 작다.

### ADR-003: API Gateway는 MVP에서 제외

- 외부 진입점이 release-svc 하나뿐이므로 Ingress로 충분하다. 서비스가 늘어나면 Spring Cloud Gateway를 도입한다.

### ADR-004: 요약 기능을 별도 summary-svc(Python)로 추가

- **상황**: 릴리스 노트가 길어 상세 화면에서 핵심만 먼저 보고 싶다는 요구가 생겼다. LLM으로 한국어 핵심요약을 만들어 보여 준다.
- **결정**: 새 서비스 **summary-svc**를 추가한다.
  - Python + 공식 `anthropic` SDK.
  - 릴리스는 release-svc의 **공개 API로만** 조회한다. release 스키마를 직접 읽지 않는다.
  - 결과는 자체 `summary` 스키마에 캐시한다.
  - 공개 경로 `/api/releases/{id}/summary`를 Ingress 경로 라우팅으로 summary-svc에 보낸다(ADR-003 유지, 게이트웨이 도입 없음).
- **근거**: ADR-001이 내세운 확장 근거를 실제로 검증한다.
  - 장애 격리: LLM 지연·실패가 summary-svc 안에서 끝난다. 목록·상세 조회 API와 SSE는 영향을 받지 않는다.
  - 기술 스택 분리: LLM 연동은 Python 생태계로, 기존 Spring Boot 서비스는 그대로 둔다.
  - 비용 보호를 한 곳에서: 캐시, 생성 횟수 제한, 동시 요청 합치기를 summary-svc 하나에서 관리한다.
- **감수하는 비용**: 운영할 서비스가 하나 늘어난다(이미지, 배포, 관측). LLM 호출 비용이 든다(캐시와 분당 한도로 상한을 둔다).
- **검증**: collector-svc·release-svc 코드 변경 0으로 붙으면 이 결정과 ADR-001이 맞았다고 본다.

---

## 5. 시스템 아키텍처

```
 각 OSS 공식 릴리스 채널 (GitHub Releases · 공식 RSS)
                 │ 5~10분 폴링 (ETag)
        ┌────────▼─────────┐   release.detected   ┌──────────────────────────┐
        │ collector-svc    ├─────────────────────►│ release-svc              │
        │ (Spring Boot)    │   Redis Streams      │ (Spring Boot)            │
        │ 폴링·파싱·중복제거 │                      │ 저장·조회·검색 API, SSE    │
        └────────┬─────────┘                      └──────────┬───────────────┘
           [collector 스키마]                        [release 스키마]   │ REST / SSE
                       PostgreSQL (서비스별 스키마 분리)                 ▼
                                                              web (한 화면)
                                                                   │ 요약 요청 (/api/releases/{id}/summary)
                                                        ┌──────────▼───────────────┐
         release-svc 공개 API ◄── GET /api/releases/{id} ┤ summary-svc :8082        ├──► Claude API
                                                        │ (Python) 요약·캐시·한도    │
                                                        └──────────────────────────┘
                                                              [summary 스키마]
                 ┆ 확장: analysis-svc(Python, LLM)가 release.detected를 구독
                 ┆       → 분석 결과를 release.analyzed로 발행 → release-svc가 화면에 반영
```

| 서비스 | 책임 | 데이터 |
|---|---|---|
| collector-svc | 소스별 스케줄 폴링, 버전 파싱·필터, 중복 제거, 본문 수집, `release.detected` 발행 | `collector.source`, `collector.fetch_state`(ETag, 마지막 확인 시각) |
| release-svc | 이벤트 소비 → 멱등 저장, 목록·필터·검색·상세 REST, 새 릴리스 SSE | `release.release` |
| summary-svc (포트 8082) | 온디맨드 핵심요약: release-svc 공개 API로 릴리스 조회 → Claude API로 한국어 요약 → 캐시, 생성 횟수 제한 | `summary` 스키마 |
| web | 한 화면: 도구 탭·필터, 타임라인 목록, 상세(본문 Markdown 렌더링, 핵심요약), 실시간 반영 | 없음 |

**규칙**
- 다른 서비스의 DB 스키마를 직접 읽지 않는다. 서비스 간 소통은 이벤트와 API로만 한다.
- 이벤트를 받는 쪽은 멱등하게 처리한다. 같은 이벤트가 두 번 와도 결과가 같다.
- collector가 실패해도 release-svc와 web은 이미 저장된 데이터로 정상 동작한다.

### 계약 (`contracts/`)

```json
// events/release-detected.json (예시 페이로드)
{
  "eventId": "uuid",
  "tool": "argocd",
  "version": "v3.6.1",
  "title": "v3.6.1",
  "url": "https://github.com/argoproj/argo-cd/releases/tag/v3.6.1",
  "publishedAt": "2026-11-10T12:00:00Z",
  "body": "## What's Changed ...",
  "source": "github"
}
```

| release-svc API | 설명 |
|---|---|
| `GET /api/releases?tool=&from=&to=&q=&page=` | 목록, 필터, 검색 |
| `GET /api/releases/{id}` | 상세 (본문 포함) |
| `GET /api/tools` | 도구 목록과 도구별 최신 버전 |
| `GET /api/releases/stream` | SSE: 새 릴리스 push |
| `GET`·`POST /api/releases/{id}/summary` | 핵심요약 조회·생성 (summary-svc가 처리) |

### 기술 스택

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot (팀 스택과 같음), JDK 21 |
| 프론트 | React + Vite (화면 하나, SSR 불필요) |
| 데이터 | PostgreSQL, Redis (Streams) |
| 로컬 | docker compose, Makefile |
| 배포 (MVP 이후) | k3s + ArgoCD + GitHub Actions/GHCR, Terraform (9절) |

---

## 6. Claude Code 멀티 에이전트 개발 구조

### 원칙

- **계약 우선**: `contracts/`가 진실의 원천이다. 계약이 확정돼야 에이전트들이 충돌 없이 병렬로 작업할 수 있다.
- **에이전트 하나 = 디렉터리 하나**: 담당 범위가 겹치지 않게 나눈다.
- **병렬 작업은 git worktree로 격리**하고, 리뷰를 거쳐 병합한다.
- **뼈대는 단일 세션으로 만든다**: 처음부터 병렬로 시작하면 기준이 서로 어긋난다.
- **에이전트는 3~5개로 시작**한다. 늘릴수록 조율 비용과 토큰 사용이 커진다.

### 구조

```
          나 + 메인 Claude 세션 (오케스트레이터 = 메타 에이전트)
          계획(plan mode) · 작업 분해 · 결과 통합 · 최종 판단
               │
  ┌────────────┼───────────────┬───────────────┬──────────────┐
architect   collector-dev    release-dev      web-dev        reviewer
contracts/  services/        services/        web/           읽기 전용
docs/adr/   collector-svc/   release-svc/                    계약 준수·테스트 검증
```

| 에이전트 | 범위 | 모델 | 도구 |
|---|---|---|---|
| architect | `contracts/`, `docs/adr/`. 계약 변경은 이 에이전트만 | 상위 | Read, Edit, Write |
| collector-dev | `services/collector-svc/` | 경량 | Read, Edit, Write, Bash |
| release-dev | `services/release-svc/` | 경량 | Read, Edit, Write, Bash |
| web-dev | `web/` (계약 기반 목 API로 먼저 개발) | 경량 | Read, Edit, Write, Bash |
| reviewer | 전체 읽기 전용, 테스트 실행 | 상위 | Read, Grep, Glob, Bash |
| summary-dev | `services/summary-svc/` (Python, Claude API). 테스트에서 실제 API 호출 금지 | 경량 | Read, Edit, Write, Bash |

### 설정 파일

```
.claude/
├─ agents/            # 위 5개 에이전트 정의 (frontmatter: name, description, tools, model)
├─ skills/
│  └─ add-source/     # 새 OSS 소스 추가 절차: 파서 → 테스트용 피드 → 등록 → 테스트
└─ settings.json      # 허용 명령(make, ./gradlew, npm), hooks(수정 후 포맷·린트)
CLAUDE.md             # 공통 규칙: 계약 준수, 범위 제한, 완료 전 테스트, 커밋 형식, 공통 명령
services/*/CLAUDE.md  # 서비스별 책임, 패키지 구조, 테스트 방법
```

**에이전트 정의 예시** (`.claude/agents/collector-dev.md`)

```markdown
---
name: collector-dev
description: services/collector-svc 구현 담당. 소스 폴링, 파싱, 이벤트 발행 작업에 사용.
tools: Read, Edit, Write, Bash, Grep, Glob
model: sonnet
---
너는 collector-svc 담당 개발자다.
- contracts/events/release-detected.json 을 정확히 따른다. 계약 변경이 필요하면 수정하지 말고 보고한다.
- services/collector-svc/ 밖의 파일은 수정하지 않는다.
- 완료 전 make test SERVICE=collector-svc 통과. 변경 파일과 테스트 결과를 요약 보고.
```

### 작업 루프

1. 오케스트레이터가 plan mode로 작업을 "서비스 × 기능" 단위로 쪼갠다.
2. 구현 에이전트를 병렬로 실행한다(각각 worktree).
3. reviewer가 계약 준수와 테스트를 검증한다.
4. `make up`으로 전체 흐름을 테스트한다: 수집 → 이벤트 → 저장 → 화면.
5. 병합하고 회고한다. 반복된 실수는 `CLAUDE.md`나 에이전트 정의에 규칙으로 추가한다.

---

## 7. 준비 단계와 로드맵

### 준비 (개발 착수 전, 약 2~3일)

| 단계 | 산출물 |
|---|---|
| 1. 범위·스택 확정 | 이 문서 1·4·5절 확정 |
| 2. 계약 설계 | `contracts/openapi/release-api.yaml`, `contracts/events/release-detected.json`, `contracts/domain.md` |
| 3. 저장소·로컬 환경 | 모노레포 구조, `docker-compose.yml`(postgres, redis), `Makefile`(up/test/lint) |
| 4. Claude Code 설정 | 루트·서비스별 `CLAUDE.md`, `settings.json`, hooks, `add-source` skill |
| 5. 에이전트 정의 | `.claude/agents/` 5개 |

### 개발

| 마일스톤 | 산출물 | 완료 기준 |
|---|---|---|
| M0 뼈대 (단일 세션) | 서비스 빈 프로젝트, compose, 계약, CI 테스트 | `make up`으로 전체가 뜸 |
| M1 수집 | collector-svc: 8개 소스, ETag, 필터, 이벤트 발행 | Redis Stream에 이벤트가 쌓임 |
| M2 조회 | release-svc: 저장·API·SSE / web: 한 화면 | 브라우저에서 8개 도구 릴리스 조회·필터 |
| M3 배포 | k3s, ArgoCD, GitHub Actions, TLS | `git push`만으로 공개 URL에 배포 |
| M4 확장 | analysis-svc (8절) | 기존 서비스 수정 없이 분석 결과가 화면에 표시 → ADR-001 검증 |

M1과 M2는 계약을 기준으로 병렬 진행한다(collector-dev, release-dev, web-dev).

---

## 8. 확장 과제: LLM 분석 파이프라인 (요약)

- **구현됨**: 온디맨드 한국어 핵심요약(summary-svc, ADR-004). 사용자가 요청할 때만 생성하고 캐시한다. 규칙은 `contracts/domain.md` "요약 생성".
- **나머지는 확장 과제**: 아래 파이프라인은 아직 구현하지 않았다.
- **analysis-svc** (Python): `release.detected` 구독 → 분석 → `release.analyzed` 발행 → release-svc가 화면에 반영.
- **파이프라인 후보**:
  - 섹션 단위 청킹과 임베딩(pgvector)
  - 분류 → 구조화 추출(breaking, deprecation, CVE) → 외부 도구 보강(OSV/NVD, endoflife.date) → 한국어 요약 → 근거 검증
  - 하이브리드 검색 RAG와 **업그레이드 경로 리포트**("v3.4.0 → v3.6.1" 사이의 변경 모음)
  - 평가 세트와 관측(Langfuse 등)
- **원칙**: LLM에는 공개 릴리스 노트만 보낸다. 요청 시 분석 기능에는 요청 횟수 제한을 둔다.

---

## 9. 인프라 (MVP 이후, 포트폴리오용)

```
GitHub Actions: 테스트 → 이미지 빌드 → Trivy 스캔 → GHCR → 매니페스트 태그 갱신
        │ GitOps
클라우드 VM (Terraform) └─ k3s
     ├─ ArgoCD (App of Apps)
     ├─ Traefik Ingress + cert-manager (TLS)
     ├─ Sealed Secrets (GitHub 토큰, DB 비밀번호)
     ├─ collector-svc · release-svc · web
     ├─ PostgreSQL · Redis (StatefulSet + 백업 CronJob)
     └─ Prometheus · Grafana · Loki (경량 관측)
Cloudflare DNS
```

- 관리형 Kubernetes(EKS/GKE)는 1인 포트폴리오에 비싸다. k3s는 DevOpsit에서 이미 쓰는 기술이다.
- 무료 또는 저가 VM(예: Oracle Cloud 무료 ARM)의 조건은 바뀌므로 착수 시점에 확인한다.

---

## 10. 보안과 노출 원칙

- 공개 서비스에는 **공개 릴리스 정보만** 넣는다. DevOpsit의 사용 버전, 미적용 패치, 클라이언트 구조, 호출 엔드포인트는 넣지 않는다. 사용 버전과 미적용 패치는 공격자에게 표적을 알려주는 정보다.
- ⚠️ 저장소를 공개하기 전에 `find_version.py`의 `SOURCES`에 적힌 **로컬 버전 값을 지운다.**
- GitHub 토큰은 Secret으로만 관리한다. 저장소나 이미지에 넣지 않는다.

---

## 11. 착수 전 결정할 것

- [ ] 서비스 이름
- [ ] 프론트엔드: React + Vite로 확정할지
- [ ] 공개 저장소 여부 (공개 시 10절 조치)
- [ ] 배포할 클라우드와 도메인 (M3 전까지)
- [ ] 사전 릴리스(rc) 표시 여부

## 부록: 모노레포 구조 (안)

```
patch-radar/
├─ CLAUDE.md
├─ .claude/{agents,skills}/, settings.json
├─ contracts/{openapi,events}/, domain.md
├─ services/
│  ├─ collector-svc/   (CLAUDE.md)
│  ├─ release-svc/     (CLAUDE.md)
│  └─ summary-svc/     (CLAUDE.md, Python)
├─ web/                (CLAUDE.md)
├─ deploy/{helm,argocd}/        # M3
├─ infra/terraform/             # M3
├─ docs/adr/
├─ docker-compose.yml
└─ Makefile
```
