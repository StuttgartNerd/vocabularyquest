# Mindcraft Trainer

Paper plugin for vocabulary quests (DE->EN / DE->FR) with SQLite persistence and daily playtime limits.

## Install (Server Owners)
1. Download the latest plugin jar from GitHub Releases.
2. Copy the jar to your Paper server `plugins/` directory.
3. Start or restart the server.

Plugin name: `VocabularyQuestPlugin`

## For Players
- `/answer <antwort>`: answer the currently active vocabulary quest.
- `/msg jenkins <antwort>`: alternate way to answer in private chat.
- Daily playtime limits may disconnect players when their daily limit is reached.

## For Admins (RCON Only)
All admin commands are restricted to RCON senders.

### Vocabulary/Admin Commands
- `/questnow`: start a quest immediately.
- `/dbdump`: dump users/vocabulary/rewards/attempts to the server log.
- `/flushanswers`: clear reward and attempt tracking tables.
- `/flushvocab <en|fr>`: clear one vocabulary table (`de_en` or `de_fr`) and reset reward/attempt tracking.
- `/addvocab <en|fr> <de_wort> <uebersetzung>`: insert one vocabulary row.
- `/setvocaburl <en|fr> <url>`: store sheet CSV URL in plugin config.
- `/importvocab <en|fr>`: merge one language from configured sheet URL.

### Playtime Commands
- `/playtime status <player>`: show today's used minutes and effective daily limit.
- `/playtime setused <player> <minutes>`: set today's used minutes.
- `/playtime setlimit <player> <minutes|default>`: set or clear per-player daily limit override.
- `/playtime reset <player|all>`: reset today's used minutes for one player or everyone.

## Playtime Configuration
In `plugins/VocabularyQuestPlugin/config.yml`:

```yaml
playtime:
  enabled: true
  default_daily_limit_minutes: 120
  kick_message: "Daily playtime limit reached ({used}/{limit} min). Come back tomorrow."
```

Notes:
- `default_daily_limit_minutes` applies when no per-player override is set.
- Per-player overrides are managed via `/playtime setlimit`.
- Daily usage resets automatically when the date changes.

## Playtime Quick Ops (RCON Copy/Paste)
Common admin sequences for fast playtime operations.

### 1) Inspect one player
```text
playtime status Steve
```

### 2) Set a strict temporary limit for one player
```text
playtime setlimit Steve 30
playtime status Steve
```

### 3) Remove player override and return to server default
```text
playtime setlimit Steve default
playtime status Steve
```

### 4) Correct today's used time manually
```text
playtime setused Steve 45
playtime status Steve
```

### 5) Reset one player for today
```text
playtime reset Steve
playtime status Steve
```

### 6) Reset everyone for today
```text
playtime reset all
```

## Google Sheets Import (DE->EN / DE->FR)
The plugin imports from Google Sheets CSV export URLs.

- one URL for `en` (DE->EN)
- one URL for `fr` (DE->FR)
- URLs are persisted in `paper/plugins/VocabularyQuestPlugin/config.yml`

### 1) Create Google Sheets CSV export URLs
Use URLs like:
- `https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-en>`
- `https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-fr>`

You can use:
- two tabs in one spreadsheet (different `gid`)
- or two separate spreadsheets

### 2) Required Sheet Content
DE->EN sheet:

```csv
de,en
haus,house
baum,tree
```

DE->FR sheet:

```csv
de,fr
haus,maison
baum,arbre
```

Parsing rules:
- first column is `de`, second is `en` or `fr`
- empty lines and `#` comment lines are ignored
- malformed rows are skipped
- header row is optional and ignored when present

### 3) Configure via RCON
```text
setvocaburl en https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-en>
setvocaburl fr https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-fr>
```

### 4) Run Import via RCON
```text
importvocab en
importvocab fr
```

If URLs are configured, the plugin also runs one import attempt automatically on server startup.

### 5) Import Semantics
Import is merge-only by German word (`de`, case-insensitive):
- new `de` words are inserted
- existing `de` words are kept as-is (not overwritten)
- reward/attempt counters are preserved

## RCON Basics
Set these in `server.properties`:
- `enable-rcon=true`
- `rcon.port=25575`
- `rcon.password=<your-password>`

## Useful Paths
- Plugin data folder: `plugins/VocabularyQuestPlugin/`
- Plugin DB: `plugins/VocabularyQuestPlugin/mindcraft.db`
- Plugin config: `plugins/VocabularyQuestPlugin/config.yml`

## License
This project is licensed under `GPL-3.0`.
