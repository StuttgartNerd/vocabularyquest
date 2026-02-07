# AGENTS.md

## Goal
Run a local Minecraft Paper server and Java chatbot, verify end-to-end chat delivery, and iterate on the vocabulary-quest plugin with testable workflows.

## Version Compatibility
- Use Paper `1.21.7` with chat-bot dependency `org.geysermc.mcprotocollib:protocol:1.21.7-1`.
- If Paper is older (for example `1.21.4`), the bot disconnects with an outdated-server error.

## Tasks
1. Download Paper and prepare local server files.
2. Build and copy the plugin into `paper/plugins`.
3. Start the Paper server and validate plugin startup.
4. Run the MCProtocolLib bot and test chat send/receive.
5. Validate SQLite-backed features (users, vocabulary, quest rewards).
6. Add automated tests in 3 layers (unit, plugin-level, integration smoke).

## Current Plugin Features
- Chat echo for `ChatBot` messages.
- SQLite database in `paper/plugins/VocabularyQuestPlugin/mindcraft.db`.
- User tracking table updated on join.
- Vocabulary tables:
  - `vocab_de_en`
  - `vocab_de_fr`
- CSV vocabulary bootstrap on startup from:
  - `paper/plugins/VocabularyQuestPlugin/vocabulary/de_en.csv`
  - `paper/plugins/VocabularyQuestPlugin/vocabulary/de_fr.csv`
- Timed vocabulary quest event:
  - Random interval: 3 to 10 minutes
  - Quest timeout: 2 minutes
  - Weighted vocab selection prefers entries with fewer attempts
  - Selection is constrained to reward-eligible online players
  - Correct answer reward: 1 emerald (once per player+vocab)
- Quest answer input:
  - `/answer <antwort>`
  - private message parse: `/msg jenkins <antwort>`

## Standard Workflow
```bash
./scripts/download-paper.sh 1.21.7
./scripts/gradle.sh :plugin-vocabulary-quest:build :chat-bot:build
./scripts/copy-plugin.sh
./scripts/start-paper.sh
```

In another terminal:
```bash
./scripts/run-bot.sh --host 127.0.0.1 --port 25565 --username ChatBot
```

## Admin and Testing Commands
- In-game/console:
  - `/questnow` starts a quest immediately (for fast testing)
- RCON-only commands:
  - `/dbdump` dumps users, vocab, rewards, attempts to log
  - `/flushanswers` clears `player_vocab_rewards` and `vocab_attempts`
  - `/addvocab <en|fr> <de_wort> <uebersetzung>` inserts vocab row

## RCON Setup
- `paper/server.properties` must include:
  - `enable-rcon=true`
  - `rcon.port=25575`
  - `rcon.password=<password>`
- Use RCON for CI/admin-only mutation commands, not player-facing flow.

## Verify Connection and Message Delivery
- Paper startup complete when console shows: `Done (... )! For help, type "help"`.
- Bot connection confirmed by `ChatBot joined the game` in `paper/logs/latest.log`.
- Chat delivery confirmed by lines like:
  - `[Not Secure] <ChatBot> your-message`

Quick check:
```bash
rg -n "ChatBot joined the game|<ChatBot>" paper/logs/latest.log
```

## Legacy Command Set
```bash
./scripts/download-paper.sh 1.21.7
./scripts/gradle.sh :plugin-vocabulary-quest:build
./scripts/copy-plugin.sh
./scripts/start-paper.sh
MC_HOST=127.0.0.1 MC_PORT=25565 MC_USERNAME=ChatBot ./scripts/gradle.sh :chat-bot:run
```

## Test Strategy (Planned)
1. Unit tests (fast, no Bukkit):
   - `SQLiteStore` schema/init and CRUD behavior
   - reward claim idempotency
   - answer tracking flush
   - weighted quest selection with online/offline eligibility
2. Plugin-level tests (MockBukkit):
   - command access rules (`RCON-only` denied for players/console)
   - join event user insert
   - quest start/answer flow
3. Integration smoke tests (Paper runtime):
   - startup plugin load
   - `questnow` path
   - bot answer path
   - DB/log assertions

## CI Flow (Target)
1. PR fast lane:
   - `./scripts/gradle.sh :plugin-vocabulary-quest:test :plugin-vocabulary-quest:build`
2. Integration lane (separate job/nightly):
   - start Paper
   - run scripted quest + bot scenario
   - assert log/DB outcomes

## Files of Interest
- `scripts/download-paper.sh`
- `scripts/start-paper.sh`
- `scripts/run-bot.sh`
- `chat-bot/src/main/java/dev/snpr/chatbot/TestChatBot.java`
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/VocabularyQuestPlugin.java`
- `plugin-vocabulary-quest/src/main/java/io/github/stuttgartnerd/vocabularyquest/SQLiteStore.java`
- `plugin-vocabulary-quest/src/main/resources/plugin.yml`
- `paper/logs/latest.log`
