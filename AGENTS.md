# AGENTS.md

## Project Snapshot
Minecraft Paper plugin project with a Java chatbot test client.

- Plugin module: `plugin-vocabulary-quest`
- Plugin name: `VocabularyQuestPlugin`
- Plugin Java package: `io.github.stuttgartnerd.vocabularyquest`
- Chatbot module: `chat-bot`
- Target Paper version: `1.21.7`

## Repository Structure
- `plugin-vocabulary-quest/`: Paper plugin source, tests, resources
- `chat-bot/`: MCProtocolLib chatbot used for automated interaction testing
- `scripts/`: local automation (`download-paper`, build, run, smoke test, RCON helper)
- `paper/`: local runtime server folder (ignored in git)

## Runtime Architecture
### Plugin
Main class: `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPlugin.java`

Responsibilities:
- Track joined users in SQLite
- Track and enforce per-player daily playtime limits
- Load DE->EN / DE->FR vocab from CSV on startup
- Optionally import DE->EN / DE->FR vocab from Google Sheets CSV URLs via RCON
- If sheet URLs are configured, attempt one EN/FR sheet merge on startup
- Schedule vocabulary quests at random intervals (3-10 minutes)
- Evaluate answers and grant one emerald for first correct answer per player+word
- Expose RCON-only admin/testing commands
- Broadcast plugin-originated chat as identity `Jenkins`

### Storage Layer
Class: `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStore.java`

CSV import utility class:
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyCsvImport.java`

SQLite DB path:
- `paper/plugins/VocabularyQuestPlugin/mindcraft.db`

Tables:
- `users`
- `vocab_de_en`
- `vocab_de_fr`
- `player_vocab_rewards`
- `vocab_attempts`
- `player_playtime`

Sheet import semantics:
- `/importvocab <en|fr>` is merge-only (append missing entries by `de`, case-insensitive)
- Existing `de` entries are not overwritten
- Reward/attempt counters remain unchanged
- Startup performs at most one merge attempt per configured language (`en`, `fr`)

Quest selection:
- Only entries reward-eligible for online players are considered
- Preference is weighted toward lower attempt count

### Chatbot
Main class: `chat-bot/src/main/java/dev/snpr/chatbot/TestChatBot.java`

Used for:
- Joining server as `ChatBot`
- Sending chat/commands
- Supporting non-interactive mode for smoke tests (`--no-stdin`, `--send-on-connect`, hold window)

## Player/Admin Interface
### Player-facing
- `/answer <antwort>`
- `/msg jenkins <antwort>` (also supports aliases handled in parser)

### RCON-only
- `/questnow`
- `/dbdump`
- `/flushanswers`
- `/flushvocab <en|fr>`
- `/addvocab <en|fr> <de_wort> <uebersetzung>`
- `/setvocaburl <en|fr> <url>`
- `/importvocab <en|fr>`
- `/playtime status <player>`
- `/playtime setused <player> <minutes>`
- `/playtime setlimit <player> <minutes|default>`
- `/playtime reset <player|all>`

## Playtime Behavior
- Enabled by config key `playtime.enabled` (default `true`).
- Daily default limit comes from `playtime.default_daily_limit_minutes`.
- Effective limit is either default limit or per-player override set via `/playtime setlimit`.
- Daily usage increments once per minute for online players.
- In the final 5 minutes, each player receives one per-minute Jenkins chat warning countdown (`5` to `1`).
- Limit is enforced on login and during runtime; player is kicked when `used >= limit`.
- Daily usage resets automatically when calendar date changes.
- Kick message template comes from `playtime.kick_message` and supports `{used}` / `{limit}` placeholders.

## Developer + AI Tool Guardrails
- When changing command parsing or RCON restrictions, update tests in:
  - `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPluginMockBukkitTest.java`
- When changing persistence/schema/playtime math, update tests in:
  - `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStoreTest.java`
- When changing runtime command/security behavior, update:
  - `scripts/integration-smoke.sh`
- Keep abuse/security coverage for:
  - SQL-like payloads
  - player-side attempts to run RCON-only commands
  - overlong input validation
- If playtime logic changes, validate all three stages (unit, MockBukkit, integration smoke) before considering work complete.

## Jenkins Chat Identity
Plugin broadcasts are formatted as:
- `<Jenkins> ...`

This is used for:
- Bot echo messages
- Quest announcement
- Correct/incorrect result broadcasts
- Solution reveal and timeout messaging

## Startup/Bootstrap Flow
1. Ensure Paper exists (`scripts/download-paper.sh 1.21.7`)
2. Build plugin and bot
3. Copy plugin jar into `paper/plugins`
4. Start Paper
5. Optionally start chatbot

Common commands:
```bash
./scripts/gradle.sh :plugin-vocabulary-quest:build :chat-bot:installDist
./scripts/copy-plugin.sh
./scripts/start-paper.sh
./scripts/run-bot.sh --host 127.0.0.1 --port 25565 --username ChatBot
```

Sheet import config (persisted in plugin data folder):
- `paper/plugins/VocabularyQuestPlugin/config.yml`
- keys:
  - `vocab_import.sheet_urls.en`
  - `vocab_import.sheet_urls.fr`
  - `vocab_import.http.connect_timeout_seconds`
  - `vocab_import.http.read_timeout_seconds`
  - `playtime.enabled`
  - `playtime.default_daily_limit_minutes`
  - `playtime.kick_message`

## Testing Stages
### Stage 1: Unit (fast)
- File: `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStoreTest.java`
- File: `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/VocabularyCsvImportTest.java`
- Focus: schema/CRUD, reward/attempt tracking, playtime persistence/reset/override behavior, SQL-like abuse payload resilience, weighted selection, merge-import semantics, HTTP CSV parsing

### Stage 2: Plugin-level (MockBukkit)
- File: `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPluginMockBukkitTest.java`
- Focus: command permissions, playtime command validation/security, join/login playtime behavior, quest+answer flow, `/setvocaburl` + `/importvocab` behavior

### Stage 3: Integration smoke (Paper runtime)
- Script: `scripts/integration-smoke.sh`
- Flow: build -> start Paper -> start local Python CSV fixture server (high port) ->
  set sheet URLs via RCON -> import EN/FR via RCON (merge assertions in SQLite) ->
  connect bot -> playtime RCON checks -> playtime abuse checks -> DB dump -> questnow -> bot answer -> DB assert
- Auto-bootstrap: if `paper/paper.jar` is missing, script calls `download-paper.sh` automatically

## CI/Automation Commands
```bash
./scripts/gradle.sh :plugin-vocabulary-quest:test :plugin-vocabulary-quest:build
./scripts/integration-smoke.sh
```

## Local Runtime Notes
- `paper/` is intentionally git-ignored
- Smoke test expects RCON enabled and reachable
- Default RCON values are read from env in `scripts/integration-smoke.sh`
- Smoke test now also validates playtime RCON command behavior and abuse rejection paths.

## High-Value Files
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPlugin.java`
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStore.java`
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyCsvImport.java`
- `plugin-vocabulary-quest/src/main/resources/plugin.yml`
- `plugin-vocabulary-quest/src/main/resources/config.yml`
- `chat-bot/src/main/java/dev/snpr/chatbot/TestChatBot.java`
- `scripts/integration-smoke.sh`
- `scripts/rcon-command.py`
