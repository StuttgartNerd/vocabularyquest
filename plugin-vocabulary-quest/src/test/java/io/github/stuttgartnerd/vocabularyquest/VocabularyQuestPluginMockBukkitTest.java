package io.github.stuttgartnerd.vocabularyquest;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.command.CommandResult;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocabularyQuestPluginMockBukkitTest {
    private ServerMock server;
    private VocabularyQuestPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VocabularyQuestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rconOnlyCommandsAreDeniedForPlayerAndConsole() {
        server.addPlayer("Alice");

        CommandResult playerDumpResult = server.executePlayer("dbdump");
        playerDumpResult.assertResponse("This command is restricted to RCON.");

        CommandResult playerResult = server.executePlayer("flushanswers");
        playerResult.assertResponse("This command is restricted to RCON.");

        CommandResult playerFlushVocabResult = server.executePlayer("flushvocab", "en");
        playerFlushVocabResult.assertResponse("This command is restricted to RCON.");

        CommandResult playerQuestResult = server.executePlayer("questnow");
        playerQuestResult.assertResponse("This command is restricted to RCON.");

        CommandResult playerSetUrlResult = server.executePlayer("setvocaburl", "en", "https://example.test/en.csv");
        playerSetUrlResult.assertResponse("This command is restricted to RCON.");

        CommandResult playerImportResult = server.executePlayer("importvocab", "en");
        playerImportResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleDumpResult = server.executeConsole("dbdump");
        consoleDumpResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleResult = server.executeConsole("addvocab", "en", "hund", "dog");
        consoleResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleFlushVocabResult = server.executeConsole("flushvocab", "en");
        consoleFlushVocabResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleQuestResult = server.executeConsole("questnow");
        consoleQuestResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleSetUrlResult = server.executeConsole(
                "setvocaburl", "en", "https://example.test/en.csv");
        consoleSetUrlResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleImportResult = server.executeConsole("importvocab", "en");
        consoleImportResult.assertResponse("This command is restricted to RCON.");
    }

    @Test
    void rconOnlyCommandsWorkForRemoteConsoleSender() throws Exception {
        SQLiteStore store = getSQLiteStore();
        store.recordAttempt("alice", "de_en", "haus", false);
        assertTrue(store.claimReward("alice", "de_en", "haus"));

        SQLiteStore.DumpSummary beforeInsert = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
        int deEnBefore = beforeInsert.deEnEntries();

        List<String> messages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(messages);

        PluginCommand dumpCommand = server.getPluginCommand("dbdump");
        assertNotNull(dumpCommand);
        assertTrue(plugin.onCommand(rcon, dumpCommand, "dbdump", new String[0]));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Dumped SQLite tables to log")));

        PluginCommand addVocab = server.getPluginCommand("addvocab");
        assertNotNull(addVocab);
        assertTrue(plugin.onCommand(rcon, addVocab, "addvocab", new String[]{"en", "hund", "dog"}));

        PluginCommand setVocabUrl = server.getPluginCommand("setvocaburl");
        assertNotNull(setVocabUrl);
        String configuredEnUrl = "https://example.test/en.csv";
        assertTrue(plugin.onCommand(rcon, setVocabUrl, "setvocaburl", new String[]{"en", configuredEnUrl}));
        assertEquals(configuredEnUrl, plugin.getConfig().getString("vocab_import.sheet_urls.en"));

        PluginCommand importVocab = server.getPluginCommand("importvocab");
        assertNotNull(importVocab);
        assertTrue(plugin.onCommand(rcon, importVocab, "importvocab", new String[]{"fr"}));

        SQLiteStore.DumpSummary afterInsert = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
        assertEquals(deEnBefore + 1, afterInsert.deEnEntries());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Inserted vocabulary pair")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Set sheet URL for de_en.")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("No sheet URL configured for de_fr")));

        PluginCommand flushAnswers = server.getPluginCommand("flushanswers");
        assertNotNull(flushAnswers);
        assertTrue(plugin.onCommand(rcon, flushAnswers, "flushanswers", new String[0]));

        PluginCommand questNow = server.getPluginCommand("questnow");
        assertNotNull(questNow);
        assertTrue(plugin.onCommand(rcon, questNow, "questnow", new String[0]));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Quest konnte nicht gestartet")
                || m.contains("Vokabel-Quest wurde gestartet")));

        SQLiteStore.DumpSummary afterFlush = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
        assertEquals(0, afterFlush.rewards());
        assertEquals(0, afterFlush.attempts());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Cleared reward/attempt tracking tables")));
    }

    @Test
    void playerJoinEventStoresUserInDatabase() throws Exception {
        SQLiteStore store = getSQLiteStore();
        int beforeUsers = store.dumpToLog(java.util.logging.Logger.getLogger("test")).users();

        String username = "User_" + UUID.randomUUID().toString().substring(0, 8);
        PlayerMock player = server.addPlayer(username);
        plugin.onPlayerJoin(new PlayerJoinEvent(player, "joined"));

        int afterUsers = store.dumpToLog(java.util.logging.Logger.getLogger("test")).users();
        assertEquals(beforeUsers + 1, afterUsers);
    }

    @Test
    void questNowAndAnswerFlowRewardsPlayer() throws Exception {
        String username = "QuestUser_" + UUID.randomUUID().toString().substring(0, 8);
        PlayerMock player = server.addPlayer(username);
        List<String> rconMessages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(rconMessages);

        PluginCommand questNow = server.getPluginCommand("questnow");
        assertNotNull(questNow);
        assertTrue(plugin.onCommand(rcon, questNow, "questnow", new String[0]));
        assertTrue(rconMessages.stream().anyMatch(m -> m.contains("Vokabel-Quest wurde gestartet.")));

        Object activeQuest = getActiveQuest();
        assertNotNull(activeQuest);

        int emeraldsBefore = countMaterial(player, Material.EMERALD);

        server.execute("answer", player, "falsch");
        assertNotNull(getActiveQuest());
        assertEquals(emeraldsBefore, countMaterial(player, Material.EMERALD));

        String answer = getQuestAnswer(activeQuest);
        server.execute("answer", player, answer);

        assertNull(getActiveQuest());
        assertEquals(emeraldsBefore + 1, countMaterial(player, Material.EMERALD));
    }

    @Test
    void timedQuestRequiresAtLeastTenEntriesButQuestNowCanStillStart() throws Exception {
        server.addPlayer("ThresholdUser");
        SQLiteStore store = getSQLiteStore();
        store.replaceDeEn(List.of(
                new SQLiteStore.VocabEntry("haus", "house"),
                new SQLiteStore.VocabEntry("baum", "tree")
        ));
        store.replaceDeFr(List.of(
                new SQLiteStore.VocabEntry("maus", "souris"),
                new SQLiteStore.VocabEntry("wasser", "eau")
        ));

        assertFalse(invokeStartVocabularyQuest(true));
        assertNull(getActiveQuest());

        List<String> rconMessages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(rconMessages);
        PluginCommand questNow = server.getPluginCommand("questnow");
        assertNotNull(questNow);
        assertTrue(plugin.onCommand(rcon, questNow, "questnow", new String[0]));

        assertTrue(rconMessages.stream().anyMatch(m -> m.contains("Vokabel-Quest wurde gestartet.")));
        assertNotNull(getActiveQuest());
    }

    @Test
    void flushVocabClearsSelectedLanguageAndTracking() throws Exception {
        SQLiteStore store = getSQLiteStore();
        store.replaceDeEn(List.of(
                new SQLiteStore.VocabEntry("haus", "house"),
                new SQLiteStore.VocabEntry("baum", "tree")
        ));
        store.replaceDeFr(List.of(
                new SQLiteStore.VocabEntry("maus", "souris")
        ));
        store.recordAttempt("alice", "de_en", "haus", false);
        store.recordAttempt("alice", "de_fr", "maus", false);
        assertTrue(store.claimReward("alice", "de_en", "haus"));
        assertTrue(store.claimReward("alice", "de_fr", "maus"));

        List<String> rconMessages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(rconMessages);
        PluginCommand flushVocab = server.getPluginCommand("flushvocab");
        assertNotNull(flushVocab);
        assertTrue(plugin.onCommand(rcon, flushVocab, "flushvocab", new String[]{"en"}));

        SQLiteStore.DumpSummary after = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
        assertEquals(0, after.deEnEntries());
        assertEquals(1, after.deFrEntries());
        assertEquals(0, after.rewards());
        assertEquals(0, after.attempts());
        assertTrue(rconMessages.stream().anyMatch(m -> m.contains("Cleared de_en (2 entries)")));
    }

    @Test
    void overlongAnswerIsRejectedAndDoesNotRewardPlayer() throws Exception {
        String username = "LengthUser_" + UUID.randomUUID().toString().substring(0, 8);
        PlayerMock player = server.addPlayer(username);
        List<String> rconMessages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(rconMessages);

        PluginCommand questNow = server.getPluginCommand("questnow");
        assertNotNull(questNow);
        assertTrue(plugin.onCommand(rcon, questNow, "questnow", new String[0]));
        assertNotNull(getActiveQuest());

        int emeraldsBefore = countMaterial(player, Material.EMERALD);
        server.execute("answer", player, "a".repeat(80));

        assertTrue(playerReceivedMessageContaining(player, "Antwort ist zu lang (max 64 Zeichen)."));
        assertNotNull(getActiveQuest());
        assertEquals(emeraldsBefore, countMaterial(player, Material.EMERALD));
    }

    @Test
    void privateMessageOldAliasIsIgnored() {
        PlayerMock player = server.addPlayer("AliasUser");
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/msg plugin haus");

        plugin.onPrivateMessageCommand(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void addVocabRejectsOverlongTerms() {
        List<String> rconMessages = new ArrayList<>();
        RemoteConsoleCommandSender rcon = createRconSender(rconMessages);
        PluginCommand addVocab = server.getPluginCommand("addvocab");
        assertNotNull(addVocab);

        String longWord = "x".repeat(65);
        assertTrue(plugin.onCommand(rcon, addVocab, "addvocab", new String[]{"en", longWord, "house"}));
        assertTrue(rconMessages.stream().anyMatch(m -> m.contains("Vocabulary terms must be <= 64 characters.")));
    }

    @Test
    void importVocabPullsConfiguredSheetUrlsForEnAndFr() throws Exception {
        SQLiteStore store = getSQLiteStore();
        store.replaceDeEn(List.of(new SQLiteStore.VocabEntry("haus", "legacy-house")));
        store.replaceDeFr(List.of(new SQLiteStore.VocabEntry("haus", "ancienne-maison")));
        store.recordAttempt("alice", "de_en", "haus", false);
        store.recordAttempt("alice", "de_fr", "haus", false);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            httpServer.createContext("/de_en.csv", exchange -> respondCsv(exchange, """
                    de,en
                    haus,new-house
                    baum,tree
                    """));
            httpServer.createContext("/de_fr.csv", exchange -> respondCsv(exchange, """
                    de,fr
                    haus,nouvelle-maison
                    baum,arbre
                    """));
            httpServer.start();

            int port = httpServer.getAddress().getPort();
            String enUrl = "http://127.0.0.1:" + port + "/de_en.csv";
            String frUrl = "http://127.0.0.1:" + port + "/de_fr.csv";

            List<String> messages = new ArrayList<>();
            RemoteConsoleCommandSender rcon = createRconSender(messages);

            PluginCommand setVocabUrl = server.getPluginCommand("setvocaburl");
            assertNotNull(setVocabUrl);
            assertTrue(plugin.onCommand(rcon, setVocabUrl, "setvocaburl", new String[]{"en", enUrl}));
            assertTrue(plugin.onCommand(rcon, setVocabUrl, "setvocaburl", new String[]{"fr", frUrl}));

            PluginCommand importVocab = server.getPluginCommand("importvocab");
            assertNotNull(importVocab);
            assertTrue(plugin.onCommand(rcon, importVocab, "importvocab", new String[]{"en"}));
            assertTrue(plugin.onCommand(rcon, importVocab, "importvocab", new String[]{"fr"}));

            SQLiteStore.DumpSummary summary = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
            assertEquals(2, summary.deEnEntries());
            assertEquals(2, summary.deFrEntries());
            assertEquals(2, summary.attempts(), "Merge import must not clear attempt counters.");
            assertTrue(messages.stream().anyMatch(m -> m.contains("Merged de_en from sheet: added 1 new entries")));
            assertTrue(messages.stream().anyMatch(m -> m.contains("Merged de_fr from sheet: added 1 new entries")));

            Path dbPath = plugin.getDataFolder().toPath().resolve("mindcraft.db");
            assertEquals("legacy-house", selectTranslation(dbPath, "vocab_de_en", "en", "haus"));
            assertEquals("tree", selectTranslation(dbPath, "vocab_de_en", "en", "baum"));
            assertEquals("ancienne-maison", selectTranslation(dbPath, "vocab_de_fr", "fr", "haus"));
            assertEquals("arbre", selectTranslation(dbPath, "vocab_de_fr", "fr", "baum"));
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void startupImportMergesConfiguredSheetsWhenUrlsExist() throws Exception {
        SQLiteStore store = getSQLiteStore();
        store.replaceDeEn(List.of(new SQLiteStore.VocabEntry("haus", "legacy-house")));
        store.replaceDeFr(List.of(new SQLiteStore.VocabEntry("haus", "ancienne-maison")));

        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            httpServer.createContext("/startup-en.csv", exchange -> respondCsv(exchange, """
                    de,en
                    haus,new-house
                    baum,tree
                    """));
            httpServer.createContext("/startup-fr.csv", exchange -> respondCsv(exchange, """
                    de,fr
                    haus,nouvelle-maison
                    baum,arbre
                    """));
            httpServer.start();

            int port = httpServer.getAddress().getPort();
            plugin.getConfig().set("vocab_import.sheet_urls.en", "http://127.0.0.1:" + port + "/startup-en.csv");
            plugin.getConfig().set("vocab_import.sheet_urls.fr", "http://127.0.0.1:" + port + "/startup-fr.csv");

            invokeImportConfiguredSheetsOnStartup();

            SQLiteStore.DumpSummary summary = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
            assertEquals(2, summary.deEnEntries());
            assertEquals(2, summary.deFrEntries());

            Path dbPath = plugin.getDataFolder().toPath().resolve("mindcraft.db");
            assertEquals("legacy-house", selectTranslation(dbPath, "vocab_de_en", "en", "haus"));
            assertEquals("tree", selectTranslation(dbPath, "vocab_de_en", "en", "baum"));
            assertEquals("ancienne-maison", selectTranslation(dbPath, "vocab_de_fr", "fr", "haus"));
            assertEquals("arbre", selectTranslation(dbPath, "vocab_de_fr", "fr", "baum"));
        } finally {
            httpServer.stop(0);
        }
    }

    private RemoteConsoleCommandSender createRconSender(List<String> sink) {
        return (RemoteConsoleCommandSender) Proxy.newProxyInstance(
                RemoteConsoleCommandSender.class.getClassLoader(),
                new Class[]{RemoteConsoleCommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("sendMessage".equals(name)) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof String s) {
                                    sink.add(s);
                                } else if (arg instanceof String[] arr) {
                                    for (String s : arr) {
                                        sink.add(s);
                                    }
                                }
                            }
                        }
                        return null;
                    }
                    if ("getName".equals(name)) {
                        return "RCON";
                    }
                    if ("getServer".equals(name)) {
                        return server;
                    }
                    if ("isOp".equals(name) || "hasPermission".equals(name) || "isPermissionSet".equals(name)) {
                        return true;
                    }
                    if ("toString".equals(name)) {
                        return "RemoteConsoleCommandSenderMock";
                    }
                    if ("hashCode".equals(name)) {
                        return 31;
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private SQLiteStore getSQLiteStore() throws Exception {
        Field field = VocabularyQuestPlugin.class.getDeclaredField("sqliteStore");
        field.setAccessible(true);
        return (SQLiteStore) field.get(plugin);
    }

    private Object getActiveQuest() throws Exception {
        Field field = VocabularyQuestPlugin.class.getDeclaredField("activeQuest");
        field.setAccessible(true);
        return field.get(plugin);
    }

    private String getQuestAnswer(Object activeQuest) throws Exception {
        Object answer = activeQuest.getClass().getDeclaredMethod("answer").invoke(activeQuest);
        return String.valueOf(answer).toLowerCase(Locale.ROOT);
    }

    private boolean invokeStartVocabularyQuest(boolean timerTriggered) throws Exception {
        Method method = VocabularyQuestPlugin.class.getDeclaredMethod("startVocabularyQuest", boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(plugin, timerTriggered);
    }

    private void invokeImportConfiguredSheetsOnStartup() throws Exception {
        Method method = VocabularyQuestPlugin.class.getDeclaredMethod("importConfiguredSheetsOnStartup");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    private int countMaterial(PlayerMock player, Material material) {
        return player.getInventory().all(material)
                .values()
                .stream()
                .mapToInt(stack -> stack.getAmount())
                .sum();
    }

    private boolean playerReceivedMessageContaining(PlayerMock player, String expectedText) {
        for (int i = 0; i < 30; i++) {
            String message = player.nextMessage();
            if (message == null) {
                break;
            }
            if (message.contains(expectedText)) {
                return true;
            }
        }
        return false;
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private void respondCsv(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private String selectTranslation(Path dbPath, String table, String column, String deWord) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE lower(de)=lower(?) ORDER BY id ASC LIMIT 1")) {
            statement.setString(1, deWord);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(column) : null;
            }
        }
    }
}
