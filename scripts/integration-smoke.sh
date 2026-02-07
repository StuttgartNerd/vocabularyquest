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
SHEET_HTTP_PORT="${SHEET_HTTP_PORT:-38080}"
SMOKE_RUN_ID="${SMOKE_RUN_ID:-$(date +%s%N)}"
SMOKE_KEEP_DE_WORD="smoke_keep_${SMOKE_RUN_ID}"
SMOKE_KEEP_EN_VALUE="keep_en_${SMOKE_RUN_ID}"
SMOKE_KEEP_FR_VALUE="keep_fr_${SMOKE_RUN_ID}"
SMOKE_NEW_EN_DE_WORD="smoke_new_en_${SMOKE_RUN_ID}"
SMOKE_NEW_EN_VALUE="new_en_${SMOKE_RUN_ID}"
SMOKE_NEW_FR_DE_WORD="smoke_new_fr_${SMOKE_RUN_ID}"
SMOKE_NEW_FR_VALUE="new_fr_${SMOKE_RUN_ID}"
SMOKE_PLAYTIME_LIMIT=5
SMOKE_PLAYTIME_USED=2

SERVER_LOG="$ROOT_DIR/paper/logs/latest.log"
DB_PATH="$ROOT_DIR/paper/plugins/VocabularyQuestPlugin/mindcraft.db"
WORK_DIR="$(mktemp -d /tmp/mindcraft-smoke-XXXXXX)"
SERVER_STDOUT="$WORK_DIR/server.out"
BOT_LOG="$WORK_DIR/bot.log"
SHEET_SERVER_LOG="$WORK_DIR/sheet-server.log"
SHEET_DIR="$WORK_DIR/sheet-fixture"

SERVER_PID=""
BOT_PID=""
SHEET_SERVER_PID=""

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

stop_sheet_server() {
  if [[ -n "$SHEET_SERVER_PID" ]] && kill -0 "$SHEET_SERVER_PID" >/dev/null 2>&1; then
    kill "$SHEET_SERVER_PID" >/dev/null 2>&1 || true
    wait "$SHEET_SERVER_PID" >/dev/null 2>&1 || true
  fi
  SHEET_SERVER_PID=""
}

