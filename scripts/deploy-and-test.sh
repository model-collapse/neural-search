#!/usr/bin/env bash
#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# Deploy & Test pipeline for the neural-search plugin.
#
# Builds the plugin from local source, provisions a matching OpenSearch
# instance via Gradle, installs all required plugins, starts a single-node
# cluster, runs smoke tests, and reports results.
#
# Usage:
#   ./scripts/deploy-and-test.sh [OPTIONS]
#
# Options:
#   --skip-build       Reuse previously built plugin ZIPs
#   --keep-running     Leave the cluster running after tests
#   --port PORT        HTTP port for OpenSearch (default: 9200)
#   --num-nodes N      Number of cluster nodes (default: 1)
#   --endpoint URL     Test against an already-running cluster (skip build/deploy)
#   --help             Show this help message
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# --- Defaults ----------------------------------------------------------------
SKIP_BUILD=false
KEEP_RUNNING=false
HTTP_PORT=9200
NUM_NODES=1
EXTERNAL_ENDPOINT=""
GRADLE_PID=""
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_TOTAL=0

# --- Colors -------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# --- Helpers ------------------------------------------------------------------
log()  { echo -e "${BLUE}[pipeline]${NC} $*"; }
ok()   { echo -e "${GREEN}[  OK  ]${NC} $*"; }
fail() { echo -e "${RED}[ FAIL ]${NC} $*"; }
warn() { echo -e "${YELLOW}[ WARN ]${NC} $*"; }
header() { echo -e "\n${BOLD}=== $* ===${NC}"; }

record_test() {
    local name="$1" result="$2"
    TESTS_TOTAL=$((TESTS_TOTAL + 1))
    if [[ "$result" == "pass" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        ok "$name"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        fail "$name"
    fi
}

usage() {
    sed -n '/^# Usage:/,/^[^#]/p' "$0" | grep '^#' | sed 's/^# \?//'
    exit 0
}

cleanup() {
    if [[ -n "$EXTERNAL_ENDPOINT" ]]; then
        return
    fi
    if [[ -n "$GRADLE_PID" ]] && kill -0 "$GRADLE_PID" 2>/dev/null; then
        if [[ "$KEEP_RUNNING" == true ]]; then
            log "Cluster left running (Gradle PID ${GRADLE_PID}) on port ${HTTP_PORT}"
            log "Stop it with:  kill ${GRADLE_PID}"
            return
        fi
        log "Stopping OpenSearch cluster (Gradle PID ${GRADLE_PID})..."
        kill "$GRADLE_PID" 2>/dev/null || true
        wait "$GRADLE_PID" 2>/dev/null || true
    fi
}

trap cleanup EXIT

# --- Parse arguments ----------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)    SKIP_BUILD=true; shift ;;
        --keep-running)  KEEP_RUNNING=true; shift ;;
        --port)          HTTP_PORT="$2"; shift 2 ;;
        --num-nodes)     NUM_NODES="$2"; shift 2 ;;
        --endpoint)      EXTERNAL_ENDPOINT="$2"; shift 2 ;;
        --help|-h)       usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

# --- Resolve version ----------------------------------------------------------
OS_VERSION=$(grep -m1 'opensearch.version"' "${PROJECT_ROOT}/build.gradle" \
    | sed 's/.*"\([0-9][^"]*\)".*/\1/')
if [[ -z "$OS_VERSION" ]]; then
    echo "ERROR: Could not determine OpenSearch version from build.gradle." >&2
    exit 1
fi

header "Neural Search Deploy & Test Pipeline"
log "OpenSearch version : ${OS_VERSION}"
log "HTTP port          : ${HTTP_PORT}"
log "Nodes              : ${NUM_NODES}"
if [[ -n "$EXTERNAL_ENDPOINT" ]]; then
    log "Mode               : external cluster (${EXTERNAL_ENDPOINT})"
else
    log "Mode               : local cluster via Gradle"
    log "Skip build         : ${SKIP_BUILD}"
    log "Keep running       : ${KEEP_RUNNING}"
fi

# ==============================================================================
# Stage 1: Build the plugin (skipped in external-endpoint mode)
# ==============================================================================
if [[ -z "$EXTERNAL_ENDPOINT" ]]; then
    header "Stage 1: Build Plugin"

    if [[ "$SKIP_BUILD" == true ]]; then
        log "Skipping build — will use cached artifacts"
    else
        log "Building plugin from source..."
        cd "$PROJECT_ROOT"
        ./gradlew assemble --console=plain -q
        ok "Plugin built successfully"
    fi
