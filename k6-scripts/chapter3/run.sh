#!/bin/bash
# 사용법: ./run.sh [before|after]
# BASE_URL=http://<EC2_IP>:8080/api ./run.sh before

# .env 파일이 있으면 자동 로드
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
[ -f "$ENV_FILE" ] && export $(grep -v '^#' "$ENV_FILE" | xargs)

PHASE=${1:-before}
DATE=$(date +%Y-%m-%d_%H-%M)
mkdir -p results
echo "▶ Running: ${PHASE}/order-test.js"
k6 run "${PHASE}/order-test.js" --summary-export "results/order-${PHASE}-${DATE}.json"
