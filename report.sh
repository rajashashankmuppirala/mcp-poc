#!/usr/bin/env bash
#
# CLI client for the AI Gateway — sends a natural language prompt
# and prints the streaming NDJSON response in real-time.
#
# Usage:
#   ./report.sh "Show me revenue by product for Q1"
#   echo "Sales for us-east" | ./report.sh
#

set -euo pipefail

GATEWAY_URL="${REPORT_GATEWAY_URL:-http://localhost:8080}"

read_prompt() {
    if [ $# -gt 0 ]; then
        echo "$*"
    elif [ ! -t 0 ]; then
        cat
    else
        echo -n "report> " >&2
        read -r line
        echo "$line"
    fi
}

main() {
    local prompt
    prompt="$(read_prompt "$@" || true)"

    if [ -z "$prompt" ]; then
        echo "Error: empty prompt" >&2
        exit 1
    fi

    echo "Prompt: $prompt" >&2
    echo "Gateway: $GATEWAY_URL" >&2
    echo "---" >&2

    local body
    body=$(echo "$prompt" | jq -Rs '{"prompt": .}')

    curl -s -N \
        -X POST "${GATEWAY_URL}/ai/request" \
        -H "Content-Type: application/json" \
        -d "$body"

    echo "" >&2
    echo "---" >&2
    echo "done" >&2
}

main "$@"