fi

# ==============================================================================
# Stage 2: Start OpenSearch cluster
# ==============================================================================
if [[ -z "$EXTERNAL_ENDPOINT" ]]; then
    header "Stage 2: Start OpenSearch Cluster"

    log "Starting ${NUM_NODES}-node cluster via './gradlew run' (this provisions OpenSearch"
    log "with K-NN, ML Commons, and neural-search plugins automatically)..."

    cd "$PROJECT_ROOT"

    GRADLE_LOG="${PROJECT_ROOT}/build/deploy-pipeline-gradle.log"
    mkdir -p "${PROJECT_ROOT}/build"

    GRADLE_ARGS=(-PnumNodes="${NUM_NODES}")
    if [[ "$HTTP_PORT" != "9200" ]]; then
        GRADLE_ARGS+=(-Dhttp.port="${HTTP_PORT}")
    fi

    ./gradlew run "${GRADLE_ARGS[@]}" --console=plain > "$GRADLE_LOG" 2>&1 &
    GRADLE_PID=$!
    log "Gradle run started (PID ${GRADLE_PID}), log: ${GRADLE_LOG}"

    ENDPOINT="http://127.0.0.1:${HTTP_PORT}"
    WAIT_SECONDS=180

    log "Waiting for cluster to be ready (up to ${WAIT_SECONDS}s)..."
    for i in $(seq 1 $WAIT_SECONDS); do
        if curl -sf "${ENDPOINT}/_cluster/health" -o /dev/null 2>/dev/null; then
            ok "Cluster is ready (took ${i}s)"
            break
        fi
        if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
            fail "Gradle process exited during startup"
            echo "--- Last 40 lines of Gradle log ---"
            tail -40 "$GRADLE_LOG" 2>/dev/null || true
            exit 1
        fi
        if [[ $i -eq $WAIT_SECONDS ]]; then
            fail "Cluster did not become ready within ${WAIT_SECONDS}s"
            echo "--- Last 40 lines of Gradle log ---"
            tail -40 "$GRADLE_LOG" 2>/dev/null || true
            exit 1
        fi
        sleep 1
    done
else
    ENDPOINT="${EXTERNAL_ENDPOINT}"
    header "Stage 2: Verify External Cluster"

    if ! curl -sf "${ENDPOINT}/_cluster/health" -o /dev/null 2>/dev/null; then
        fail "Cannot reach cluster at ${ENDPOINT}"
        exit 1
    fi
    ok "External cluster is reachable"
fi

# Print cluster info
log "Cluster info:"
curl -sf "${ENDPOINT}" 2>/dev/null | head -20 || true
echo ""

# ==============================================================================
# Stage 3: Smoke Tests
# ==============================================================================
header "Stage 3: Smoke Tests"

api() {
    curl -sf -H "Content-Type: application/json" "$@"
}

# --- Test 1: Cluster health ---
cluster_health=$(api "${ENDPOINT}/_cluster/health" 2>/dev/null || true)
if echo "$cluster_health" | grep -qE '"status"\s*:\s*"(green|yellow)"'; then
    record_test "Cluster health is green/yellow" "pass"
else
    record_test "Cluster health is green/yellow" "fail"
fi

# --- Test 2: Neural-search plugin loaded ---
cat_plugins=$(api "${ENDPOINT}/_cat/plugins?h=component&format=json" 2>/dev/null || true)
if echo "$cat_plugins" | grep -q "opensearch-neural-search"; then
    record_test "neural-search plugin is loaded" "pass"
else
    record_test "neural-search plugin is loaded" "fail"
fi

# --- Test 3: K-NN plugin loaded ---
if echo "$cat_plugins" | grep -q "opensearch-knn"; then
    record_test "k-NN plugin is loaded" "pass"
else
    record_test "k-NN plugin is loaded" "fail"
fi

# --- Test 4: ML Commons plugin loaded ---
if echo "$cat_plugins" | grep -q "opensearch-ml"; then
    record_test "ML Commons plugin is loaded" "pass"
else
    record_test "ML Commons plugin is loaded" "fail"
fi

