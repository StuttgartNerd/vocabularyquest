package io.github.stuttgartnerd.vocabularyquest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

final class VocabularyCsvImport {
    private VocabularyCsvImport() {
    }

    static List<SQLiteStore.VocabEntry> loadFromPath(Path csvPath, String leftHeader, String rightHeader,
                                                     Logger logger) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            return parse(reader, csvPath.getFileName().toString(), leftHeader, rightHeader, logger);
        }
    }

    static List<SQLiteStore.VocabEntry> loadFromUrl(String sourceUrl, int connectTimeoutSeconds,
                                                    int readTimeoutSeconds, String leftHeader, String rightHeader,
                                                    Logger logger) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI uri = new URI(sourceUrl);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutSeconds * 1000);
            connection.setReadTimeout(readTimeoutSeconds * 1000);
            connection.setDoInput(true);

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Unexpected HTTP status " + statusCode + " while loading " + sourceUrl);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return parse(reader, sourceUrl, leftHeader, rightHeader, logger);
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL syntax: " + sourceUrl, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static List<SQLiteStore.VocabEntry> parse(BufferedReader reader, String sourceName, String leftHeader,
                                                       String rightHeader, Logger logger) throws IOException {
        List<SQLiteStore.VocabEntry> entries = new ArrayList<>();

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
                logger.warning("Skipping malformed CSV row in " + sourceName + ":" + lineNo);
                continue;
            }

            String left = parts[0].trim();
            String right = parts[1].trim();
            if (left.isEmpty() || right.isEmpty()) {
                logger.warning("Skipping empty CSV row in " + sourceName + ":" + lineNo);
                continue;
            }

            if (left.equalsIgnoreCase(leftHeader) && right.equalsIgnoreCase(rightHeader)) {
                continue;
            }

            entries.add(new SQLiteStore.VocabEntry(left, right));
        }

        return entries;
    }
}