cleanup() {
  set +e

  stop_bot_process
  stop_sheet_server

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

wait_for_player_online() {
  local timeout_seconds="$1"
  local waited=0

  while (( waited < timeout_seconds )); do
    local online_output
    online_output="$(rcon_cmd "list" 2>/dev/null || true)"
    if [[ "$online_output" == *"$MC_USERNAME"* ]]; then
      return 0
    fi
    sleep 1
    ((waited+=1))
  done

  return 1
}

wait_for_rcon_ready() {
  local timeout_seconds="$1"
  local waited=0

  while (( waited < timeout_seconds )); do
    if rcon_cmd "list" >/dev/null 2>&1; then
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

start_sheet_fixture_server() {
  mkdir -p "$SHEET_DIR"
  cat >"$SHEET_DIR/de_en.csv" <<CSV
de,en
${SMOKE_KEEP_DE_WORD},should_not_overwrite_en
${SMOKE_NEW_EN_DE_WORD},${SMOKE_NEW_EN_VALUE}
CSV
  cat >"$SHEET_DIR/de_fr.csv" <<CSV
de,fr
${SMOKE_KEEP_DE_WORD},should_not_overwrite_fr
${SMOKE_NEW_FR_DE_WORD},${SMOKE_NEW_FR_VALUE}
CSV

  python3 -m http.server "$SHEET_HTTP_PORT" --bind 127.0.0.1 --directory "$SHEET_DIR" >"$SHEET_SERVER_LOG" 2>&1 &
  SHEET_SERVER_PID="$!"

  local waited=0
  while (( waited < 20 )); do
    if curl -fsS "http://127.0.0.1:${SHEET_HTTP_PORT}/de_en.csv" >/dev/null 2>&1 \
      && curl -fsS "http://127.0.0.1:${SHEET_HTTP_PORT}/de_fr.csv" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    ((waited+=1))
  done

  return 1
}

if ss -lnt | rg -q "[:.]${MC_PORT}[[:space:]]"; then
  echo "Port ${MC_PORT} is already in use. Stop existing server first."
  exit 1
fi

if ss -lnt | rg -q "[:.]${SHEET_HTTP_PORT}[[:space:]]"; then
  echo "Port ${SHEET_HTTP_PORT} is already in use. Set SHEET_HTTP_PORT to a free high port."
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

wait_for_rcon_ready 30 || {
  echo "[smoke] RCON socket did not become ready."
  tail -n 200 "$SERVER_STDOUT" || true
  exit 1
}

echo "[smoke] Starting local sheet fixture server on port ${SHEET_HTTP_PORT}..."
start_sheet_fixture_server || {
  echo "[smoke] Sheet fixture server failed to start."
  tail -n 200 "$SHEET_SERVER_LOG" || true
  exit 1
}

echo "[smoke] Importing EN/FR vocab from sheet URLs via RCON..."
rcon_cmd "addvocab en ${SMOKE_KEEP_DE_WORD} ${SMOKE_KEEP_EN_VALUE}" >/dev/null
rcon_cmd "addvocab fr ${SMOKE_KEEP_DE_WORD} ${SMOKE_KEEP_FR_VALUE}" >/dev/null

counts_before="$(
  python3 - "$DB_PATH" <<'PY'
import sqlite3
import sys

db_path = sys.argv[1]
conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute("SELECT COUNT(*) FROM vocab_de_en")
en_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM vocab_de_fr")
fr_count = cur.fetchone()[0]
conn.close()
print(f"{en_count} {fr_count}")
PY
)"
read -r en_count_before fr_count_before <<<"$counts_before"

rcon_cmd "setvocaburl en http://127.0.0.1:${SHEET_HTTP_PORT}/de_en.csv" >/dev/null
rcon_cmd "setvocaburl fr http://127.0.0.1:${SHEET_HTTP_PORT}/de_fr.csv" >/dev/null
rcon_cmd "importvocab en" >/dev/null
rcon_cmd "importvocab fr" >/dev/null

python3 - "$DB_PATH" "$en_count_before" "$fr_count_before" \
  "$SMOKE_KEEP_DE_WORD" "$SMOKE_KEEP_EN_VALUE" "$SMOKE_KEEP_FR_VALUE" \
  "$SMOKE_NEW_EN_DE_WORD" "$SMOKE_NEW_EN_VALUE" "$SMOKE_NEW_FR_DE_WORD" "$SMOKE_NEW_FR_VALUE" <<'PY'
import sqlite3
import sys

(
    db_path,
    en_count_before,
    fr_count_before,
    keep_de_word,
    keep_en_value,
    keep_fr_value,
    new_en_de_word,
    new_en_value,
    new_fr_de_word,
    new_fr_value,
) = sys.argv[1:]

en_count_before = int(en_count_before)
fr_count_before = int(fr_count_before)

conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute("SELECT COUNT(*) FROM vocab_de_en")
en_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM vocab_de_fr")
fr_count = cur.fetchone()[0]
cur.execute("SELECT en FROM vocab_de_en WHERE lower(de)=lower(?) ORDER BY id ASC LIMIT 1", (keep_de_word,))
keep_en_row = cur.fetchone()
cur.execute("SELECT fr FROM vocab_de_fr WHERE lower(de)=lower(?) ORDER BY id ASC LIMIT 1", (keep_de_word,))
keep_fr_row = cur.fetchone()
cur.execute("SELECT en FROM vocab_de_en WHERE lower(de)=lower(?) LIMIT 1", (new_en_de_word,))
new_en_row = cur.fetchone()
cur.execute("SELECT fr FROM vocab_de_fr WHERE lower(de)=lower(?) LIMIT 1", (new_fr_de_word,))
new_fr_row = cur.fetchone()
conn.close()

if en_count != en_count_before + 1:
    raise SystemExit(f"Expected EN count to increase by 1, before={en_count_before}, after={en_count}")
if fr_count != fr_count_before + 1:
    raise SystemExit(f"Expected FR count to increase by 1, before={fr_count_before}, after={fr_count}")
if keep_en_row is None or keep_en_row[0] != keep_en_value:
    raise SystemExit("Expected existing EN value to remain unchanged after import merge")
if keep_fr_row is None or keep_fr_row[0] != keep_fr_value:
    raise SystemExit("Expected existing FR value to remain unchanged after import merge")
if new_en_row is None or new_en_row[0] != new_en_value:
    raise SystemExit("Expected new EN sheet entry to be inserted")
if new_fr_row is None or new_fr_row[0] != new_fr_value:
    raise SystemExit("Expected new FR sheet entry to be inserted")
PY

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

wait_for_player_online 30 || {
  echo "[smoke] Bot did not appear as online player."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

echo "[smoke] Verifying playtime commands via RCON..."
rcon_cmd "playtime reset ${MC_USERNAME}" >/dev/null
rcon_cmd "playtime setlimit ${MC_USERNAME} ${SMOKE_PLAYTIME_LIMIT}" >/dev/null

playtime_status="$(
  rcon_cmd "playtime status ${MC_USERNAME}" | tr -d '\r'
)"
if [[ "$playtime_status" != *"used=0/${SMOKE_PLAYTIME_LIMIT} min"* ]]; then
  echo "[smoke] Unexpected playtime status after setlimit/reset: ${playtime_status}"
  exit 1
fi

rcon_cmd "playtime setused ${MC_USERNAME} ${SMOKE_PLAYTIME_USED}" >/dev/null
playtime_status="$(
  rcon_cmd "playtime status ${MC_USERNAME}" | tr -d '\r'
)"
if [[ "$playtime_status" != *"used=${SMOKE_PLAYTIME_USED}/${SMOKE_PLAYTIME_LIMIT} min"* ]]; then
  echo "[smoke] Unexpected playtime status after setused: ${playtime_status}"
  exit 1
