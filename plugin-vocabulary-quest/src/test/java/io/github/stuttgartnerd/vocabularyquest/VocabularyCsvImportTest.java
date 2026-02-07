package io.github.stuttgartnerd.vocabularyquest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VocabularyCsvImportTest {
    private static final Logger TEST_LOGGER = Logger.getLogger(VocabularyCsvImportTest.class.getName());

    @Test
    void loadsVocabularyFromHttpCsvAndSkipsInvalidRows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/de_en.csv", exchange -> respondCsv(exchange, 200, """
                    de,en
                    haus,house
                    invalid-row-without-comma
                    baum,tree
                    """));
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/de_en.csv";
            List<SQLiteStore.VocabEntry> entries = VocabularyCsvImport.loadFromUrl(url, 2, 2, "de", "en",
                    TEST_LOGGER);

            assertEquals(2, entries.size());
            assertEquals("haus", entries.get(0).left());
            assertEquals("house", entries.get(0).right());
            assertEquals("baum", entries.get(1).left());
            assertEquals("tree", entries.get(1).right());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsOnNonSuccessHttpStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/de_fr.csv", exchange -> respondCsv(exchange, 404, "not found\n"));
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/de_fr.csv";
            assertThrows(IOException.class, () -> VocabularyCsvImport.loadFromUrl(url, 2, 2, "de", "fr",
                    TEST_LOGGER));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesSqlLikeSheetPayloadAsPlainText() throws Exception {
        String maliciousDe = "haus'); DROP TABLE users;--";
        String maliciousEn = "house'); DELETE FROM vocab_de_en;--";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/abuse.csv", exchange -> respondCsv(exchange, 200, """
                    de,en
                    haus'); DROP TABLE users;--,house'); DELETE FROM vocab_de_en;--
                    baum,tree
                    """));
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/abuse.csv";
            List<SQLiteStore.VocabEntry> entries = VocabularyCsvImport.loadFromUrl(url, 2, 2, "de", "en",
                    TEST_LOGGER);

            assertEquals(2, entries.size());
            assertEquals(maliciousDe, entries.get(0).left());
            assertEquals(maliciousEn, entries.get(0).right());
            assertEquals("baum", entries.get(1).left());
            assertEquals("tree", entries.get(1).right());
        } finally {
            server.stop(0);
        }
    }

    private void respondCsv(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
