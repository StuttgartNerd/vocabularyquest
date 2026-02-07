package io.github.stuttgartnerd.vocabularyquest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

final class SQLiteStore implements AutoCloseable {
    record VocabEntry(String left, String right) {
    }

    record QuestEntry(String vocabTable, String deWord, String answer, int attempts, int eligibleOnlinePlayers) {
    }

    record DumpSummary(int users, int deEnEntries, int deFrEntries, int rewards, int attempts) {
    }

    private final Connection connection;

    SQLiteStore(Path dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    synchronized void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        username TEXT PRIMARY KEY,
                        first_seen TEXT NOT NULL DEFAULT (datetime('now')),
                        last_seen TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vocab_de_en (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        de TEXT NOT NULL,
                        en TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vocab_de_fr (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        de TEXT NOT NULL,
                        fr TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_vocab_rewards (
                        username TEXT NOT NULL,
                        vocab_table TEXT NOT NULL,
                        de_word TEXT NOT NULL,
                        rewarded_at TEXT NOT NULL DEFAULT (datetime('now')),
                        PRIMARY KEY (username, vocab_table, de_word)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vocab_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL,
                        vocab_table TEXT NOT NULL,
                        de_word TEXT NOT NULL,
                        correct INTEGER NOT NULL,
                        attempted_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_vocab_attempts_table_word
                    ON vocab_attempts (vocab_table, de_word)
                    """);
        }
    }

    synchronized void upsertUser(String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (username, first_seen, last_seen)
                VALUES (?, datetime('now'), datetime('now'))
                ON CONFLICT(username) DO UPDATE SET last_seen = excluded.last_seen
                """)) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    synchronized void replaceDeEn(List<VocabEntry> entries) throws SQLException {
        replaceVocabulary("vocab_de_en", "en", entries);
    }

    synchronized void replaceDeFr(List<VocabEntry> entries) throws SQLException {
        replaceVocabulary("vocab_de_fr", "fr", entries);
    }

    synchronized DumpSummary dumpToLog(Logger logger) throws SQLException {
        int users = logUsers(logger);
        int deEn = logVocabulary(logger, "vocab_de_en", "en");
        int deFr = logVocabulary(logger, "vocab_de_fr", "fr");
        int rewards = logRewards(logger);
        int attempts = logAttempts(logger);
        return new DumpSummary(users, deEn, deFr, rewards, attempts);
    }

    synchronized void recordAttempt(String username, String vocabTable, String deWord, boolean correct)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO vocab_attempts (username, vocab_table, de_word, correct, attempted_at)
                VALUES (?, ?, ?, ?, datetime('now'))
                """)) {
            statement.setString(1, username);
            statement.setString(2, vocabTable);
            statement.setString(3, deWord);
            statement.setInt(4, correct ? 1 : 0);
            statement.executeUpdate();
        }
    }

    synchronized boolean claimReward(String username, String vocabTable, String deWord) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO player_vocab_rewards (username, vocab_table, de_word, rewarded_at)
                VALUES (?, ?, ?, datetime('now'))
                """)) {
            statement.setString(1, username);
            statement.setString(2, vocabTable);
            statement.setString(3, deWord);
            return statement.executeUpdate() > 0;
        }
    }

    synchronized void clearAnswerTracking() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM player_vocab_rewards");
            statement.executeUpdate("DELETE FROM vocab_attempts");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    synchronized void insertVocabularyEntry(String language, String deWord, String translatedWord) throws SQLException {
        String normalizedLang = language == null ? "" : language.trim().toLowerCase();
        String table;
        String rightColumn;

        if ("en".equals(normalizedLang)) {
            table = "vocab_de_en";
            rightColumn = "en";
        } else if ("fr".equals(normalizedLang)) {
            table = "vocab_de_fr";
            rightColumn = "fr";
        } else {
            throw new SQLException("Unsupported language for vocabulary insert: " + language);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (de, " + rightColumn + ") VALUES (?, ?)")) {
            statement.setString(1, deWord);
            statement.setString(2, translatedWord);
            statement.executeUpdate();
        }
    }

    synchronized QuestEntry selectWeightedQuestForOnlinePlayers(List<String> onlinePlayers, Random random)
            throws SQLException {
        if (onlinePlayers == null || onlinePlayers.isEmpty()) {
            return null;
        }

        List<QuestEntry> entries = new ArrayList<>();

        String sql = """
                SELECT entries.vocab_table,
                       entries.de,
                       entries.answer,
                       COALESCE(a.attempt_count, 0) AS attempts
                FROM (
                    SELECT 'de_en' AS vocab_table, de, en AS answer FROM vocab_de_en
                    UNION ALL
                    SELECT 'de_fr' AS vocab_table, de, fr AS answer FROM vocab_de_fr
                ) AS entries
                LEFT JOIN (
                    SELECT vocab_table, de_word, COUNT(*) AS attempt_count
                    FROM vocab_attempts
                    GROUP BY vocab_table, de_word
                ) AS a
                ON a.vocab_table = entries.vocab_table AND a.de_word = entries.de
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                entries.add(new QuestEntry(
                        resultSet.getString("vocab_table"),
                        resultSet.getString("de"),
                        resultSet.getString("answer"),
                        resultSet.getInt("attempts"),
                        0
                ));
            }
        }

        if (entries.isEmpty()) {
            return null;
        }

        Set<String> rewardedKeys = loadRewardedKeysForPlayers(onlinePlayers);
        List<QuestEntry> eligibleEntries = new ArrayList<>();
        int maxEligiblePlayers = 0;

        for (QuestEntry entry : entries) {
            int eligiblePlayers = 0;
            for (String username : onlinePlayers) {
                if (!rewardedKeys.contains(rewardKey(username, entry.vocabTable(), entry.deWord()))) {
                    eligiblePlayers++;
                }
            }

            if (eligiblePlayers <= 0) {
                continue;
            }

            QuestEntry withEligibility = new QuestEntry(
                    entry.vocabTable(),
                    entry.deWord(),
                    entry.answer(),
                    entry.attempts(),
                    eligiblePlayers
            );

            if (eligiblePlayers > maxEligiblePlayers) {
                maxEligiblePlayers = eligiblePlayers;
                eligibleEntries.clear();
                eligibleEntries.add(withEligibility);
            } else if (eligiblePlayers == maxEligiblePlayers) {
                eligibleEntries.add(withEligibility);
            }
        }

        if (eligibleEntries.isEmpty()) {
            return null;
        }

        double totalWeight = 0.0d;
        for (QuestEntry entry : eligibleEntries) {
            totalWeight += 1.0d / (1.0d + entry.attempts());
        }

        double pick = random.nextDouble() * totalWeight;
        double cursor = 0.0d;
        for (QuestEntry entry : eligibleEntries) {
            cursor += 1.0d / (1.0d + entry.attempts());
            if (pick <= cursor) {
                return entry;
            }
        }

        return eligibleEntries.get(eligibleEntries.size() - 1);
    }

    private void replaceVocabulary(String table, String rightColumn, List<VocabEntry> entries) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement deleteStatement = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + table + " (de, " + rightColumn + ") VALUES (?, ?)")) {
            deleteStatement.executeUpdate("DELETE FROM " + table);
            for (VocabEntry entry : entries) {
                insert.setString(1, entry.left());
                insert.setString(2, entry.right());
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private int logUsers(Logger logger) throws SQLException {
        int rows = 0;
        logger.info("[DBDUMP] users:");

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username, first_seen, last_seen FROM users ORDER BY first_seen ASC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows++;
                logger.info("[DBDUMP] users row: username=" + resultSet.getString("username")
                        + ", first_seen=" + resultSet.getString("first_seen")
                        + ", last_seen=" + resultSet.getString("last_seen"));
            }
        }

        logger.info("[DBDUMP] users count=" + rows);
        return rows;
    }

    private int logVocabulary(Logger logger, String table, String rightColumn) throws SQLException {
        int rows = 0;
        logger.info("[DBDUMP] " + table + ":");

        String sql = "SELECT de, " + rightColumn + " FROM " + table + " ORDER BY id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows++;
                logger.info("[DBDUMP] " + table + " row: de=" + resultSet.getString("de")
                        + ", " + rightColumn + "=" + resultSet.getString(rightColumn));
            }
        }

        logger.info("[DBDUMP] " + table + " count=" + rows);
        return rows;
    }

    private int logRewards(Logger logger) throws SQLException {
        int rows = 0;
        logger.info("[DBDUMP] player_vocab_rewards:");

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT username, vocab_table, de_word, rewarded_at
                FROM player_vocab_rewards
                ORDER BY rewarded_at ASC
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows++;
                logger.info("[DBDUMP] player_vocab_rewards row: username=" + resultSet.getString("username")
                        + ", vocab_table=" + resultSet.getString("vocab_table")
                        + ", de_word=" + resultSet.getString("de_word")
                        + ", rewarded_at=" + resultSet.getString("rewarded_at"));
            }
        }

        logger.info("[DBDUMP] player_vocab_rewards count=" + rows);
        return rows;
    }

    private int logAttempts(Logger logger) throws SQLException {
        int rows = 0;
        logger.info("[DBDUMP] vocab_attempts:");

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT username, vocab_table, de_word, correct, attempted_at
                FROM vocab_attempts
                ORDER BY attempted_at ASC
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows++;
                logger.info("[DBDUMP] vocab_attempts row: username=" + resultSet.getString("username")
                        + ", vocab_table=" + resultSet.getString("vocab_table")
                        + ", de_word=" + resultSet.getString("de_word")
                        + ", correct=" + resultSet.getInt("correct")
                        + ", attempted_at=" + resultSet.getString("attempted_at"));
            }
        }

        logger.info("[DBDUMP] vocab_attempts count=" + rows);
        return rows;
    }

    private Set<String> loadRewardedKeysForPlayers(List<String> usernames) throws SQLException {
        if (usernames == null || usernames.isEmpty()) {
            return Collections.emptySet();
        }

        String placeholders = String.join(",", Collections.nCopies(usernames.size(), "?"));
        String sql = "SELECT username, vocab_table, de_word FROM player_vocab_rewards WHERE username IN (" + placeholders
                + ")";

        Set<String> rewardedKeys = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < usernames.size(); i++) {
                statement.setString(i + 1, usernames.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rewardedKeys.add(rewardKey(
                            resultSet.getString("username"),
                            resultSet.getString("vocab_table"),
                            resultSet.getString("de_word")
                    ));
                }
            }
        }

        return rewardedKeys;
    }

    private String rewardKey(String username, String vocabTable, String deWord) {
        return username + "|" + vocabTable + "|" + deWord;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
