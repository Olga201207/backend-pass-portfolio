#!/bin/bash
# 사용법: ./run.sh [board-load|algorithm|cpu|memory|resource]
# BASE_URL=http://<EC2_IP>:8080/api ./run.sh board-load

# .env 파일이 있으면 자동 로드
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
[ -f "$ENV_FILE" ] && export $(grep -v '^#' "$ENV_FILE" | xargs)

SCRIPT=${1:-board-load}
DATE=$(date +%Y-%m-%d_%H-%M)
mkdir -p results
echo "▶ Running: ${SCRIPT}-test.js"
k6 run "${SCRIPT}-test.js" --summary-export "results/${SCRIPT}-${DATE}.json"
