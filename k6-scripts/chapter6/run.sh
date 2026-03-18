#!/bin/bash
# 사용법:
#   ./run.sh hot-key                        # Hot Key before (락 없음)
#   STRATEGY=after ./run.sh hot-key         # Hot Key after (분산 락)
#   ./run.sh cache-avalanche                # Avalanche before (고정 TTL)
#   STRATEGY=after ./run.sh cache-avalanche # Avalanche after (Jitter)
#   ./run.sh cache-penetration              # Penetration before
#   STRATEGY=after ./run.sh cache-penetration
#   BASE_URL=http://<EC2_IP>:8080/api STRATEGY=after ./run.sh hot-key

# .env 파일이 있으면 자동 로드
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
[ -f "$ENV_FILE" ] && export $(grep -v '^#' "$ENV_FILE" | xargs)

SCRIPT=${1:-hot-key}
DATE=$(date +%Y-%m-%d_%H-%M)
mkdir -p results

echo "▶ Running: ${SCRIPT}-test.js (STRATEGY=${STRATEGY:-before})"
k6 run "${SCRIPT}-test.js" --summary-export "results/${SCRIPT}-${STRATEGY:-before}-${DATE}.json"