# --- Test 5: Configure ML Commons settings ---
ml_settings_resp=$(api -XPUT "${ENDPOINT}/_cluster/settings" -d '{
  "persistent": {
    "plugins.ml_commons.only_run_on_ml_node": false,
    "plugins.ml_commons.native_memory_threshold": 100,
    "plugins.ml_commons.allow_registering_model_via_url": true
  }
}' 2>/dev/null || true)
if echo "$ml_settings_resp" | grep -q '"acknowledged".*true'; then
    record_test "Configure ML Commons cluster settings" "pass"
else
    record_test "Configure ML Commons cluster settings" "fail"
fi

# --- Test 6: Create a KNN index with vector field ---
api -XDELETE "${ENDPOINT}/test-neural-idx" >/dev/null 2>&1 || true

create_idx_resp=$(api -XPUT "${ENDPOINT}/test-neural-idx" -d '{
  "settings": {
    "index.knn": true,
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "passage_text": { "type": "text" },
      "passage_embedding": {
        "type": "knn_vector",
        "dimension": 3,
        "method": {
          "name": "hnsw",
          "engine": "lucene"
        }
      }
    }
  }
}' 2>/dev/null || true)
if echo "$create_idx_resp" | grep -q '"acknowledged".*true'; then
    record_test "Create KNN index with vector field" "pass"
else
    record_test "Create KNN index with vector field" "fail"
fi

# --- Test 7: Index documents with embeddings ---
bulk_resp=$(api -XPOST "${ENDPOINT}/_bulk?refresh=true" -d '
{"index": {"_index": "test-neural-idx", "_id": "1"}}
{"passage_text": "OpenSearch is a search engine", "passage_embedding": [1.0, 2.0, 3.0]}
{"index": {"_index": "test-neural-idx", "_id": "2"}}
{"passage_text": "Neural search uses machine learning", "passage_embedding": [4.0, 5.0, 6.0]}
{"index": {"_index": "test-neural-idx", "_id": "3"}}
{"passage_text": "Sparse vectors are efficient", "passage_embedding": [7.0, 8.0, 9.0]}
' 2>/dev/null || true)
if echo "$bulk_resp" | grep -q '"errors".*false'; then
    record_test "Bulk index documents with embeddings" "pass"
else
    record_test "Bulk index documents with embeddings" "fail"
fi

# --- Test 8: KNN vector search ---
knn_resp=$(api -XPOST "${ENDPOINT}/test-neural-idx/_search" -d '{
  "size": 2,
  "query": {
    "knn": {
      "passage_embedding": {
        "vector": [1.0, 2.0, 3.0],
        "k": 2
      }
    }
  }
}' 2>/dev/null || true)
knn_hits=$(echo "$knn_resp" | grep -oP '"total"\s*:\s*\{"value"\s*:\s*\K[0-9]+' | head -1 || true)
if [[ -n "$knn_hits" ]] && [[ "$knn_hits" -ge 1 ]]; then
    record_test "KNN vector search returns results (got ${knn_hits} hits)" "pass"
else
    record_test "KNN vector search returns results" "fail"
fi

# --- Test 9: Hybrid search (neural-search specific feature) ---
hybrid_resp=$(api -XPOST "${ENDPOINT}/test-neural-idx/_search" -d '{
  "query": {
    "hybrid": {
      "queries": [
        {
          "match": {
            "passage_text": "search engine"
          }
        },
        {
          "knn": {
            "passage_embedding": {
              "vector": [1.0, 2.0, 3.0],
              "k": 2
            }
          }
        }
      ]
    }
  }
}' 2>/dev/null || true)
hybrid_hits=$(echo "$hybrid_resp" | grep -oP '"total"\s*:\s*\{"value"\s*:\s*\K[0-9]+' | head -1 || true)
if [[ -n "$hybrid_hits" ]] && [[ "$hybrid_hits" -ge 1 ]]; then
    record_test "Hybrid query (BM25 + KNN) returns results (got ${hybrid_hits} hits)" "pass"
else
    record_test "Hybrid query (BM25 + KNN) returns results" "fail"
fi

# --- Test 10: Search pipeline with normalization processor ---
create_pipeline_resp=$(api -XPUT "${ENDPOINT}/_search/pipeline/norm-pipeline" -d '{
  "description": "Normalization pipeline for hybrid search",
  "phase_results_processors": [
    {
      "normalization-processor": {
        "normalization": {
          "technique": "min_max"
        },
        "combination": {
          "technique": "arithmetic_mean",
          "parameters": {
            "weights": [0.3, 0.7]
          }
        }
      }
    }
  ]
}' 2>/dev/null || true)
if echo "$create_pipeline_resp" | grep -q '"acknowledged".*true'; then
    record_test "Create search pipeline with normalization processor" "pass"
else
    record_test "Create search pipeline with normalization processor" "fail"
fi

# --- Test 11: Hybrid search through normalization pipeline ---
norm_resp=$(api -XPOST "${ENDPOINT}/test-neural-idx/_search?search_pipeline=norm-pipeline" -d '{
  "query": {
    "hybrid": {
      "queries": [
        {
          "match": {
            "passage_text": "machine learning"
          }
        },
        {
          "knn": {
            "passage_embedding": {
              "vector": [4.0, 5.0, 6.0],
              "k": 3
            }
          }
        }
      ]
    }
  }
}' 2>/dev/null || true)
norm_hits=$(echo "$norm_resp" | grep -oP '"total"\s*:\s*\{"value"\s*:\s*\K[0-9]+' | head -1 || true)
if [[ -n "$norm_hits" ]] && [[ "$norm_hits" -ge 1 ]]; then
    record_test "Hybrid search with normalization pipeline (got ${norm_hits} hits)" "pass"
else
    record_test "Hybrid search with normalization pipeline" "fail"
fi

# --- Test 12: Neural search stats endpoint ---
# Enable stats (disabled by default)
api -XPUT "${ENDPOINT}/_cluster/settings" -d '{
  "persistent": { "plugins.neural_search.stats_enabled": true }
}' >/dev/null 2>&1 || true

