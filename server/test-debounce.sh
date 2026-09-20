#!/usr/bin/env bash
# Local debounce acceptance for agentping-ntfy-body.py (no real ntfy required).
# Usage: ./test-debounce.sh
set -euo pipefail
cd "$(dirname "$0")"

PY=python3
command -v python3 >/dev/null 2>&1 || PY=python

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export XDG_CACHE_HOME="$TMP/cache"
export AGENTPING_URL="http://127.0.0.1:9"   # publish will fail quietly; we assert pending/files
export AGENTPING_TOKEN="tk_test"
export AGENTPING_BODY_PY="$PWD/agentping-ntfy-body.py"
export AGENTPING_DEBOUNCE_SEC=5

BODY="$PWD/agentping-ntfy-body.py"
pass=0
fail=0

ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; fail=$((fail + 1)); }

# Monkeypatch: wrap publisher to record posts instead of HTTP
RECORD="$TMP/posts.jsonl"
cat > "$TMP/recorder.py" <<'PY'
import json, os, sys, time
# Import the real module by path
import importlib.util
spec = importlib.util.spec_from_file_location("body", os.environ["AGENTPING_BODY_PY"])
body = importlib.util.module_from_spec(spec)
spec.loader.exec_module(body)

record = os.environ["AP_RECORD"]

def fake_publish(fields, tags, prio, url, token, host_topic):
    with open(record, "a", encoding="utf-8") as f:
        f.write(json.dumps({"fields": fields, "tags": tags, "prio": prio, "topic": host_topic}, ensure_ascii=False) + "\n")

body._publish_fields = fake_publish
# Do not detach a real sleeper during tests; the harness flushes explicitly.
body._spawn_flush = lambda keyhash, sec: None
sys.exit(body.main(sys.argv[1:]))
PY

run_body() {
  AP_RECORD="$RECORD" \
  AP_AGENT="${AP_AGENT:-opencode}" AP_HOST="${AP_HOST:-testhost}" AP_STATE="$1" \
  AP_TASK="${AP_TASK:-}" AP_DETAIL="${AP_DETAIL:-}" AP_SESSION="${AP_SESSION:-}" \
  AP_DUR="${AP_DUR:-}" AP_TAGS="${AP_TAGS:-robot,white_check_mark}" AP_PRIO="${AP_PRIO:-3}" \
  AP_TOPIC_HOST="agentping-testhost" \
  "$PY" "$TMP/recorder.py" "${@:2}"
}

post_count() {
  if [ -f "$RECORD" ]; then wc -l < "$RECORD" | tr -d ' '; else echo 0; fi
}

rm -f "$RECORD"
: > "$RECORD"
mkdir -p "$XDG_CACHE_HOME"

# --- 1) DEBOUNCE_SEC=0 → immediate ---
AGENTPING_DEBOUNCE_SEC=0 AP_TASK=a AP_SESSION=s1 run_body finished
n=$(post_count)
[ "$n" = "1" ] && ok "SEC=0 immediate publish" || bad "SEC=0 expected 1 post got $n"

# --- 2) three finished in window → one merged after flush ---
: > "$RECORD"
AGENTPING_DEBOUNCE_SEC=5
AP_TASK=t1 AP_DETAIL=d1 AP_SESSION=s2 run_body finished
AP_TASK=t2 AP_DETAIL=d2 AP_SESSION=s2 run_body finished
AP_TASK=t3 AP_DETAIL=d3 AP_SESSION=s2 run_body finished
n=$(post_count)
[ "$n" = "0" ] && ok "window open: no publish yet" || bad "window open expected 0 posts got $n"

