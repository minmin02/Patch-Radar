# 공통 명령. 사람과 에이전트 모두 이 명령만 쓴다.
#   make test SERVICE=collector-svc   (SERVICE 생략 시 전체)
JAVA_SERVICES := collector-svc release-svc
SERVICE ?= all

.PHONY: infra up down logs test lint

infra:            ## postgres, redis만 실행 (테스트·로컬 개발용)
	docker compose up -d --wait postgres redis
	@docker compose exec -T postgres psql -U patchradar -tAc "select 1 from pg_database where datname='patchradar_test'" | grep -q 1 \
		|| docker compose exec -T postgres createdb -U patchradar patchradar_test

up:               ## 전체 빌드 후 실행 → http://localhost:3000
	docker compose up -d --build --wait

down:
	docker compose down

logs:
	docker compose logs -f $(if $(filter-out all,$(SERVICE)),$(SERVICE))

test: infra
ifeq ($(SERVICE),all)
	@for s in $(JAVA_SERVICES); do (cd services/$$s && ./gradlew test) || exit 1; done
	cd web && npm ci && npm run build
else ifeq ($(SERVICE),web)
	cd web && npm ci && npm run build
else
	cd services/$(SERVICE) && ./gradlew test
endif

lint:
	cd web && npm run lint
