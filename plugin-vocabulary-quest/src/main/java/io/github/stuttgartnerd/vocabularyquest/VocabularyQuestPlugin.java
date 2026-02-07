package io.github.stuttgartnerd.vocabularyquest;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

public class VocabularyQuestPlugin extends JavaPlugin implements Listener {
    private static final String BOT_USERNAME = "ChatBot";
    private static final String PLUGIN_CHAT_NAME = "Jenkins";
    private static final String DB_DUMP_COMMAND = "dbdump";
    private static final String FLUSH_ANSWERS_COMMAND = "flushanswers";
    private static final String FLUSH_VOCAB_COMMAND = "flushvocab";
    private static final String ADD_VOCAB_COMMAND = "addvocab";
    private static final String SET_VOCAB_URL_COMMAND = "setvocaburl";
    private static final String IMPORT_VOCAB_COMMAND = "importvocab";
    private static final String PLAYTIME_COMMAND = "playtime";
    private static final String ANSWER_COMMAND = "answer";
    private static final String QUEST_NOW_COMMAND = "questnow";
    private static final String CONFIG_SHEET_URL_EN = "vocab_import.sheet_urls.en";
    private static final String CONFIG_SHEET_URL_FR = "vocab_import.sheet_urls.fr";
    private static final String CONFIG_HTTP_CONNECT_TIMEOUT_SECONDS = "vocab_import.http.connect_timeout_seconds";
    private static final String CONFIG_HTTP_READ_TIMEOUT_SECONDS = "vocab_import.http.read_timeout_seconds";
    private static final String CONFIG_PLAYTIME_ENABLED = "playtime.enabled";
    private static final String CONFIG_PLAYTIME_DEFAULT_DAILY_LIMIT_MINUTES = "playtime.default_daily_limit_minutes";
    private static final String CONFIG_PLAYTIME_KICK_MESSAGE = "playtime.kick_message";
    private static final long QUEST_TIMEOUT_TICKS = 2L * 60L * 20L;
    private static final int QUEST_DELAY_MIN_SECONDS = 3 * 60;
    private static final int QUEST_DELAY_MAX_SECONDS = 10 * 60;
    private static final int MIN_VOCAB_ENTRIES_FOR_TIMER_QUESTS = 10;
    private static final int MAX_ANSWER_LENGTH = 64;
    private static final int MAX_VOCAB_TERM_LENGTH = 64;
    private static final int DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_HTTP_READ_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_PLAYTIME_DAILY_LIMIT_MINUTES = 120;
    private static final int PLAYTIME_WARNING_WINDOW_MINUTES = 5;
    private static final String DEFAULT_PLAYTIME_KICK_MESSAGE = "Daily playtime limit reached ({used}/{limit} min). "
            + "Come back tomorrow.";

    private final Random random = new Random();
    private SQLiteStore sqliteStore;
    private BukkitTask scheduledQuestTask;
    private BukkitTask questTimeoutTask;
    private BukkitTask playtimeTrackerTask;
    private ActiveQuest activeQuest;

    private record ActiveQuest(String vocabTable, String deWord, String answer) {
    }