fi

echo "[smoke] Security abuse check: SQL-like playtime limit payload must be rejected..."
playtime_abuse_response="$(
  rcon_cmd "playtime setlimit ${MC_USERNAME} 1;DROP_TABLE" | tr -d '\r'
)"
if [[ "$playtime_abuse_response" != *"Limit must be a positive integer or 'default'."* ]]; then
  echo "[smoke] Expected validation rejection for SQL-like setlimit payload."
  echo "[smoke] Response: ${playtime_abuse_response}"
  exit 1
fi

playtime_status_after_abuse="$(
  rcon_cmd "playtime status ${MC_USERNAME}" | tr -d '\r'
)"
if [[ "$playtime_status_after_abuse" != *"used=${SMOKE_PLAYTIME_USED}/${SMOKE_PLAYTIME_LIMIT} min"* ]]; then
  echo "[smoke] Playtime state changed unexpectedly after SQL-like RCON payload: ${playtime_status_after_abuse}"
  exit 1
fi

echo "[smoke] Security abuse check: player-triggered /playtime must be blocked..."
stop_bot_process
sleep 1
start_bot_process "/playtime setused ${MC_USERNAME} 9999" 2500 12

wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Playtime abuse-check bot did not connect."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_player_online 30 || {
  echo "[smoke] Playtime abuse-check bot did not appear online."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_bot_line "This command is restricted to RCON." 20 || {
  echo "[smoke] Expected player-side /playtime rejection was not observed in bot output."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

playtime_status_after_player_abuse="$(
  rcon_cmd "playtime status ${MC_USERNAME}" | tr -d '\r'
)"
if [[ "$playtime_status_after_player_abuse" != *"used=${SMOKE_PLAYTIME_USED}/${SMOKE_PLAYTIME_LIMIT} min"* ]]; then
  echo "[smoke] Playtime state changed unexpectedly after player-side /playtime abuse: ${playtime_status_after_player_abuse}"
  exit 1
fi

stop_bot_process
sleep 1
start_bot_process "" 0 240
wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Bot did not reconnect after playtime abuse check."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}
wait_for_player_online 30 || {
  echo "[smoke] Bot did not appear online after playtime abuse reconnect."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

echo "[smoke] Security abuse check: player-triggered /questnow must be blocked..."
quest_start_before="$(rg -c "\\[VocabularyQuestPlugin\\] Started quest for" "$SERVER_LOG" || true)"
stop_bot_process
sleep 1
start_bot_process "/questnow" 2500 12

wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Abuse-check bot did not connect."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_player_online 30 || {
  echo "[smoke] Abuse-check bot did not appear online."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

sleep 3
quest_start_after="$(rg -c "\\[VocabularyQuestPlugin\\] Started quest for" "$SERVER_LOG" || true)"
if [[ "$quest_start_after" != "$quest_start_before" ]]; then
  echo "[smoke] Player-side /questnow unexpectedly started a quest."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
fi

stop_bot_process
sleep 1
start_bot_process "" 0 240
wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Bot did not reconnect after abuse check."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}
wait_for_player_online 30 || {
  echo "[smoke] Bot did not appear online after reconnect."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

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

echo "[smoke] Security abuse check: overlong /answer must be rejected..."
stop_bot_process
sleep 1
long_answer="$(printf 'a%.0s' $(seq 1 80))"
start_bot_process "/answer ${long_answer}" 2500 12

wait_for_bot_line "Connected\\." 25 || {
  echo "[smoke] Overlong-answer bot did not connect."
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_player_online 30 || {
  echo "[smoke] Overlong-answer bot did not appear online."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

wait_for_bot_line "Antwort ist zu lang" 20 || {
  echo "[smoke] Expected overlong-answer rejection message not found in bot log."
  tail -n 200 "$SERVER_LOG" || true
  echo "----- bot log -----"
  tail -n 200 "$BOT_LOG" || true
  exit 1
}

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
