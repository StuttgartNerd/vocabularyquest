#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MC_HOST="${MC_HOST:-127.0.0.1}"
MC_PORT="${MC_PORT:-25565}"
MC_USERNAME="${MC_USERNAME:-ChatBot}"
RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-dev-rcon-password}"
SMOKE_MC_VERSION="${SMOKE_MC_VERSION:-1.21.7}"

SERVER_LOG="$ROOT_DIR/paper/logs/latest.log"
DB_PATH="$ROOT_DIR/paper/plugins/VocabularyQuestPlugin/mindcraft.db"
WORK_DIR="$(mktemp -d /tmp/mindcraft-smoke-XXXXXX)"
SERVER_STDOUT="$WORK_DIR/server.out"
BOT_LOG="$WORK_DIR/bot.log"

SERVER_PID=""
BOT_PID=""

rcon_cmd() {
  "$ROOT_DIR/scripts/rcon-command.py" \
    --host "$RCON_HOST" \
    --port "$RCON_PORT" \
    --password "$RCON_PASSWORD" \
    "$1"
}

stop_bot_process() {
  if [[ -n "$BOT_PID" ]] && kill -0 "$BOT_PID" >/dev/null 2>&1; then
    kill "$BOT_PID" >/dev/null 2>&1 || true
    wait "$BOT_PID" >/dev/null 2>&1 || true
  fi
  BOT_PID=""
}

