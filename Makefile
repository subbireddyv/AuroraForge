# =============================================================================
# AuroraForge – Developer Makefile
#
# Usage:
#   make help          – list all targets
#   make dev-up        – start full local infrastructure
#   make build         – build all service JARs
#   make test          – run unit tests
#   make verify        – run unit + integration tests (requires dev-up)
#   make images        – build OCI images via Buildpacks
#   make deploy-aws    – push images to ECR and apply k8s to EKS
#   make deploy-azure  – push images to ACR and apply k8s to AKS
# =============================================================================

SHELL         := /usr/bin/env bash
.DEFAULT_GOAL := help

VERSION       ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "local")
MVN           := mvn -f services/pom.xml
DOCKER_COMP   := docker compose

AWS_REGION    ?= us-east-1
AWS_ACCOUNT   ?= $(shell aws sts get-caller-identity --query Account --output text 2>/dev/null)
ECR_REGISTRY  := $(AWS_ACCOUNT).dkr.ecr.$(AWS_REGION).amazonaws.com

ACR_REGISTRY  ?= auroraforgeacr.azurecr.io

SERVICES      := auth ingestion processing sync keymgmt

.PHONY: help dev-up dev-down dev-logs \
        build test verify images \
        init-kafka init-vault \
        deploy-aws deploy-azure \
        clean fmt lint arch-test

# ── Help ──────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "AuroraForge – available targets:"
	@echo ""
	@echo "  LOCAL DEVELOPMENT"
	@echo "  make dev-up          Start full docker-compose stack"
	@echo "  make dev-down        Stop and remove containers"
	@echo "  make dev-logs        Tail all container logs"
	@echo "  make init-kafka      Create Kafka topics (run after dev-up)"
	@echo "  make init-vault      Seed Vault transit engine (run after dev-up)"
	@echo ""
	@echo "  BUILD & TEST"
	@echo "  make build           Compile + package all services (skip tests)"
	@echo "  make test            Run unit tests"
	@echo "  make verify          Run unit + integration tests (requires dev-up)"
	@echo "  make arch-test       Run ArchUnit architecture tests only"
	@echo "  make lint            Run Checkstyle + SpotBugs"
	@echo "  make images          Build OCI images via Paketo Buildpacks"
	@echo ""
	@echo "  CLOUD DEPLOYMENT"
	@echo "  make deploy-aws      Push images to ECR + kubectl apply to EKS"
	@echo "  make deploy-azure    Push images to ACR + kubectl apply to AKS"
	@echo ""
	@echo "  OTHER"
	@echo "  make clean           Remove build artifacts"
	@echo "  make fmt             Format code (google-java-format via Maven)"
	@echo ""

# ── Local development ─────────────────────────────────────────────────────────
dev-up:
	@echo ">>> Starting AuroraForge infrastructure stack..."
	$(DOCKER_COMP) up -d
	@echo ">>> Waiting for services to become healthy..."
	@bash -c 'until docker compose ps | grep -v "healthy\|running\|exited (0)" | grep -q "auroraforge"; do sleep 2; done; echo ">>> All containers healthy."' || true
	@echo ">>> Run 'make init-kafka' and 'make init-vault' if this is a fresh start."

dev-down:
	$(DOCKER_COMP) down --remove-orphans

dev-logs:
	$(DOCKER_COMP) logs -f --tail=100

init-kafka:
	@echo ">>> Initialising Kafka topics..."
	bash scripts/init-kafka.sh

init-vault:
	@echo ">>> Seeding Vault transit engine..."
	bash scripts/init-vault.sh

# ── Build & test ──────────────────────────────────────────────────────────────
build:
	$(MVN) clean package -DskipTests -T 1C

test:
	$(MVN) test

verify:
	$(MVN) verify -Pfailsafe

arch-test:
	$(MVN) test -Dtest="*ArchTest" -pl auroraforge-core,auroraforge-ingestion,auroraforge-sync

lint:
	$(MVN) checkstyle:check spotbugs:check

fmt:
	$(MVN) com.spotify.fmt:fmt-maven-plugin:format

# ── OCI images ────────────────────────────────────────────────────────────────
images: build
	@echo ">>> Building OCI images via Paketo Buildpacks (VERSION=$(VERSION))..."
	$(MVN) spring-boot:build-image -DskipTests \
	  -Dspring-boot.build-image.imageName=auroraforge/$${MODULE_NAME}:$(VERSION)
	@for svc in $(SERVICES); do \
	  docker tag auroraforge/auroraforge-$${svc}:1.0.0-SNAPSHOT \
	             auroraforge/auroraforge-$${svc}:$(VERSION); \
	done
	@echo ">>> Images built: $(VERSION)"

# ── AWS deployment ────────────────────────────────────────────────────────────
ecr-login:
	aws ecr get-login-password --region $(AWS_REGION) | \
	  docker login --username AWS --password-stdin $(ECR_REGISTRY)

push-aws: images ecr-login
	@for svc in $(SERVICES); do \
	  echo ">>> Pushing auroraforge-$${svc} to ECR..."; \
	  docker tag auroraforge/auroraforge-$${svc}:$(VERSION) \
	             $(ECR_REGISTRY)/auroraforge-$${svc}:$(VERSION); \
	  docker push $(ECR_REGISTRY)/auroraforge-$${svc}:$(VERSION); \
	done

deploy-aws: push-aws
	@echo ">>> Deploying to EKS (VERSION=$(VERSION))..."
	cd k8s/overlays/aws && kustomize edit set image "auroraforge/*=$(ECR_REGISTRY)/*:$(VERSION)"
	kubectl apply -k k8s/overlays/aws --context auroraforge-aws
	kubectl rollout status deployment -n auroraforge --timeout=5m --context auroraforge-aws
	@echo ">>> AWS deployment complete."

# ── Azure deployment ──────────────────────────────────────────────────────────
acr-login:
	az acr login --name auroraforgeacr

push-azure: images acr-login
	@for svc in $(SERVICES); do \
	  echo ">>> Pushing auroraforge-$${svc} to ACR..."; \
	  docker tag auroraforge/auroraforge-$${svc}:$(VERSION) \
	             $(ACR_REGISTRY)/auroraforge-$${svc}:$(VERSION); \
	  docker push $(ACR_REGISTRY)/auroraforge-$${svc}:$(VERSION); \
	done

deploy-azure: push-azure
	@echo ">>> Deploying to AKS (VERSION=$(VERSION))..."
	cd k8s/overlays/azure && kustomize edit set image "auroraforge/*=$(ACR_REGISTRY)/*:$(VERSION)"
	kubectl apply -k k8s/overlays/azure --context auroraforge-azure
	kubectl rollout status deployment -n auroraforge --timeout=5m --context auroraforge-azure
	@echo ">>> Azure deployment complete."

# ── Cleanup ───────────────────────────────────────────────────────────────────
clean:
	$(MVN) clean
	find services -name "*.class" -delete 2>/dev/null || true
