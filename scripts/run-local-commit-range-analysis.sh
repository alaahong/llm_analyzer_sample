#!/usr/bin/env bash
# Run PrChangeTestAnalyzer locally for a given commit range, using a local Ollama model via OpenAI-compatible API.
# - Starts Ollama (if not running), ensures model is available.
# - Stubs `gh` so the analyzer can read "PR files" from `git diff <base>..<head>` and "post comment" to a local file.
# - Executes the analyzer with LLM_PROVIDER=openai to use the local model.
#
# Usage:
#   scripts/run-local-commit-range-analysis.sh <BASE_SHA_OR_REF> <HEAD_SHA_OR_REF> [--model <ollama_model>] [--fail-on-test-failure <true|false>]
#
# Examples:
#   scripts/run-local-commit-range-analysis.sh main HEAD
#   scripts/run-local-commit-range-analysis.sh 1a2b3c4d 5e6f7a8b --model "llama3.1:8b-instruct"
#
# Requirements:
# - bash, git, curl, java (21+), and Maven/Gradle as needed by your project.
# - This script is meant to be run at the repository root (where scripts/PrChangeTestAnalyzer.java exists).
set -euo pipefail

# -------- Args --------
if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <BASE_SHA_OR_REF> <HEAD_SHA_OR_REF> [--model <ollama_model>] [--fail-on-test-failure <true|false>]"
  exit 1
fi
BASE_REF="$1"
HEAD_REF="$2"
shift 2

OLLAMA_MODEL_DEFAULT="llama3.1:8b-instruct"
OLLAMA_MODEL="$OLLAMA_MODEL_DEFAULT"
FAIL_ON_TEST_FAILURE="true"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model)
      OLLAMA_MODEL="${2:-$OLLAMA_MODEL_DEFAULT}"
      shift 2
      ;;
    --fail-on-test-failure)
      FAIL_ON_TEST_FAILURE="${2:-true}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1"
      exit 1
      ;;
  esac
done

# -------- Resolve SHAs --------
BASE_SHA="$(git rev-parse "${BASE_REF}")"
HEAD_SHA="$(git rev-parse "${HEAD_REF}")"
echo "[info] Commit range: ${BASE_SHA} .. ${HEAD_SHA}"

# -------- Ensure Java --------
if ! command -v java >/dev/null 2>&1; then
  echo "[error] java not found. Please install Java 21+ and retry."
  exit 1
fi

# -------- Start/verify Ollama (OpenAI-compatible at http://127.0.0.1:11434/v1) --------
ensure_ollama() {
  local host="127.0.0.1"
  local port="11434"
  local base="http://${host}:${port}"
  if ! curl -sSf "${base}/v1/models" >/dev/null 2>&1; then
    echo "[info] Ollama not responding on ${base}, trying to start it..."
    if command -v ollama >/dev/null 2>&1; then
      nohup ollama serve >/tmp/ollama_serve.log 2>&1 &
      # wait up to 120s
      if ! timeout 120 bash -lc "until curl -sSf ${base}/v1/models >/dev/null; do sleep 2; done"; then
        echo "[error] Ollama did not become ready on ${base}."
        exit 1
      fi
    else
      echo "[error] ollama command not found. Install from https://ollama.com and retry."
      exit 1
    fi
  fi
  # Pull model if not present
  if ! curl -sSf "${base}/v1/models" | grep -q "\"id\":\"${OLLAMA_MODEL}\""; then
    echo "[info] Pulling model ${OLLAMA_MODEL} ..."
    ollama pull "${OLLAMA_MODEL}"
  else
    echo "[info] Model ${OLLAMA_MODEL} already available."
  fi
}
ensure_ollama

# -------- Build a temp GH stub --------
WORK_DIR="$(pwd)"
TMP_DIR="$(mktemp -d)"
STUB_DIR="${TMP_DIR}/gh-stub"
mkdir -p "${STUB_DIR}" "${TMP_DIR}/out"
LOCAL_OUTPUT="${TMP_DIR}/out/local-pr-comment.md"
REPO_SLUG="$(git config --get remote.origin.url | sed -E 's#(git@|https://)github\.com[:/ ]##; s/\.git$//')"
REPO_SLUG="${REPO_SLUG:-local/repo}"
echo "[info] Repo slug: ${REPO_SLUG}"
echo "[info] Output PR comment will be saved to: ${LOCAL_OUTPUT}"

cat > "${STUB_DIR}/gh" <<'GHSTUB'
#!/usr/bin/env bash
set -euo pipefail

# This stub emulates a tiny portion of the GitHub CLI used by PrChangeTestAnalyzer:
# 1) `gh api repos/<owner>/<repo>/pulls/<number>/files?...` -> returns a JSON array derived from `git diff --name-status $BASE_SHA..$HEAD_SHA`
# 2) `gh pr comment <number> --repo <repo> --body-file <path>` -> writes the body to a local file and prints a message

usage() { echo "gh stub: unsupported arguments: $*"; exit 0; }