stats_resp=$(api "${ENDPOINT}/_plugins/_neural/stats/" 2>/dev/null || true)
if [[ -n "$stats_resp" ]] && ! echo "$stats_resp" | grep -q '"error"'; then
    record_test "Neural search stats endpoint responds" "pass"
else
    record_test "Neural search stats endpoint responds" "fail"
fi

# --- Test 13: Ingest pipeline with text_embedding processor ---
ingest_pipeline_resp=$(api -XPUT "${ENDPOINT}/_ingest/pipeline/test-nlp-pipeline" -d '{
  "description": "Test NLP pipeline",
  "processors": [
    {
      "text_embedding": {
        "model_id": "dummy_model_id",
        "field_map": {
          "passage_text": "passage_embedding"
        }
      }
    }
  ]
}' 2>/dev/null || true)
if echo "$ingest_pipeline_resp" | grep -q '"acknowledged".*true'; then
    record_test "Create ingest pipeline with text_embedding processor" "pass"
else
    record_test "Create ingest pipeline with text_embedding processor" "fail"
fi

# --- Test 14: Cleanup ---
api -XDELETE "${ENDPOINT}/test-neural-idx" >/dev/null 2>&1 || true
api -XDELETE "${ENDPOINT}/_search/pipeline/norm-pipeline" >/dev/null 2>&1 || true
api -XDELETE "${ENDPOINT}/_ingest/pipeline/test-nlp-pipeline" >/dev/null 2>&1 || true

del_resp=$(api "${ENDPOINT}/_cat/indices?format=json" 2>/dev/null || true)
if ! echo "$del_resp" | grep -q "test-neural-idx"; then
    record_test "Cleanup test resources" "pass"
else
    record_test "Cleanup test resources" "fail"
fi

# ==============================================================================
# Results
# ==============================================================================
header "Results"

echo ""
echo -e "  Total : ${TESTS_TOTAL}"
echo -e "  ${GREEN}Passed${NC}: ${TESTS_PASSED}"
if [[ $TESTS_FAILED -gt 0 ]]; then
    echo -e "  ${RED}Failed${NC}: ${TESTS_FAILED}"
fi
echo ""

if [[ $TESTS_FAILED -eq 0 ]]; then
    echo -e "${GREEN}${BOLD}All smoke tests passed.${NC}"
else
    echo -e "${RED}${BOLD}${TESTS_FAILED} test(s) failed.${NC}"
fi

if [[ "$KEEP_RUNNING" == true ]] && [[ -z "$EXTERNAL_ENDPOINT" ]]; then
    echo ""
    log "Cluster is still running at ${ENDPOINT}"
    log "Try: curl ${ENDPOINT}/_cat/plugins?v"
    log "Stop: kill ${GRADLE_PID}"
fi

exit $TESTS_FAILED