cleanup() {
  set +e

  stop_bot_process

  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    rcon_cmd "stop" >/dev/null 2>&1 || true
    sleep 2
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi

  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

wait_for_log_line() {
  local pattern="$1"
  local timeout_seconds="$2"
  local waited=0

  while (( waited < timeout_seconds )); do
    if [[ -f "$SERVER_LOG" ]] && rg -q "$pattern" "$SERVER_LOG"; then
      return 0
    fi
    sleep 1
    ((waited+=1))
  done

  return 1
}

wait_for_bot_line() {
  local pattern="$1"
  local timeout_seconds="$2"
  local waited=0

  while (( waited < timeout_seconds )); do
    if [[ -f "$BOT_LOG" ]] && rg -q "$pattern" "$BOT_LOG"; then
      return 0
    fi
    sleep 1
    ((waited+=1))
  done

  return 1
}

start_bot_process() {
  local send_on_connect="${1:-}"
  local send_delay_ms="${2:-0}"
  local hold_seconds="${3:-120}"

  : >"$BOT_LOG"
  local -a bot_cmd=(
    "$ROOT_DIR/chat-bot/build/install/chat-bot/bin/chat-bot"
    --host "$MC_HOST"
    --port "$MC_PORT"
    --username "$MC_USERNAME"
    --no-autoreply
    --no-stdin
    --hold-seconds "$hold_seconds"
  )

  if [[ -n "$send_on_connect" ]]; then
    bot_cmd+=(--send-on-connect "$send_on_connect" --send-delay-ms "$send_delay_ms")
  fi

  "${bot_cmd[@]}" >"$BOT_LOG" 2>&1 &
  BOT_PID="$!"
}

if ss -lnt | rg -q "[:.]${MC_PORT}[[:space:]]"; then
  echo "Port ${MC_PORT} is already in use. Stop existing server first."
  exit 1
fi

if [[ ! -f "$ROOT_DIR/paper/paper.jar" ]]; then
  echo "[smoke] paper/paper.jar missing, downloading Paper ${SMOKE_MC_VERSION}..."
  "$ROOT_DIR/scripts/download-paper.sh" "$SMOKE_MC_VERSION" >/dev/null
fi

echo "[smoke] Building plugin and chatbot..."
"$ROOT_DIR/scripts/gradle.sh" :plugin-vocabulary-quest:build :chat-bot:installDist >/dev/null
"$ROOT_DIR/scripts/copy-plugin.sh" >/dev/null

echo "[smoke] Starting Paper server..."
"$ROOT_DIR/scripts/start-paper.sh" >"$SERVER_STDOUT" 2>&1 &
SERVER_PID="$!"

wait_for_log_line "Done \\(" 120 || {
  echo "[smoke] Server did not reach ready state."
  tail -n 200 "$SERVER_STDOUT" || true
  exit 1
}

wait_for_log_line "RCON running on" 60 || {
  echo "[smoke] RCON did not start."
  tail -n 200 "$SERVER_STDOUT" || true
  exit 1
}

waited=0
while (( waited < 30 )); do
  if ss -lnt | rg -q "[:.]${MC_PORT}[[:space:]]"; then
    break
  fi
  sleep 1
  ((waited+=1))
done

if (( waited >= 30 )); then
  echo "[smoke] Server port ${MC_PORT} did not become ready."
  tail -n 200 "$SERVER_STDOUT" || true
  exit 1
fi

# Give Paper a short stabilization window after initial readiness.
sleep 5

echo "[smoke] Starting ChatBot..."
bot_joined=0
for attempt in 1 2 3; do
  start_bot_process "" 0 240

  if wait_for_bot_line "Connected\\." 25 && wait_for_log_line "${MC_USERNAME} joined the game" 40; then
    bot_joined=1
    break
  fi

  echo "[smoke] Bot connection attempt ${attempt} failed, retrying..."
  stop_bot_process
  sleep 2
done

if [[ "$bot_joined" -ne 1 ]]; then
  echo "[smoke] Bot join not visible in server log."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
fi

echo "[smoke] Triggering DB dump through RCON..."
rcon_cmd "dbdump" >/dev/null
wait_for_log_line "\\[DBDUMP\\] users row: username=${MC_USERNAME}" 30 || {
  echo "[smoke] DB dump did not contain bot user."
  tail -n 200 "$SERVER_LOG" || true
  exit 1
}

echo "[smoke] Resetting answer tracking via RCON..."
rcon_cmd "flushanswers" >/dev/null

echo "[smoke] Triggering questnow via RCON..."
rcon_cmd "questnow" >/dev/null
wait_for_log_line "Vokabel-Quest: Was ist das" 30 || {
  echo "[smoke] Quest announcement not found."
  tail -n 200 "$SERVER_LOG" || true
  exit 1
}

question_line="$(rg "Vokabel-Quest: Was ist das" "$SERVER_LOG" | tail -n 1)"
if [[ "$question_line" == *"französische"* ]]; then
  vocab_table="de_fr"
  column_name="fr"
elif [[ "$question_line" == *"englische"* ]]; then
  vocab_table="de_en"
  column_name="en"
else
  echo "[smoke] Could not infer quest language from line: $question_line"
  exit 1
fi

de_word="$(sed -E 's/.*Wort für: ([^?]+)\?.*/\1/' <<<"$question_line" | xargs)"
if [[ -z "$de_word" ]]; then
  echo "[smoke] Could not parse quest vocabulary from line: $question_line"
  exit 1
fi

answer="$(
  python3 - "$DB_PATH" "$vocab_table" "$column_name" "$de_word" <<'PY'
import sqlite3
import sys

db_path, table, column, de_word = sys.argv[1:]
conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute(f"SELECT {column} FROM vocab_{table} WHERE lower(de)=lower(?) LIMIT 1", (de_word,))
row = cur.fetchone()
conn.close()
print("" if row is None else row[0])
PY
)"

if [[ -z "$answer" ]]; then
  echo "[smoke] Could not resolve answer from DB for ${vocab_table}:${de_word}"
  exit 1
fi

echo "[smoke] Reconnecting ChatBot to send /answer ..."
stop_bot_process
sleep 1
start_bot_process "/answer ${answer}" 2500 90

wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Answer bot did not connect."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_log_line "Player ${MC_USERNAME} hat die Frage richtig beantwortet" 40 || {
  echo "[smoke] Correct-answer broadcast not found."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

python3 - "$DB_PATH" "$MC_USERNAME" "$vocab_table" "$de_word" <<'PY'
import sqlite3
import sys

db_path, username, vocab_table, de_word = sys.argv[1:]
conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute(
    """
    SELECT COUNT(*)
    FROM player_vocab_rewards
    WHERE username = ? AND vocab_table = ? AND lower(de_word) = lower(?)
    """,
    (username, vocab_table, de_word),
)
count = cur.fetchone()[0]
conn.close()
if count != 1:
    raise SystemExit(f"Expected reward row for {username}/{vocab_table}/{de_word}, found {count}")
PY

echo "[smoke] Integration smoke test passed."
