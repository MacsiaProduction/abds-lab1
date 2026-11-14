.PHONY: run rund services stop clean
.DEFAULT_GOAL = run

# Runs all services in detached mode.
run:
	docker compose --env-file .env.local up -d --build

# Runs all services without detached mode (for debugging).
rund:
	docker compose --env-file .env.local up --build

# Shows all service statuses.
services:
	docker compose ps

# Stops all running services.
stop:
	docker compose down

# Cleans up all resources including volumes.
clean:
	docker compose down -v

.PHONY: gen load setup teardown

gen:
	docker compose --env-file .env.local run --build --rm --no-deps app gen

load:
	docker compose --env-file .env.local run --rm --no-deps app load

setup:
	docker compose --env-file .env.local run --rm --no-deps app setup

teardown:
	docker compose --env-file .env.local run --rm --no-deps app teardown
