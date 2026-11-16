# Patch Radar

DevOps OSS(GitLab, Jenkins, ArgoCD, Harbor, SonarQube 등)의 공식 패치 뉴스를 한 화면에서 보는 서비스.
설계와 로드맵은 [PROJECT_PLAN.md](PROJECT_PLAN.md), 서비스 간 계약은 [contracts/](contracts/).

## 구성

| 경로 | 역할 | 포트 |
|---|---|---|
| `services/collector-svc` | 릴리스 수집 → `release.detected` 발행 (Spring Boot) | 8081 |
| `services/release-svc` | 저장·조회 API·SSE (Spring Boot) | 8080 |
| `web` | 한 화면 UI (React + Vite, nginx) | 3000 |
| PostgreSQL / Redis | 서비스별 스키마 / Streams | 15432 / 6379 |

## 실행

필요: JDK 21, Node 22+, Docker

```bash
cp .env.example .env     # GITHUB_TOKEN 입력 (없으면 시간당 60회 제한)
make up                  # 전체 실행 → http://localhost:3000
make test                # 전체 테스트 (SERVICE=collector-svc 처럼 개별 가능)
make down
```
