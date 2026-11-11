CONTAINER_ENGINE ?= docker
COMPOSE ?= $(CONTAINER_ENGINE) compose --env-file .env.local
.PHONY: run rund services stop clean gen load setup teardown test format
.DEFAULT_GOAL = run

run:
	$(COMPOSE) up -d --build --wait --wait-timeout 120
rund:
	$(COMPOSE) up --build
services:
	$(COMPOSE) ps
stop:
	$(COMPOSE) down
clean:
	$(COMPOSE) down -v
gen:
	$(COMPOSE) run --build --rm --no-deps app gen
load:
	$(COMPOSE) run --rm --no-deps app load
setup:
	$(COMPOSE) run --rm --no-deps app setup
teardown:
	$(COMPOSE) run --rm --no-deps app teardown
test:
	./gradlew check
format:
	./gradlew ktlintFormat
