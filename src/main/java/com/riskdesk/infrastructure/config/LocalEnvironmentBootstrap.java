package com.riskdesk.infrastructure.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class LocalEnvironmentBootstrap {

    private static final List<String> CANDIDATE_FILES = List.of(".env.local", ".env");

    private LocalEnvironmentBootstrap() {
    }

    public static void loadIntoSystemProperties(Path workingDirectory) {
        for (String candidate : CANDIDATE_FILES) {
            loadFile(workingDirectory.resolve(candidate));
        }
    }

    private static void loadFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(file)) {
                loadLine(line);
            }
        } catch (IOException ignored) {
            // Local developer convenience only. Startup should continue even if the file is unreadable.
        }
    }

    private static void loadLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).trim();
        }

        int separatorIndex = line.indexOf('=');
        if (separatorIndex <= 0) {
            return;
        }

        String key = line.substring(0, separatorIndex).trim();
        String value = stripQuotes(line.substring(separatorIndex + 1).trim());
        if (key.isEmpty() || value == null) {
            return;
        }

        if (!isBlank(System.getenv(key)) || !isBlank(System.getProperty(key))) {
            return;
        }

        System.setProperty(key, value);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean quotedWithDouble = value.startsWith("\"") && value.endsWith("\"");
            boolean quotedWithSingle = value.startsWith("'") && value.endsWith("'");
            if (quotedWithDouble || quotedWithSingle) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
