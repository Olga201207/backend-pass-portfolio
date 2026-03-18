#!/bin/bash
# 사용법:
#   ./run.sh                          # pessimistic 전략으로 실행
#   STRATEGY=optimistic ./run.sh      # optimistic 전략으로 실행
#   STRATEGY=named ./run.sh           # named lock 전략으로 실행
#   BASE_URL=http://<EC2_IP>:8080/api ./run.sh  # EC2 환경

# .env 파일이 있으면 자동 로드
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
[ -f "$ENV_FILE" ] && export $(grep -v '^#' "$ENV_FILE" | xargs)

STRATEGY=${STRATEGY:-pessimistic}
DATE=$(date +%Y-%m-%d_%H-%M)
mkdir -p results

echo "▶ Running: concurrency-test.js (STRATEGY=${STRATEGY})"
STRATEGY="${STRATEGY}" k6 run concurrency-test.js --summary-export "results/concurrency-${STRATEGY}-${DATE}.json"
