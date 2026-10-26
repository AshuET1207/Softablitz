package com.slingring.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slingring.model.TransferSession;
import com.slingring.model.TransferState;

/**
 * Append-only transfer history stored as JSON Lines: one JSON object per line.
 *
 */
public class HistoryStore {

    /**
     * One finished transfer
     */
    public record Entry(String id,
                        String timestamp,
                        TransferSession.Direction direction,
                        String fileName,
                        long size,
                        String sha256,
                        String peerName,
                        TransferState state,
                        String detail) {

        /** Snapshot a finished session. */
        public static Entry from(TransferSession s) {
            Instant when = s.finishedAt() != null ? s.finishedAt() : Instant.now();
            return new Entry(s.id(), when.toString(), s.direction(), s.fileName(),
                    s.size(), s.sha256(), s.peerName(), s.state(), s.detail());
        }
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public HistoryStore(Path file) throws IOException {
        this.file = file;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Record the outcome of a finished transfer.
     * Only terminal states belong in history (intact, corrupted, refused, cancelled, failed).
     */
    public synchronized void append(Entry entry) throws IOException {
        if (!entry.state().isTerminal()) {
            throw new IllegalArgumentException("Only finished transfers are recorded: " + entry.state());
        }
        // Jackson escapes newlines inside strings, so this is guaranteed to be one line.
        String line = mapper.writeValueAsString(entry) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Convenience for the registry: log a session that just reached a terminal state. */
    public void append(TransferSession session) throws IOException {
        append(Entry.from(session));
    }

    /** All entries, oldest first. Blank or damaged lines are skipped. Empty list if no file yet. */
    public synchronized List<Entry> readAll() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(mapper.readValue(line, Entry.class));
            } catch (IOException badLine) {
                System.err.println("Skipping unreadable history line: " + badLine.getMessage());
            }
        }
        return entries;
    }

    /** Newest entries first, at most {@code limit}. Handy for a history view. */
    public List<Entry> readRecent(int limit) throws IOException {
        List<Entry> all = readAll();
        List<Entry> recent = new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
        java.util.Collections.reverse(recent);
        return recent;
    }

    public Path file() {
        return file;
    }
}