    private record ImportSummary(int sourceRows, int inserted, int skippedExisting) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            initializeStorage();
        } catch (IOException | SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite storage.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        importConfiguredSheetsOnStartup();

        getServer().getPluginManager().registerEvents(this, this);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            registerPlayerForPlaytime(onlinePlayer.getName());
            enforcePlaytimeLimit(onlinePlayer);
        }
        startPlaytimeTracker();
        scheduleNextQuest();
        getLogger().info("VocabularyQuestPlugin enabled.");
    }

    @Override
    public void onDisable() {
        cancelScheduledQuest();
        cancelQuestTimeout();
        cancelPlaytimeTracker();

        if (sqliteStore != null) {
            try {
                sqliteStore.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Failed to close SQLite connection cleanly.", e);
            }
        }

        getLogger().info("VocabularyQuestPlugin disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (DB_DUMP_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            if (sqliteStore == null) {
                sender.sendMessage("SQLite store is not available.");
                return true;
            }

            try {
                SQLiteStore.DumpSummary summary = sqliteStore.dumpToLog(getLogger());
                sender.sendMessage("Dumped SQLite tables to log (" + formatDumpSummary(summary) + ").");
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to dump SQLite tables.", e);
                sender.sendMessage("Failed to dump SQLite tables. Check server log.");
            }

            return true;
        }

        if (FLUSH_ANSWERS_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            try {
                sqliteStore.clearAnswerTracking();
                sender.sendMessage("Cleared reward/attempt tracking tables.");
                getLogger().info("RCON cleared answer tracking tables.");
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to clear answer tracking tables.", e);
                sender.sendMessage("Failed to clear answer tracking tables.");
            }
            return true;
        }

        if (FLUSH_VOCAB_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            if (args.length != 1) {
                sender.sendMessage("Usage: /flushvocab <en|fr>");
                return true;
            }

            String language = sanitizeUserInput(args[0]).toLowerCase(Locale.ROOT);
            if (!"en".equals(language) && !"fr".equals(language)) {
                sender.sendMessage("Language must be en or fr.");
                return true;
            }

            try {
                int removedEntries = sqliteStore.clearVocabularyLanguageAndTracking(language);
                sender.sendMessage("Cleared de_" + language + " (" + removedEntries
                        + " entries) and reset reward/attempt tracking tables.");
                getLogger().info("RCON cleared de_" + language + " (" + removedEntries
                        + " entries) and reset reward/attempt tracking tables.");
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to clear de_" + language + " vocabulary table.", e);
                sender.sendMessage("Failed to clear de_" + language + " vocabulary table.");
            }
            return true;
        }

        if (ADD_VOCAB_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            if (args.length < 3) {
                sender.sendMessage("Usage: /addvocab <en|fr> <de_wort> <uebersetzung>");
                return true;
            }

            String language = sanitizeUserInput(args[0]).toLowerCase(Locale.ROOT);
            String deWord = sanitizeUserInput(args[1]);
            String translatedWord = sanitizeUserInput(
                    String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
            );

            if (deWord.isEmpty() || translatedWord.isEmpty()) {
                sender.sendMessage("Both source and target words are required.");
                return true;
            }

            if (deWord.length() > MAX_VOCAB_TERM_LENGTH || translatedWord.length() > MAX_VOCAB_TERM_LENGTH) {
                sender.sendMessage("Vocabulary terms must be <= " + MAX_VOCAB_TERM_LENGTH + " characters.");
                return true;
            }

            if (!"en".equals(language) && !"fr".equals(language)) {
                sender.sendMessage("Language must be en or fr.");
                return true;
            }

            try {
                sqliteStore.insertVocabularyEntry(language, deWord, translatedWord);
                sender.sendMessage("Inserted vocabulary pair into de_" + language + ": " + deWord + " -> "
                        + translatedWord);
                getLogger().info("RCON inserted vocabulary pair into de_" + language + ": "
                        + deWord + " -> " + translatedWord);
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to insert vocabulary pair.", e);
                sender.sendMessage("Failed to insert vocabulary pair.");
            }

            return true;
        }

        if (SET_VOCAB_URL_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage("Usage: /setvocaburl <en|fr> <url>");
                return true;
            }

            String language = sanitizeUserInput(args[0]).toLowerCase(Locale.ROOT);
            if (!isSupportedImportLanguage(language)) {
                sender.sendMessage("Language must be en or fr.");
                return true;
            }

            String url = sanitizeUserInput(
                    String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
            );
            if (!isValidHttpUrl(url)) {
                sender.sendMessage("URL must be a valid http(s) URL.");
                return true;
            }

            getConfig().set(configPathForSheetUrl(language), url);
            saveConfig();

            sender.sendMessage("Set sheet URL for de_" + language + ".");
            getLogger().info("RCON configured sheet URL for de_" + language + ": " + url);
            return true;
        }

        if (IMPORT_VOCAB_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            if (args.length != 1) {
                sender.sendMessage("Usage: /importvocab <en|fr>");
                return true;
            }

            String language = sanitizeUserInput(args[0]).toLowerCase(Locale.ROOT);
            if (!isSupportedImportLanguage(language)) {
                sender.sendMessage("Language must be en or fr.");
                return true;
            }

            String sourceUrl = getConfiguredSheetUrl(language);
            if (sourceUrl.isBlank()) {
                sender.sendMessage("No sheet URL configured for de_" + language + ". Use /setvocaburl first.");
                return true;
            }

            try {
                ImportSummary summary = mergeVocabularyFromSheet(language, sourceUrl);
                if (summary.sourceRows() == 0) {
                    sender.sendMessage("Import aborted: source contains zero vocabulary entries.");
                    return true;
                }

                sender.sendMessage("Merged de_" + language + " from sheet: added " + summary.inserted()
                        + " new entries, skipped " + summary.skippedExisting() + " existing.");
                getLogger().info("RCON merged de_" + language + " from sheet URL: added=" + summary.inserted()
                        + ", skippedExisting=" + summary.skippedExisting() + ", source=" + sourceUrl);
            } catch (IOException | SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to import de_" + language + " from sheet URL.", e);
                sender.sendMessage("Failed to import de_" + language + ". Check server log.");
            }
            return true;
        }

        if (PLAYTIME_COMMAND.equalsIgnoreCase(command.getName())) {
            return handlePlaytimeCommand(sender, args);
        }

        if (ANSWER_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can answer quests.");
                return true;
            }

            if (args.length == 0) {
                player.sendMessage("Nutze: /answer <Antwort>");
                return true;
            }

            String answer = sanitizeUserInput(String.join(" ", args));
            if (answer.length() > MAX_ANSWER_LENGTH) {
                player.sendMessage("Antwort ist zu lang (max " + MAX_ANSWER_LENGTH + " Zeichen).");
                return true;
            }

            handleQuestAnswer(player, answer);
            return true;
        }

        if (QUEST_NOW_COMMAND.equalsIgnoreCase(command.getName())) {
            if (!isRconSender(sender)) {
                sender.sendMessage("This command is restricted to RCON.");
                return true;
            }

            boolean started = startVocabularyQuest();
            if (started) {
                sender.sendMessage("Vokabel-Quest wurde gestartet.");
            } else {
                sender.sendMessage("Quest konnte nicht gestartet werden (evtl. schon aktiv).");
            }
            return true;
        }

        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!BOT_USERNAME.equalsIgnoreCase(player.getName())) {
            return;
        }

        String inbound = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (inbound.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> broadcastAsJenkins(inbound));
        getLogger().info("Echoed bot message: " + inbound);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!isPlaytimeLimitEnabled()) {
            return;
        }

        String username = sanitizeUserInput(event.getPlayer().getName());
        if (username.isBlank()) {
            return;
        }

        registerPlayerForPlaytime(username);

        SQLiteStore.PlayerPlaytime playtime = getPlayerPlaytime(username);
        if (playtime == null) {
            return;
        }

        if (playtime.dailyUsedMinutes() >= playtime.effectiveLimitMinutes()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    buildPlaytimeKickMessage(playtime.dailyUsedMinutes(), playtime.effectiveLimitMinutes()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        registerPlayerForPlaytime(event.getPlayer().getName());
        enforcePlaytimeLimit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrivateMessageCommand(PlayerCommandPreprocessEvent event) {
        String answer = parsePrivatePluginAnswer(event.getMessage());
        if (answer == null) {
            return;
        }

        event.setCancelled(true);
        String sanitizedAnswer = sanitizeUserInput(answer);
        if (sanitizedAnswer.isBlank()) {
            event.getPlayer().sendMessage("Nutze: /msg jenkins <Antwort>");
            return;
        }

        if (sanitizedAnswer.length() > MAX_ANSWER_LENGTH) {
            event.getPlayer().sendMessage("Antwort ist zu lang (max " + MAX_ANSWER_LENGTH + " Zeichen).");
            return;
        }

        handleQuestAnswer(event.getPlayer(), sanitizedAnswer);
    }

    private void initializeStorage() throws IOException, SQLException {
        Path dataDir = getDataFolder().toPath();
        Path vocabularyDir = dataDir.resolve("vocabulary");
        Files.createDirectories(vocabularyDir);

        Path deEnFile = vocabularyDir.resolve("de_en.csv");
        Path deFrFile = vocabularyDir.resolve("de_fr.csv");
        ensureDefaultResource("vocabulary/de_en.csv", deEnFile);
        ensureDefaultResource("vocabulary/de_fr.csv", deFrFile);

        sqliteStore = new SQLiteStore(dataDir.resolve("mindcraft.db"));
        sqliteStore.initializeSchema();

        List<SQLiteStore.VocabEntry> deEnEntries = loadVocabularyCsv(deEnFile, "de", "en");
        List<SQLiteStore.VocabEntry> deFrEntries = loadVocabularyCsv(deFrFile, "de", "fr");
        sqliteStore.replaceDeEn(deEnEntries);
        sqliteStore.replaceDeFr(deFrEntries);

        getLogger().info("Loaded vocabularies from CSV: de_en=" + deEnEntries.size()
                + ", de_fr=" + deFrEntries.size());
    }

    private List<SQLiteStore.VocabEntry> loadVocabularyCsv(Path csvPath, String leftHeader, String rightHeader)
            throws IOException {
        return VocabularyCsvImport.loadFromPath(csvPath, leftHeader, rightHeader, getLogger());
    }

    private List<SQLiteStore.VocabEntry> loadVocabularyCsvFromUrl(String sourceUrl, String leftHeader,
                                                                   String rightHeader) throws IOException {
        int connectTimeoutSeconds = Math.max(1,
                getConfig().getInt(CONFIG_HTTP_CONNECT_TIMEOUT_SECONDS, DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS));
        int readTimeoutSeconds = Math.max(1,
                getConfig().getInt(CONFIG_HTTP_READ_TIMEOUT_SECONDS, DEFAULT_HTTP_READ_TIMEOUT_SECONDS));
        return VocabularyCsvImport.loadFromUrl(sourceUrl, connectTimeoutSeconds, readTimeoutSeconds, leftHeader,
                rightHeader, getLogger());
    }

    private ImportSummary mergeVocabularyFromSheet(String language, String sourceUrl) throws IOException, SQLException {
        List<SQLiteStore.VocabEntry> entries = loadVocabularyCsvFromUrl(sourceUrl, "de", language);
        int inserted = sqliteStore.insertMissingVocabularyEntries(language, entries);
        int skippedExisting = entries.size() - inserted;
        return new ImportSummary(entries.size(), inserted, skippedExisting);
    }

    private void importConfiguredSheetsOnStartup() {
        importConfiguredSheetOnStartup("en");
        importConfiguredSheetOnStartup("fr");
    }

    private void importConfiguredSheetOnStartup(String language) {
        String sourceUrl = getConfiguredSheetUrl(language);
        if (sourceUrl.isBlank()) {
            return;
        }

        try {
            ImportSummary summary = mergeVocabularyFromSheet(language, sourceUrl);
            getLogger().info("Startup sheet merge for de_" + language + ": sourceRows=" + summary.sourceRows()
                    + ", added=" + summary.inserted() + ", skippedExisting=" + summary.skippedExisting());
        } catch (IOException | SQLException e) {
            getLogger().log(Level.WARNING, "Startup sheet merge failed for de_" + language + ".", e);
        }
    }

    private void ensureDefaultResource(String resourcePath, Path targetPath) throws IOException {
        if (Files.exists(targetPath)) {
            return;
        }

        try (InputStream in = getResource(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }

            Files.copy(in, targetPath);
        }
    }

    private void registerPlayerForPlaytime(String username) {
        if (sqliteStore == null || username == null || username.isBlank()) {
            return;
        }

        String normalized = username.trim();
        String todayDate = todayDate();
        try {
            sqliteStore.upsertUser(normalized);
            sqliteStore.getOrCreatePlayerPlaytimeForToday(normalized, todayDate, getDefaultPlaytimeLimitMinutes());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to register player '" + normalized + "' for playtime.", e);
        }
    }

    private boolean handlePlaytimeCommand(CommandSender sender, String[] args) {
        if (!isRconSender(sender)) {
            sender.sendMessage("This command is restricted to RCON.");
            return true;
        }

        if (sqliteStore == null) {
            sender.sendMessage("SQLite store is not available.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("Usage: /playtime <status|setused|setlimit|reset> ...");
            sender.sendMessage("Usage: /playtime status <player>");
            sender.sendMessage("Usage: /playtime setused <player> <minutes>");
            sender.sendMessage("Usage: /playtime setlimit <player> <minutes|default>");
            sender.sendMessage("Usage: /playtime reset <player|all>");
            return true;
        }

        String action = sanitizeUserInput(args[0]).toLowerCase(Locale.ROOT);
        int defaultLimit = getDefaultPlaytimeLimitMinutes();
        String todayDate = todayDate();

        try {
            if ("status".equals(action)) {
                if (args.length != 2) {
                    sender.sendMessage("Usage: /playtime status <player>");
                    return true;
                }

                String username = sanitizeUserInput(args[1]);
                if (username.isBlank()) {
                    sender.sendMessage("Player name is required.");
                    return true;
                }

                registerPlayerForPlaytime(username);
                SQLiteStore.PlayerPlaytime state =
                        sqliteStore.getOrCreatePlayerPlaytimeForToday(username, todayDate, defaultLimit);
                String overrideValue = state.limitOverrideMinutes() == null
                        ? "default"
                        : String.valueOf(state.limitOverrideMinutes());
                sender.sendMessage("Playtime for " + username + ": used=" + state.dailyUsedMinutes()
                        + "/" + state.effectiveLimitMinutes() + " min, limitOverride=" + overrideValue
                        + ", date=" + state.lastResetDate());
                return true;
            }

            if ("setused".equals(action)) {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /playtime setused <player> <minutes>");
                    return true;
                }

                String username = sanitizeUserInput(args[1]);
                if (username.isBlank()) {
                    sender.sendMessage("Player name is required.");
                    return true;
                }

                Integer minutes = parseNonNegativeInt(args[2]);
                if (minutes == null) {
                    sender.sendMessage("Minutes must be a non-negative integer.");
                    return true;
                }

                registerPlayerForPlaytime(username);
                SQLiteStore.PlayerPlaytime updated =
                        sqliteStore.setDailyUsedMinutesForToday(username, minutes, todayDate, defaultLimit);
                sender.sendMessage("Updated " + username + " daily used playtime to " + updated.dailyUsedMinutes()
                        + "/" + updated.effectiveLimitMinutes() + " min.");
                kickOnlinePlayerIfLimitReached(username, updated);
                return true;
            }

            if ("setlimit".equals(action)) {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /playtime setlimit <player> <minutes|default>");
                    return true;
                }

                String username = sanitizeUserInput(args[1]);
                if (username.isBlank()) {
                    sender.sendMessage("Player name is required.");
                    return true;
                }

                String limitValue = sanitizeUserInput(args[2]);
                Integer overrideLimit = null;
                if (!"default".equalsIgnoreCase(limitValue)) {
                    overrideLimit = parsePositiveInt(limitValue);
                    if (overrideLimit == null) {
                        sender.sendMessage("Limit must be a positive integer or 'default'.");
                        return true;
                    }
                }

                registerPlayerForPlaytime(username);
                SQLiteStore.PlayerPlaytime updated =
                        sqliteStore.setLimitOverrideMinutesForToday(username, overrideLimit, todayDate, defaultLimit);

                String overrideText = updated.limitOverrideMinutes() == null
                        ? "default (" + defaultLimit + ")"
                        : String.valueOf(updated.limitOverrideMinutes());
                sender.sendMessage("Updated " + username + " limit override to " + overrideText
                        + ". Effective daily limit: " + updated.effectiveLimitMinutes() + " min.");
                kickOnlinePlayerIfLimitReached(username, updated);
                return true;
            }

            if ("reset".equals(action)) {
                if (args.length != 2) {
                    sender.sendMessage("Usage: /playtime reset <player|all>");
                    return true;
                }

                String target = sanitizeUserInput(args[1]);
                if (target.isBlank()) {
                    sender.sendMessage("Player name is required.");
                    return true;
                }

                if ("all".equalsIgnoreCase(target)) {
                    int updatedRows = sqliteStore.resetAllDailyUsedMinutesForToday(todayDate);
                    sender.sendMessage("Reset daily playtime usage for " + updatedRows + " players.");
                    return true;
                }

                registerPlayerForPlaytime(target);
                SQLiteStore.PlayerPlaytime updated =
                        sqliteStore.resetDailyUsedMinutesForToday(target, todayDate, defaultLimit);
                sender.sendMessage("Reset daily playtime usage for " + target + ". Current usage: "
                        + updated.dailyUsedMinutes() + "/" + updated.effectiveLimitMinutes() + " min.");
                return true;
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to process playtime command.", e);
            sender.sendMessage("Failed to process playtime command. Check server log.");
            return true;
        }

        sender.sendMessage("Usage: /playtime <status|setused|setlimit|reset> ...");
        return true;
    }

    private void handleQuestAnswer(Player player, String rawAnswer) {
        ActiveQuest quest = activeQuest;
        if (quest == null) {
            player.sendMessage("Aktuell läuft keine Vokabel-Quest.");
            return;
        }

        String submitted = normalizeAnswer(rawAnswer);
        if (submitted.isEmpty()) {
            player.sendMessage("Bitte gib eine Antwort an.");
            return;
        }

        boolean correct = submitted.equals(normalizeAnswer(quest.answer()));
        try {
            sqliteStore.recordAttempt(player.getName(), quest.vocabTable(), quest.deWord(), correct);
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to record attempt for " + player.getName(), e);
        }

        if (!correct) {
            broadcastAsJenkins("Player " + player.getName() + " hat die Frage leider falsch beantwortet.");
            return;
        }

        boolean rewarded = false;
        try {
            rewarded = sqliteStore.claimReward(player.getName(), quest.vocabTable(), quest.deWord());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to claim reward for " + player.getName(), e);
        }

        if (rewarded) {
            giveEmerald(player);
            player.sendMessage("Du hast 1 Emerald erhalten.");
            getLogger().info("Granted reward to " + player.getName()
                    + " for " + quest.vocabTable() + ":" + quest.deWord());
            broadcastAsJenkins("Player " + player.getName() + " hat die Frage richtig beantwortet!");
            broadcastSolution(quest);
            finishQuest();
        } else {
            player.sendMessage("Für diese Vokabel hast du bereits eine Belohnung erhalten.");
            broadcastAsJenkins("Player " + player.getName()
                    + " hat korrekt geantwortet, aber für diese Vokabel bereits eine Belohnung erhalten.");
        }
    }

    private boolean startVocabularyQuest() {
        return startVocabularyQuest(false);
    }

    private boolean startVocabularyQuest(boolean timerTriggered) {
        if (activeQuest != null || sqliteStore == null) {
            return false;
        }

        if (timerTriggered && !hasMinimumVocabularyForTimerQuests()) {
            scheduleNextQuest();
            return false;
        }

        List<String> onlinePlayers = Bukkit.getOnlinePlayers()
                .stream()
                .map(Player::getName)
                .toList();

        if (onlinePlayers.isEmpty()) {
            getLogger().info("Skipping quest start because no players are online.");
            scheduleNextQuest();
            return false;
        }

        SQLiteStore.QuestEntry entry;
        try {
            entry = sqliteStore.selectWeightedQuestForOnlinePlayers(onlinePlayers, random);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to select quest vocabulary.", e);
            scheduleNextQuest();
            return false;
        }

        if (entry == null) {
            broadcastAsJenkins("Aktuell gibt es keine belohnbare Vokabel für die online Spieler.");
            getLogger().info("No eligible vocabulary entry for online players.");
            scheduleNextQuest();
            return false;
        }

        cancelQuestTimeout();
        activeQuest = new ActiveQuest(entry.vocabTable(), entry.deWord(), entry.answer());

        String languageWord = "de_fr".equals(entry.vocabTable()) ? "französische" : "englische";
        broadcastAsJenkins(
                "Vokabel-Quest: Was ist das " + languageWord + " Wort für: "
                        + entry.deWord()
                        + "? Antworte privat mit /msg jenkins <Antwort> oder mit /answer <Antwort>."
        );

        ActiveQuest expectedQuest = activeQuest;
        questTimeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> onQuestTimeout(expectedQuest),
                QUEST_TIMEOUT_TICKS);
        getLogger().info("Started quest for " + entry.vocabTable() + ":" + entry.deWord()
                + " (attempts=" + entry.attempts()
                + ", eligibleOnlinePlayers=" + entry.eligibleOnlinePlayers() + ")");
        return true;
    }

    private boolean hasMinimumVocabularyForTimerQuests() {
        try {
            int totalEntries = sqliteStore.totalVocabularyEntries();
            if (totalEntries < MIN_VOCAB_ENTRIES_FOR_TIMER_QUESTS) {
                getLogger().info("Skipping timed quest start because only " + totalEntries
                        + " vocabulary entries are available (minimum "
                        + MIN_VOCAB_ENTRIES_FOR_TIMER_QUESTS + ").");
                return false;
            }
            return true;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to count vocabulary entries for timed quest gating.", e);
            return false;
        }
    }

    private void onQuestTimeout(ActiveQuest expectedQuest) {
        if (activeQuest == null || expectedQuest == null) {
            return;
        }

        if (!isSameQuest(activeQuest, expectedQuest)) {
            return;
        }

        broadcastAsJenkins("Vokabel-Quest beendet. Niemand hat rechtzeitig korrekt geantwortet.");
        broadcastSolution(activeQuest);
        finishQuest();
    }

    private void finishQuest() {
        activeQuest = null;
        cancelQuestTimeout();
        scheduleNextQuest();
    }

    private void scheduleNextQuest() {
        cancelScheduledQuest();

        int bound = QUEST_DELAY_MAX_SECONDS - QUEST_DELAY_MIN_SECONDS + 1;
        int delaySeconds = QUEST_DELAY_MIN_SECONDS + random.nextInt(bound);
        long delayTicks = delaySeconds * 20L;

        scheduledQuestTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            scheduledQuestTask = null;
            startVocabularyQuest(true);
        }, delayTicks);

        getLogger().info("Scheduled next vocabulary quest in " + delaySeconds + " seconds.");
    }

    private void cancelScheduledQuest() {
        if (scheduledQuestTask != null) {
            scheduledQuestTask.cancel();
            scheduledQuestTask = null;
        }
    }

    private void cancelQuestTimeout() {
        if (questTimeoutTask != null) {
            questTimeoutTask.cancel();
            questTimeoutTask = null;
        }
    }

    private void startPlaytimeTracker() {
        cancelPlaytimeTracker();
        if (!isPlaytimeLimitEnabled()) {
            return;
        }

        playtimeTrackerTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::trackOnlinePlaytimeUsage,
                20L * 60L,
                20L * 60L
        );
    }

    private void cancelPlaytimeTracker() {
        if (playtimeTrackerTask != null) {
            playtimeTrackerTask.cancel();
            playtimeTrackerTask = null;
        }
    }

    private void trackOnlinePlaytimeUsage() {
        if (!isPlaytimeLimitEnabled() || sqliteStore == null) {
            return;
        }

        String todayDate = todayDate();
        int defaultLimit = getDefaultPlaytimeLimitMinutes();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String username = sanitizeUserInput(player.getName());
            if (username.isBlank()) {
                continue;
            }

            try {
                SQLiteStore.PlayerPlaytime updated =
                        sqliteStore.addDailyUsedMinutesForToday(username, 1, todayDate, defaultLimit);
                int remainingMinutes = updated.effectiveLimitMinutes() - updated.dailyUsedMinutes();
                if (remainingMinutes > 0 && remainingMinutes <= PLAYTIME_WARNING_WINDOW_MINUTES) {
                    sendPlaytimeWarning(player, remainingMinutes);
                }
                if (updated.dailyUsedMinutes() >= updated.effectiveLimitMinutes()) {
                    player.kickPlayer(buildPlaytimeKickMessage(updated.dailyUsedMinutes(), updated.effectiveLimitMinutes()));
                }
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Failed to track playtime for " + username + ".", e);
            }
        }
    }

    private void broadcastSolution(ActiveQuest quest) {
        if (quest == null) {
            return;
        }

        broadcastAsJenkins("Lösung: " + quest.deWord() + " -> " + quest.answer() + "!");
    }

    private void giveEmerald(Player player) {
        ItemStack emerald = new ItemStack(Material.EMERALD, 1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(emerald);
        if (leftover.isEmpty()) {
            return;
        }

        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    private SQLiteStore.PlayerPlaytime getPlayerPlaytime(String username) {
        if (!isPlaytimeLimitEnabled() || sqliteStore == null || username == null || username.isBlank()) {
            return null;
        }

        try {
            return sqliteStore.getOrCreatePlayerPlaytimeForToday(username.trim(), todayDate(),
                    getDefaultPlaytimeLimitMinutes());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to load playtime for " + username + ".", e);
            return null;
        }
    }

    private void enforcePlaytimeLimit(Player player) {
        if (!isPlaytimeLimitEnabled() || player == null) {
            return;
        }

        String username = sanitizeUserInput(player.getName());
        SQLiteStore.PlayerPlaytime playtime = getPlayerPlaytime(username);
        if (playtime == null) {
            return;
        }

        if (playtime.dailyUsedMinutes() >= playtime.effectiveLimitMinutes()) {
            player.kickPlayer(buildPlaytimeKickMessage(playtime.dailyUsedMinutes(), playtime.effectiveLimitMinutes()));
        }
    }

    private void kickOnlinePlayerIfLimitReached(String username, SQLiteStore.PlayerPlaytime playtime) {
        if (username == null || username.isBlank() || playtime == null) {
            return;
        }

        if (playtime.dailyUsedMinutes() < playtime.effectiveLimitMinutes()) {
            return;
        }

        Player onlinePlayer = Bukkit.getPlayerExact(username);
        if (onlinePlayer == null) {
            return;
        }

        onlinePlayer.kickPlayer(buildPlaytimeKickMessage(playtime.dailyUsedMinutes(), playtime.effectiveLimitMinutes()));
    }

    private boolean isPlaytimeLimitEnabled() {
        return getConfig().getBoolean(CONFIG_PLAYTIME_ENABLED, true);
    }

    private int getDefaultPlaytimeLimitMinutes() {
        return Math.max(1, getConfig().getInt(CONFIG_PLAYTIME_DEFAULT_DAILY_LIMIT_MINUTES,
                DEFAULT_PLAYTIME_DAILY_LIMIT_MINUTES));
    }

    private String buildPlaytimeKickMessage(int usedMinutes, int limitMinutes) {
        String template = getConfig().getString(CONFIG_PLAYTIME_KICK_MESSAGE, DEFAULT_PLAYTIME_KICK_MESSAGE);
        return template
                .replace("{used}", String.valueOf(usedMinutes))
                .replace("{limit}", String.valueOf(limitMinutes));
    }

    private String todayDate() {
        return LocalDate.now().toString();
    }

    private Integer parseNonNegativeInt(String raw) {
        try {
            int value = Integer.parseInt(sanitizeUserInput(raw));
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePositiveInt(String raw) {
        Integer value = parseNonNegativeInt(raw);
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private boolean isSameQuest(ActiveQuest a, ActiveQuest b) {
        return a.vocabTable().equals(b.vocabTable())
                && a.deWord().equals(b.deWord())
                && a.answer().equals(b.answer());
    }

    private String parsePrivatePluginAnswer(String message) {
        if (message == null || message.isBlank() || !message.startsWith("/")) {
            return null;
        }

        String content = message.substring(1).trim();
        String[] parts = content.split("\\s+", 3);
        if (parts.length < 2) {
            return null;
        }

        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!"msg".equals(command) && !"tell".equals(command) && !"w".equals(command)) {
            return null;
        }

        String target = parts[1].toLowerCase(Locale.ROOT);
        if (!"jenkins".equals(target) && !"vocabularyquest".equals(target)
                && !"vocabularyquestplugin".equals(target)) {
            return null;
        }

        return parts.length >= 3 ? parts[2].trim() : "";
    }

    private String normalizeAnswer(String answer) {
        if (answer == null) {
            return "";
        }

        return answer.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitizeUserInput(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }

        return builder.toString().trim();
    }

    private String formatDumpSummary(SQLiteStore.DumpSummary summary) {
        return "users=" + summary.users()
                + ", de_en=" + summary.deEnEntries()
                + ", de_fr=" + summary.deFrEntries()
                + ", rewards=" + summary.rewards()
                + ", attempts=" + summary.attempts();
    }

    private void broadcastAsJenkins(String message) {
        Bukkit.broadcast(Component.text("<" + PLUGIN_CHAT_NAME + "> " + message));
    }

    private void sendPlaytimeWarning(Player player, int remainingMinutes) {
        String minuteLabel = remainingMinutes == 1 ? "minute" : "minutes";
        String message = "Playtime warning: " + remainingMinutes + " " + minuteLabel + " remaining today.";
        player.sendMessage(Component.text("<" + PLUGIN_CHAT_NAME + "> " + message));
    }

    private boolean isRconSender(CommandSender sender) {
        return sender instanceof RemoteConsoleCommandSender;
    }

    private String getConfiguredSheetUrl(String language) {
        return getConfig().getString(configPathForSheetUrl(language), "").trim();
    }

    private String configPathForSheetUrl(String language) {
        return "en".equals(language) ? CONFIG_SHEET_URL_EN : CONFIG_SHEET_URL_FR;
    }

    private boolean isSupportedImportLanguage(String language) {
        return "en".equals(language) || "fr".equals(language);
    }

    private boolean isValidHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
