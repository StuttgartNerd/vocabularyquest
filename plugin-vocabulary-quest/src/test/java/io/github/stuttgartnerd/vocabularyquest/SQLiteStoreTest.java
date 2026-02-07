package io.github.stuttgartnerd.vocabularyquest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteStoreTest {
    private static final Logger TEST_LOGGER = Logger.getLogger(SQLiteStoreTest.class.getName());

    @TempDir
    Path tempDir;

    @Test
    void initializesSchemaLoadsVocabularyAndUpsertsUsers() throws Exception {
        Path db = tempDir.resolve("schema-users.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(
                    new SQLiteStore.VocabEntry("haus", "house"),
                    new SQLiteStore.VocabEntry("baum", "tree")
            ));
            store.replaceDeFr(List.of(
                    new SQLiteStore.VocabEntry("haus", "maison")
            ));

            store.upsertUser("alice");
            store.upsertUser("alice");
            store.upsertUser("bob");

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(2, summary.users());
            assertEquals(2, summary.deEnEntries());
            assertEquals(1, summary.deFrEntries());
            assertEquals(0, summary.rewards());
            assertEquals(0, summary.attempts());
        }
    }

    @Test
    void rewardAndAttemptTrackingCanBeCleared() throws Exception {
        Path db = tempDir.resolve("tracking-reset.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(new SQLiteStore.VocabEntry("haus", "house")));
            store.replaceDeFr(List.of());
            store.upsertUser("alice");

            assertTrue(store.claimReward("alice", "de_en", "haus"));
            assertFalse(store.claimReward("alice", "de_en", "haus"));

            store.recordAttempt("alice", "de_en", "haus", true);
            store.recordAttempt("alice", "de_en", "haus", false);

            SQLiteStore.DumpSummary before = store.dumpToLog(TEST_LOGGER);
            assertEquals(1, before.rewards());
            assertEquals(2, before.attempts());

            store.clearAnswerTracking();

            SQLiteStore.DumpSummary after = store.dumpToLog(TEST_LOGGER);
            assertEquals(0, after.rewards());
            assertEquals(0, after.attempts());
            assertEquals(1, after.deEnEntries());
            assertEquals(1, after.users());
        }
    }

    @Test
    void insertsVocabularyRowsAndRejectsUnsupportedLanguage() throws Exception {
        Path db = tempDir.resolve("insert-vocab.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of());
            store.replaceDeFr(List.of());

            store.insertVocabularyEntry("en", "katze", "cat");
            store.insertVocabularyEntry("fr", "maus", "souris");

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(1, summary.deEnEntries());
            assertEquals(1, summary.deFrEntries());

            assertThrows(SQLException.class, () -> store.insertVocabularyEntry("it", "haus", "casa"));
        }
    }

    @Test
    void mergeImportAddsOnlyMissingRowsAndDoesNotOverwriteExistingTranslations() throws Exception {
        Path db = tempDir.resolve("merge-import.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(new SQLiteStore.VocabEntry("haus", "legacy-house")));
            store.replaceDeFr(List.of(new SQLiteStore.VocabEntry("haus", "ancienne-maison")));
            store.recordAttempt("alice", "de_en", "haus", false);
            store.recordAttempt("alice", "de_fr", "haus", false);

            int insertedEn = store.insertMissingVocabularyEntries("en", List.of(
                    new SQLiteStore.VocabEntry("haus", "new-house"),
                    new SQLiteStore.VocabEntry("baum", "tree")
            ));
            int insertedFr = store.insertMissingVocabularyEntries("fr", List.of(
                    new SQLiteStore.VocabEntry("haus", "nouvelle-maison"),
                    new SQLiteStore.VocabEntry("baum", "arbre")
            ));

            assertEquals(1, insertedEn);
            assertEquals(1, insertedFr);

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(2, summary.deEnEntries());
            assertEquals(2, summary.deFrEntries());
            assertEquals(2, summary.attempts(), "Merge import must not reset attempt counters.");

            assertEquals("legacy-house", selectTranslation(db, "vocab_de_en", "en", "haus"));
            assertEquals("tree", selectTranslation(db, "vocab_de_en", "en", "baum"));
            assertEquals("ancienne-maison", selectTranslation(db, "vocab_de_fr", "fr", "haus"));
            assertEquals("arbre", selectTranslation(db, "vocab_de_fr", "fr", "baum"));
        }
    }

    @Test
    void reportsTotalVocabularyEntriesAcrossLanguages() throws Exception {
        Path db = tempDir.resolve("total-vocab.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(
                    new SQLiteStore.VocabEntry("haus", "house"),
                    new SQLiteStore.VocabEntry("baum", "tree")
            ));
            store.replaceDeFr(List.of(
                    new SQLiteStore.VocabEntry("maus", "souris")
            ));

            assertEquals(3, store.totalVocabularyEntries());
        }
    }

    @Test
    void clearsSelectedVocabularyLanguageAndTracking() throws Exception {
        Path db = tempDir.resolve("clear-language.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
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

            int removed = store.clearVocabularyLanguageAndTracking("en");
            assertEquals(2, removed);

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(0, summary.deEnEntries());
            assertEquals(1, summary.deFrEntries());
            assertEquals(0, summary.rewards());
            assertEquals(0, summary.attempts());

            assertThrows(SQLException.class, () -> store.clearVocabularyLanguageAndTracking("it"));
        }
    }

    @Test
    void selectsQuestOnlyForRewardEligibleOnlinePlayers() throws Exception {
        Path db = tempDir.resolve("online-eligibility.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(
                    new SQLiteStore.VocabEntry("haus", "house"),
                    new SQLiteStore.VocabEntry("baum", "tree")
            ));
            store.replaceDeFr(List.of(
                    new SQLiteStore.VocabEntry("haus", "maison")
            ));

            List<String> onlinePlayers = List.of("alice", "bob");
            store.claimReward("alice", "de_en", "haus");
            store.claimReward("bob", "de_en", "haus");
            store.claimReward("alice", "de_fr", "haus");
            store.claimReward("bob", "de_fr", "haus");

            SQLiteStore.QuestEntry quest = store.selectWeightedQuestForOnlinePlayers(onlinePlayers, new Random(123));
            assertNotNull(quest);
            assertEquals("de_en", quest.vocabTable());
            assertEquals("baum", quest.deWord());
            assertEquals("tree", quest.answer());
            assertEquals(2, quest.eligibleOnlinePlayers());

            store.claimReward("alice", "de_en", "baum");
            store.claimReward("bob", "de_en", "baum");

            assertNull(store.selectWeightedQuestForOnlinePlayers(onlinePlayers, new Random(123)));
            assertNull(store.selectWeightedQuestForOnlinePlayers(List.of(), new Random(123)));
        }
    }

    @Test
    void prefersVocabularyWithFewerAttemptsWhenEligibilityMatches() throws Exception {
        Path db = tempDir.resolve("weighted-selection.db");

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(
                    new SQLiteStore.VocabEntry("haus", "house"),
                    new SQLiteStore.VocabEntry("baum", "tree")
            ));
            store.replaceDeFr(List.of());

            for (int i = 0; i < 25; i++) {
                store.recordAttempt("alice", "de_en", "haus", false);
            }

            int hausCount = 0;
            int baumCount = 0;
            Random random = new Random(42);
            List<String> onlinePlayers = List.of("alice", "bob");

            for (int i = 0; i < 500; i++) {
                SQLiteStore.QuestEntry quest = store.selectWeightedQuestForOnlinePlayers(onlinePlayers, random);
                assertNotNull(quest);
                if ("haus".equals(quest.deWord())) {
                    hausCount++;
                } else if ("baum".equals(quest.deWord())) {
                    baumCount++;
                }
            }

            assertTrue(baumCount > hausCount, "Expected lower-attempt vocabulary to be selected more often");
        }
    }

    @Test
    void handlesSqlLikeInputWithoutBreakingSchema() throws Exception {
        Path db = tempDir.resolve("abuse-input.db");

        String maliciousUsername = "attacker'); DROP TABLE users;--";
        String maliciousDe = "haus'); DROP TABLE vocab_de_en;--";
        String maliciousEn = "house'); DELETE FROM users;--";

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            store.replaceDeEn(List.of(new SQLiteStore.VocabEntry(maliciousDe, maliciousEn)));
            store.replaceDeFr(List.of(new SQLiteStore.VocabEntry("haus", "maison")));

            store.upsertUser(maliciousUsername);
            store.recordAttempt(maliciousUsername, "de_en", maliciousDe, false);
            assertTrue(store.claimReward(maliciousUsername, "de_en", maliciousDe));

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(1, summary.users());
            assertEquals(1, summary.deEnEntries());
            assertEquals(1, summary.deFrEntries());
            assertEquals(1, summary.rewards());
            assertEquals(1, summary.attempts());

            // If schema is intact, normal writes still work after malicious-like input.
            store.upsertUser("normal-user");
            assertEquals(2, store.dumpToLog(TEST_LOGGER).users());
        }
    }

    @Test
    void sheetImportWithSqlLikePayloadDoesNotInjectSql() throws Exception {
        Path db = tempDir.resolve("abuse-sheet-import.db");
        Path csv = tempDir.resolve("abuse-sheet.csv");

        String maliciousDe = "haus'); DROP TABLE users;--";
        String maliciousEn = "house'); DELETE FROM vocab_attempts;--";
        String csvContent = """
                de,en
                haus'); DROP TABLE users;--,house'); DELETE FROM vocab_attempts;--
                baum,tree
                """;
        Files.writeString(csv, csvContent, StandardCharsets.UTF_8);

        List<SQLiteStore.VocabEntry> sheetEntries = VocabularyCsvImport.loadFromPath(csv, "de", "en", TEST_LOGGER);
        assertEquals(2, sheetEntries.size());

        try (SQLiteStore store = new SQLiteStore(db)) {
            store.initializeSchema();
            int inserted = store.insertMissingVocabularyEntries("en", sheetEntries);
            assertEquals(2, inserted);

            // If import payload caused injection, these follow-up writes would fail due to broken schema.
            store.upsertUser("normal-user");
            store.recordAttempt("normal-user", "de_en", maliciousDe, false);
            assertTrue(store.claimReward("normal-user", "de_en", maliciousDe));

            SQLiteStore.DumpSummary summary = store.dumpToLog(TEST_LOGGER);
            assertEquals(1, summary.users());
            assertEquals(2, summary.deEnEntries());
            assertEquals(1, summary.rewards());
            assertEquals(1, summary.attempts());
            assertEquals(maliciousEn, selectTranslation(db, "vocab_de_en", "en", maliciousDe));
        }
    }

    private String selectTranslation(Path dbPath, String table, String column, String deWord) throws SQLException {
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
