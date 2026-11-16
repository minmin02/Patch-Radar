# 도메인 규칙

모든 서비스와 에이전트가 따르는 공통 정의. 바꿀 때는 architect가 이 문서와 스키마를 함께 고친다.

## 도구 (Tool)

`id`는 이벤트, API, DB에서 공통으로 쓴다. 도구 목록이 바뀌면 collector-svc의 소스 설정과 release-svc의 도구 카탈로그를 함께 고친다.

| id | 이름 | 분류 | 소스 | 위치 |
|---|---|---|---|---|
| `gitlab` | GitLab | SCM | rss | `https://about.gitlab.com/all-releases.xml` |
| `gitea` | Gitea | SCM | github | `go-gitea/gitea` |
| `jenkins` | Jenkins LTS | CI | rss | `https://www.jenkins.io/changelog-stable/rss.xml` |
| `sonarqube` | SonarQube | 품질 분석 | github | `SonarSource/sonarqube` |
| `harbor` | Harbor | 레지스트리 | github | `goharbor/harbor` |
| `argocd` | ArgoCD | 배포 | github | `argoproj/argo-cd` |
| `dependency-track` | Dependency-Track | 보안 | github | `DependencyTrack/dependency-track` |
| `k3s` | k3s | 인프라 | github | `k3s-io/k3s` |
| `kubernetes` | Kubernetes | 인프라 | github | `kubernetes/kubernetes` |

## 릴리스 (Release)

| 필드 | 설명 |
|---|---|
| `tool` | 도구 id |
| `releaseKey` | 소스 안에서 릴리스를 구분하는 값. github는 `tag_name`, rss는 항목 `link` |
| `version` | 제목이나 태그에서 뽑은 대표 버전(접두사 `v` 제거). 뽑을 수 없으면 `null` |
| `title` | 원문 제목 |
| `url` | 원문 릴리스 노트 링크 |
| `publishedAt` | 발행 시각 (UTC, ISO-8601) |
| `body` / `bodyFormat` | 본문과 형식(`markdown`, `html`, `text`). 없으면 `null` |
| `security` | 보안 관련 여부 (아래 규칙) |
| `source` | `github`, `rss` |

- **멱등 키**: `(tool, releaseKey)`. 같은 키의 이벤트가 다시 와도 결과가 바뀌지 않는다(내용이 바뀌었으면 갱신만 한다).
- **대표 버전**: GitLab처럼 한 항목에 여러 버전이 들어 있으면(`19.5.5, 19.4.7`) 가장 높은 버전을 쓴다.

## 수집 규칙 (collector-svc)

- **정식 버전만 수집한다.**
  - github 소스: `prerelease=true`나 `draft=true`인 릴리스는 제외한다.
  - 태그가 `^v?\d+(\.\d+)+(\+k3s\d+)?$`에 맞지 않으면 제외한다. 예: `stable`, `gitops-engine/v3.6.1`, `v1.37.2-rc1+k3s1`
- **rss 소스**:
  - `jenkins`는 제목이 `Jenkins <버전>`인 항목만 수집한다.
  - `gitlab`은 모든 항목을 수집한다.
- **보안 판정**: 제목이나 본문에 `security`, `critical`, `CVE-`가 있으면 `security=true`로 표시한다(대소문자 무시).
- **폴링**:
  - 기본 10분 주기로 돌고, `ETag`나 `Last-Modified`로 조건부 요청을 보낸다.
  - github는 `GITHUB_TOKEN`이 있으면 인증 헤더를 붙인다.
- **첫 수집**: 소스마다 최근 릴리스(github 한 페이지 기준 30건)를 모두 발행한다. 화면을 처음부터 채우기 위해서다.