cmd="${1:-}"
shift || true

# Read required env from parent shell
BASE_SHA="${BASE_SHA:-}"
HEAD_SHA="${HEAD_SHA:-}"
LOCAL_OUTPUT="${LOCAL_OUTPUT:-./local-pr-comment.md}"

if [[ "${cmd}" == "api" ]]; then
  endpoint="${1:-}"; shift || true
  # Ignore any flags like --jq
  if [[ "${endpoint}" =~ ^repos/.*/pulls/.*/files ]]; then
    if [[ -z "${BASE_SHA}" || -z "${HEAD_SHA}" ]]; then
      echo "[]" && exit 0
    fi
    # Build JSON from git diff name-status
    # Map: A->added, M->modified, D->removed, R*->modified
    echo -n "["

    # Use tab as separator for filenames, handle spaces
    git diff --name-status "${BASE_SHA}".."${HEAD_SHA}" | awk '
      BEGIN { first=1 }
      {
        status=$1
        # Join fields 2..end with tab intact
        # For rename: Rxxx <old> <new> -> we take the new path ($3)
        if (status ~ /^R/) {
          # Rename has 3 fields; filename is $3
          file=$3
          mapped="modified"
        } else {
          file=$2
          if (status=="A") mapped="added";
          else if (status=="M") mapped="modified";
          else if (status=="D") mapped="removed";
          else mapped="modified";
        }
        # Escape JSON quotes and backslashes
        gsub(/\\/,"\\\\",file); gsub(/"/,"\\\"",file);
        if (!first) printf(",");
        printf("{\"filename\":\"%s\",\"status\":\"%s\"}", file, mapped);
        first=0
      }
    '
    echo "]"
    exit 0
  else
    # For any other API endpoint we don't emulate
    echo "{}"
    exit 0
  fi
elif [[ "${cmd}" == "pr" ]]; then
  sub="${1:-}"; shift || true
  if [[ "${sub}" == "comment" ]]; then
    prnum="${1:-}"; shift || true
    # crude parse flags --repo and --body-file
    REPO=""
    BODY_FILE=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --repo) REPO="${2:-}"; shift 2;;
        --body-file) BODY_FILE="${2:-}"; shift 2;;
        *) shift;;
      esac
    done
    if [[ -n "${BODY_FILE}" && -f "${BODY_FILE}" ]]; then
      cp "${BODY_FILE}" "${LOCAL_OUTPUT}"
      echo "[gh-stub] Wrote PR comment for PR #${prnum} (repo: ${REPO}) to ${LOCAL_OUTPUT}"
    else
      echo "[gh-stub] No body file provided. Nothing written."
    fi
    exit 0
  fi
fi

usage "$@"
GHSTUB
chmod +x "${STUB_DIR}/gh"

# -------- Run analyzer with stubbed gh in PATH --------
export PATH="${STUB_DIR}:$PATH"
export BASE_SHA="${BASE_SHA}"
export HEAD_SHA="${HEAD_SHA}"
export LOCAL_OUTPUT="${LOCAL_OUTPUT}"

# Analyzer env
export REPO="${REPO_SLUG}"
export PULL_NUMBER="${PULL_NUMBER:-0}"                 # synthetic PR number (unused by gh-stub "api" path)
export SERVER_URL="${SERVER_URL:-https://github.com}"
export API_URL="${API_URL:-https://api.github.com}"
export WORKFLOW_NAME="${WORKFLOW_NAME:-PR Targeted Test Analyzer}"

# Use local LLM (Ollama) via OpenAI-compatible API
export LLM_PROVIDER="openai"
export LLM_BASE_URL="${LLM_BASE_URL:-http://127.0.0.1:11434/v1}"
export LLM_MODEL="${LLM_MODEL:-${OLLAMA_MODEL}}"
export LLM_API_KEY="${LLM_API_KEY:-}"                  # usually not required for local Ollama

# Keep OpenRouter unset to avoid remote usage
export OPENROUTER_API_KEY=""
export OPENROUTER_MODEL=""

# Behavior
export ANALYZER_MAX_HIGHLIGHTS="${ANALYZER_MAX_HIGHLIGHTS:-200}"
export LLM_MAX_TOKENS="${LLM_MAX_TOKENS:-800}"
export FAIL_ON_TEST_FAILURE="${FAIL_ON_TEST_FAILURE}"

echo "[info] Running PrChangeTestAnalyzer with local OpenAI-compatible provider (Ollama)"
set +e
java scripts/PrChangeTestAnalyzer.java
RC=$?
set -e

echo
echo "----------------------------------------"
if [[ -f "${LOCAL_OUTPUT}" ]]; then
  echo "[info] Local PR comment saved to: ${LOCAL_OUTPUT}"
  echo "Preview (first 80 lines):"
  echo "----------------------------------------"
  head -n 80 "${LOCAL_OUTPUT}" || true
  echo "----------------------------------------"
else
  echo "[warn] No PR comment file produced (the analyzer may have terminated early)."
fi

exit "${RC}"