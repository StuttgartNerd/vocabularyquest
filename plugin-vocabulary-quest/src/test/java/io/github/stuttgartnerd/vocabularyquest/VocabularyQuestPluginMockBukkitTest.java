package io.github.stuttgartnerd.vocabularyquest;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.command.CommandResult;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
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

        CommandResult consoleDumpResult = server.executeConsole("dbdump");
        consoleDumpResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleResult = server.executeConsole("addvocab", "en", "hund", "dog");
        consoleResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleFlushVocabResult = server.executeConsole("flushvocab", "en");
        consoleFlushVocabResult.assertResponse("This command is restricted to RCON.");

        CommandResult consoleQuestResult = server.executeConsole("questnow");
        consoleQuestResult.assertResponse("This command is restricted to RCON.");
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

        SQLiteStore.DumpSummary afterInsert = store.dumpToLog(java.util.logging.Logger.getLogger("test"));
        assertEquals(deEnBefore + 1, afterInsert.deEnEntries());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Inserted vocabulary pair")));

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
}
