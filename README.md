# Mindcraft Trainer

Paper plugin for vocabulary quests (DE->EN / DE->FR) with SQLite persistence.

## Install Plugin (End Users)

1. Download the latest plugin jar from GitHub Releases.
2. Copy the jar to your Paper server `plugins/` directory.
3. Start or restart the server.

Plugin name: `VocabularyQuestPlugin`

## Commands

Player command:

- `/answer <antwort>` - answer active quest

RCON-only admin commands:

- `/questnow` - starts quest immediately
- `/dbdump` - dumps users/vocabulary/rewards/attempts to server log
- `/flushanswers` - clears reward/attempt tracking tables
- `/flushvocab <en|fr>` - clears one vocabulary table (`de_en` or `de_fr`) and resets reward/attempt tracking
- `/addvocab <en|fr> <de_wort> <uebersetzung>` - inserts one vocabulary row
- `/setvocaburl <en|fr> <url>` - stores sheet CSV URL in plugin config
- `/importvocab <en|fr>` - merges one language from configured sheet URL

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

### 2) Required sheet content

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

Run these commands in your RCON client:

```text
setvocaburl en https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-en>
setvocaburl fr https://docs.google.com/spreadsheets/d/<sheet-id>/export?format=csv&gid=<gid-fr>
```

### 4) Run import via RCON

```text
importvocab en
importvocab fr
```

If URLs are configured, the plugin also runs one import attempt automatically on server startup.

### 5) How import is handled

Import is merge-only by German word (`de`, case-insensitive):

- new `de` words are inserted
- existing `de` words are kept as-is (not overwritten)
- reward/attempt counters are preserved

This keeps existing player progress valid.

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
