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
- Load DE->EN / DE->FR vocab from CSV on startup
- Schedule vocabulary quests at random intervals (3-10 minutes)
- Evaluate answers and grant one emerald for first correct answer per player+word
- Expose RCON-only admin/testing commands
- Broadcast plugin-originated chat as identity `Jenkins`

### Storage Layer
Class: `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStore.java`

SQLite DB path:
- `paper/plugins/VocabularyQuestPlugin/mindcraft.db`

Tables:
- `users`
- `vocab_de_en`
- `vocab_de_fr`
- `player_vocab_rewards`
- `vocab_attempts`

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
- `/questnow` (currently callable in-game/console)

### RCON-only
- `/dbdump`
- `/flushanswers`
- `/addvocab <en|fr> <de_wort> <uebersetzung>`

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

## Testing Stages
### Stage 1: Unit (fast)
- File: `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStoreTest.java`
- Focus: schema, CRUD, reward/attempt tracking, weighted selection

### Stage 2: Plugin-level (MockBukkit)
- File: `plugin-vocabulary-quest/src/test/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPluginMockBukkitTest.java`
- Focus: command permissions, join event insert, quest+answer flow

### Stage 3: Integration smoke (Paper runtime)
- Script: `scripts/integration-smoke.sh`
- Flow: build -> start Paper -> connect bot -> DB dump -> questnow -> bot answer -> DB assert
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

## High-Value Files
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPlugin.java`
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStore.java`
- `plugin-vocabulary-quest/src/main/resources/plugin.yml`
- `chat-bot/src/main/java/dev/snpr/chatbot/TestChatBot.java`
- `scripts/integration-smoke.sh`
- `scripts/rcon-command.py`
