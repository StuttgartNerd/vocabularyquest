# Mindcraft Trainer

Paper server + plugin + Java chatbot for vocabulary quests (DE->EN / DE->FR), with SQLite persistence.

## What You Get

- Local Paper server (`paper/`) in offline mode for local testing.
- Paper plugin (`plugin-vocabulary-quest/`) with:
  - player join tracking in SQLite
  - vocabulary tables (`vocab_de_en`, `vocab_de_fr`)
  - timed quest events (every 3-10 minutes)
  - rewards (1 emerald for correct first-time answer per player+word)
- Java chatbot (`chat-bot/`) to simulate player chat and command input.
- Helper scripts in `scripts/`.

## Prerequisites

- Java 21+
- `curl`
- `unzip`
- `jq`
- `python3` (for RCON helper)

## Quick Start

1. Download Paper (recommended: `1.21.7`):

```bash
./scripts/download-paper.sh 1.21.7
```

2. Build plugin and bot:

```bash
./scripts/gradle.sh :plugin-vocabulary-quest:build :chat-bot:installDist
```

3. Copy plugin jar into Paper:

```bash
./scripts/copy-plugin.sh
```

4. Start server:

```bash
./scripts/start-paper.sh
```

5. Start chatbot in another terminal:

```bash
./scripts/run-bot.sh --host 127.0.0.1 --port 25565 --username ChatBot
```

When connected, type normal chat lines and press Enter.  
Type `/quit` to disconnect the bot.

Plugin-originated broadcasts are emitted with chat identity `Jenkins`.

## Connect From Minecraft Client

- Add server: `127.0.0.1:25565`
- This setup is local/offline (`online-mode=false` in `paper/server.properties`).

## Plugin Commands

Player commands:

- `/answer <antwort>` - answer active quest

RCON-only admin commands:

- `/questnow` - starts quest immediately
- `/dbdump` - dumps users/vocabulary/rewards/attempts to server log
- `/flushanswers` - clears reward/attempt tracking tables
- `/flushvocab <en|fr>` - clears one vocabulary table (`de_en` or `de_fr`) and resets reward/attempt tracking
- `/addvocab <en|fr> <de_wort> <uebersetzung>` - inserts one vocabulary row

## Vocabulary CSV Files

On first plugin startup, default CSV files are copied to:

- `paper/plugins/VocabularyQuestPlugin/vocabulary/de_en.csv`
- `paper/plugins/VocabularyQuestPlugin/vocabulary/de_fr.csv`

Format:

```csv
de,en
haus,house
baum,tree
```

```csv
de,fr
haus,maison
baum,arbre
```

After editing CSV files, restart server to reload them.

## RCON Usage

RCON is enabled by default in `paper/server.properties`:

- `enable-rcon=true`
- `rcon.port=25575`
- `rcon.password=dev-rcon-password`

Send commands:

```bash
python3 ./scripts/rcon-command.py --host 127.0.0.1 --port 25575 --password dev-rcon-password "questnow"
python3 ./scripts/rcon-command.py --host 127.0.0.1 --port 25575 --password dev-rcon-password "flushanswers"
python3 ./scripts/rcon-command.py --host 127.0.0.1 --port 25575 --password dev-rcon-password "flushvocab en"
python3 ./scripts/rcon-command.py --host 127.0.0.1 --port 25575 --password dev-rcon-password "addvocab en hund dog"
```

## Automated Smoke Test (Layer 3)

Runs end-to-end:

- build + copy plugin
- start Paper
- connect ChatBot
- trigger DB dump
- start quest
- answer quest using DB lookup
- verify reward row in SQLite

```bash
./scripts/integration-smoke.sh
```

## Useful Paths

- Server log: `paper/logs/latest.log`
- Plugin DB: `paper/plugins/VocabularyQuestPlugin/mindcraft.db`
- Plugin config/data folder: `paper/plugins/VocabularyQuestPlugin/`

## License

This project is licensed under `GPL-3.0`.

Copyright (C) 2026 Steffen Pfendtner `<steffen@pfendtner.de>`
