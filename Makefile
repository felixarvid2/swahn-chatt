# FamilyChat developer and operator shortcuts. Run `make` or `make help` for the list.
# Variables:  PYTHON=... (backend venv python)  KEYSTORE=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... (apk-release)
#             BACKUP_DIR=... (backup)  NOTE="Grandma" (invite)  ARCHIVE=path (restore)

BACKEND := backend
ANDROID := android
DEPLOY  := deployment
PYTHON  ?= $(CURDIR)/$(BACKEND)/.venv/bin/python
COMPOSE := cd $(DEPLOY) && docker compose
NOTE    ?= invited with make

.DEFAULT_GOAL := help
.PHONY: help dev test-backend lint test-android e2e apk apk-release docker-build up down logs backup restore invite admin-invite

help: ## Show this list
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[1m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

dev: ## Backend dev server on :8000 (migrations first, data in backend/data-dev)
	$(BACKEND)/scripts/dev-server.sh

test-backend: ## Backend tests (pytest)
	cd $(BACKEND) && $(PYTHON) -m pytest -q

lint: ## Backend lint and format check (ruff)
	cd $(BACKEND) && $(PYTHON) -m ruff check . && $(PYTHON) -m ruff format --check .

test-android: ## Android build with all unit tests and lint (slow the first time)
	cd $(ANDROID) && ./gradlew build

e2e: ## Two-user end-to-end test against a real local backend
	$(ANDROID)/scripts/run-e2e.sh

apk: ## Debug APK -> android/app/build/outputs/apk/debug/
	cd $(ANDROID) && ./gradlew assembleDebug
	@echo "$(CURDIR)/$(ANDROID)/app/build/outputs/apk/debug/app-debug.apk"

apk-release: ## Release APK; signed when KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD are set, else debug-signed
	cd $(ANDROID) && ./gradlew assembleRelease $(if $(KEYSTORE),-Pfamilychat.keystore=$(KEYSTORE) -Pfamilychat.keystorePassword=$(KEYSTORE_PASSWORD) -Pfamilychat.keyAlias=$(KEY_ALIAS) -Pfamilychat.keyPassword=$(KEY_PASSWORD))
	@echo "$(CURDIR)/$(ANDROID)/app/build/outputs/apk/release/app-release.apk"

docker-build: ## Build the backend image
	$(COMPOSE) build backend

up: ## Start the stack (profiles from COMPOSE_PROFILES in deployment/.env)
	$(COMPOSE) up -d --build

down: ## Stop the stack (data volumes are kept)
	$(COMPOSE) down

logs: ## Follow the logs of all running services
	$(COMPOSE) logs -f --tail=100

backup: ## Back up now (BACKUP_DIR, default deployment/backups; see backup.sh for retention and rclone)
	cd $(DEPLOY) && ./backup.sh $(BACKUP_DIR)

restore: ## Restore ARCHIVE=path/to/familychat-backup-*.tar.gz (asks before replacing data)
	@test -n "$(ARCHIVE)" || { echo "usage: make restore ARCHIVE=path/to/familychat-backup-YYYYMMDD-HHMMSS.tar.gz" >&2; exit 2; }
	cd $(DEPLOY) && ./restore.sh "$(abspath $(ARCHIVE))"

invite: ## Create a single-use invitation code (NOTE="Grandma")
	$(COMPOSE) exec -T backend python -m app.cli create-invite --note "$(NOTE)"

admin-invite: ## Create a single-use administrator invitation code
	$(COMPOSE) exec -T backend python -m app.cli create-invite --admin --note "$(NOTE)"
