#!/bin/bash
# 사용법:
#   ./run.sh n-plus-one                          # Lazy (before)
#   STRATEGY=fetch-join ./run.sh n-plus-one      # Fetch Join (after)
#   ./run.sh bulk-insert                         # Individual (before)
#   STRATEGY=batch ./run.sh bulk-insert          # Batch (after)
#   ./run.sh async                               # Sync (before)
#   STRATEGY=async ./run.sh async                # Async (after)
#   BASE_URL=http://<EC2_IP>:8080/api ./run.sh n-plus-one

# .env 파일이 있으면 자동 로드
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
[ -f "$ENV_FILE" ] && export $(grep -v '^#' "$ENV_FILE" | xargs)

SCRIPT=${1:-n-plus-one}
DATE=$(date +%Y-%m-%d_%H-%M)
mkdir -p results

echo "▶ Running: ${SCRIPT}-test.js (STRATEGY=${STRATEGY:-default})"
k6 run "${SCRIPT}-test.js" --summary-export "results/${SCRIPT}-${STRATEGY:-default}-${DATE}.json"
