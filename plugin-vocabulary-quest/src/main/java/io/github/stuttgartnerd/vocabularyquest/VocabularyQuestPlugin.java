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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
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
    private static final String ANSWER_COMMAND = "answer";
    private static final String QUEST_NOW_COMMAND = "questnow";
    private static final long QUEST_TIMEOUT_TICKS = 2L * 60L * 20L;
    private static final int QUEST_DELAY_MIN_SECONDS = 3 * 60;
    private static final int QUEST_DELAY_MAX_SECONDS = 10 * 60;
    private static final int MIN_VOCAB_ENTRIES_FOR_TIMER_QUESTS = 10;
    private static final int MAX_ANSWER_LENGTH = 64;
    private static final int MAX_VOCAB_TERM_LENGTH = 64;

    private final Random random = new Random();
    private SQLiteStore sqliteStore;
    private BukkitTask scheduledQuestTask;
    private BukkitTask questTimeoutTask;
    private ActiveQuest activeQuest;

    private record ActiveQuest(String vocabTable, String deWord, String answer) {
    }

    @Override
    public void onEnable() {
        try {
            initializeStorage();
        } catch (IOException | SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite storage.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            upsertUser(onlinePlayer.getName());
        }
        scheduleNextQuest();
        getLogger().info("VocabularyQuestPlugin enabled.");
    }

    @Override
    public void onDisable() {
        cancelScheduledQuest();
        cancelQuestTimeout();

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
    public void onPlayerJoin(PlayerJoinEvent event) {
        upsertUser(event.getPlayer().getName());
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
        List<SQLiteStore.VocabEntry> entries = new ArrayList<>();

        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                String[] parts = trimmed.split(",", 2);
                if (parts.length < 2) {
                    getLogger().warning("Skipping malformed CSV row in " + csvPath.getFileName() + ":" + lineNo);
                    continue;
                }

                String left = parts[0].trim();
                String right = parts[1].trim();
                if (left.isEmpty() || right.isEmpty()) {
                    getLogger().warning("Skipping empty CSV row in " + csvPath.getFileName() + ":" + lineNo);
                    continue;
                }

                if (left.equalsIgnoreCase(leftHeader) && right.equalsIgnoreCase(rightHeader)) {
                    continue;
                }

                entries.add(new SQLiteStore.VocabEntry(left, right));
            }
        }

        return entries;
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

    private void upsertUser(String username) {
        if (sqliteStore == null || username == null || username.isBlank()) {
            return;
        }

        try {
            sqliteStore.upsertUser(username.trim());
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to upsert user '" + username + "'.", e);
        }
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

    private boolean isRconSender(CommandSender sender) {
        return sender instanceof RemoteConsoleCommandSender;
    }
}
