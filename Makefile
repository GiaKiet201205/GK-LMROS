PYTHON ?= python3
MVN ?= mvn
NPM ?= npm
COMPOSE ?= docker compose
OPTIMIZER_SRC := $(CURDIR)/services/optimizer/src

.PHONY: help up down logs build build-java build-web test test-web test-python check clean dev-dispatcher dev-driver dev-customer dev-optimizer

help:
	@echo "up / down / logs       Start, stop or inspect the Docker stack"
	@echo "build                  Build the 3 Java services and 3 web portals"
	@echo "check                  Validate repository imports, packages and Compose paths"
	@echo "test                   Run web, optimizer and ops unit tests"
	@echo "clean                  Remove generated build output and Python caches"
	@echo "dev-dispatcher / dev-driver / dev-customer / dev-optimizer"

up:
	$(COMPOSE) up -d --build --wait

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs --tail=100 -f

build: build-java build-web

build-java:
	$(MVN) package

build-web:
	$(NPM) --prefix apps/web run build
	$(NPM) --prefix apps/web run build:driver
	$(NPM) --prefix apps/web run build:customer

check:
	$(PYTHON) scripts/checks/check_structure.py
	$(COMPOSE) config --quiet

test: test-web test-python

test-web:
	node --test apps/web/tests/*.test.js

test-python:
	PYTHONPATH="$(OPTIMIZER_SRC)" $(PYTHON) -m unittest discover -s services/optimizer/tests -p 'test_*.py'
	$(PYTHON) -m unittest discover -s tests/unit -p 'test_*.py'

clean:
	$(PYTHON) scripts/dev/clean.py

dev-dispatcher:
	$(NPM) --prefix apps/web run dev

dev-driver:
	$(NPM) --prefix apps/web run dev:driver

dev-customer:
	$(NPM) --prefix apps/web run dev:customer

dev-optimizer:
	$(PYTHON) -m uvicorn gk_optimizer.api:app --app-dir services/optimizer/src --host 127.0.0.1 --port 8000
