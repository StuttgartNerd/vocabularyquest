package io.github.stuttgartnerd.vocabularyquest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