# Find pending keyhash and flush with sleep 0 (simulate end of window)
pending_root="$XDG_CACHE_HOME/agentping/debounce"
keys=("$pending_root"/*)
[ -d "${keys[0]}" ] || { bad "no pending dir"; keys=(); }
if [ -d "${keys[0]:-}" ]; then
  kh=$(basename "${keys[0]}")
  AGENTPING_DEBOUNCE_SEC=5 AP_RECORD="$RECORD" "$PY" "$TMP/recorder.py" --flush "$kh" 0
  n=$(post_count)
  if [ "$n" = "1" ]; then
    detail=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('detail',''))")
    task=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('task',''))")
    state=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('state',''))")
    session=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('session',''))")
    if [ "$task" = "t1" ] && [ "$state" = "finished" ] && [ "$session" = "s2" ] \
       && echo "$detail" | grep -q "d1" && echo "$detail" | grep -q "d2" && echo "$detail" | grep -qE "d3|t3|t2"; then
      ok "merged flush: first meta + accumulated detail"
    else
      bad "merged content task=$task state=$state session=$session detail=$detail"
    fi
  else
    bad "flush expected 1 post got $n"
  fi
fi

# --- 3) failed clears pending and publishes immediately ---
: > "$RECORD"
rm -rf "$XDG_CACHE_HOME/agentping/debounce"
AGENTPING_DEBOUNCE_SEC=30
AP_TASK=keep AP_SESSION=s3 run_body finished
# pending should exist
pend_count=$(find "$XDG_CACHE_HOME/agentping/debounce" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')
[ "$pend_count" -ge 1 ] && ok "pending created for finished" || bad "expected pending after finished"

AP_TASK=boom AP_DETAIL=err AP_SESSION=s3 AP_TAGS="robot,x" AP_PRIO=4 run_body failed
n=$(post_count)
[ "$n" = "1" ] && ok "failed immediate publish" || bad "failed expected 1 post got $n"
state=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('state',''))")
[ "$state" = "failed" ] && ok "failed state in post" || bad "expected failed state got $state"

# flush any leftover key for s3 should be no-op
shopt -s nullglob
for d in "$XDG_CACHE_HOME/agentping/debounce"/*; do
  [ -d "$d" ] || continue
  AGENTPING_DEBOUNCE_SEC=30 AP_RECORD="$RECORD" "$PY" "$TMP/recorder.py" --flush "$(basename "$d")" 0 || true
done
shopt -u nullglob
n=$(post_count)
[ "$n" = "1" ] && ok "failed cleared pending (no extra flush)" || bad "expected still 1 post after flush got $n"

# --- 4) no session → host+agent key merges ---
: > "$RECORD"
rm -rf "$XDG_CACHE_HOME/agentping/debounce"
AGENTPING_DEBOUNCE_SEC=5
AP_SESSION= AP_AGENT=shell AP_HOST=h1 AP_TASK=a run_body finished
AP_SESSION= AP_AGENT=shell AP_HOST=h1 AP_TASK=b run_body finished
shopt -s nullglob
keys=("$XDG_CACHE_HOME/agentping/debounce"/*)
shopt -u nullglob
if [ -d "${keys[0]:-}" ]; then
  AGENTPING_DEBOUNCE_SEC=5 AP_RECORD="$RECORD" "$PY" "$TMP/recorder.py" --flush "$(basename "${keys[0]}")" 0
  n=$(post_count)
  detail=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('detail',''))" 2>/dev/null || true)
  task=$("$PY" -c "import json; print(json.load(open('$RECORD')).get('fields',{}).get('task',''))" 2>/dev/null || true)
  if [ "$n" = "1" ] && [ "$task" = "a" ] && echo "$detail" | grep -q "b"; then
    ok "no-session host+agent merge"
  else
    bad "no-session merge n=$n task=$task detail=$detail"
  fi
else
  bad "no-session: missing pending"
fi

# --- 5) started path is bash-level; body shouldn't be called — smoke syntax ---
bash -n ./agent-notify && ok "bash -n agent-notify" || bad "bash -n agent-notify"
bash -n ./agent-notify.win && ok "bash -n agent-notify.win" || bad "bash -n agent-notify.win"
"$PY" -m py_compile ./agentping-ntfy-body.py && ok "py_compile body.py" || bad "py_compile body.py"

echo
echo "results: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
