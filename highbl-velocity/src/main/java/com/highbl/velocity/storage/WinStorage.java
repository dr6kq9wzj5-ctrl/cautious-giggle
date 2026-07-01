package com.highbl.velocity.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Thread-safe in-memory win store backed by a JSON file.
 *
 * File layout (plugins/highbl/wins.json):
 * {
 *   "wins":  { "<uuid>": <int>, ... },
 *   "names": { "<uuid>": "<lastKnownName>", ... }
 * }
 *
 * Writes are synchronous on the calling thread — fine for the volumes
 * of a game server; swap in a connection pool if you need SQL later.
 */
public class WinStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path   filePath;
    private final Logger logger;

    /** UUID string → win count */
    private final Map<String, Integer> wins;
    /** UUID string → last known display name */
    private final Map<String, String>  names;

    public WinStorage(Path dataDirectory, Logger logger) {
        this.logger   = logger;
        this.filePath = dataDirectory.resolve("wins.json");
        this.wins     = new LinkedHashMap<>();
        this.names    = new LinkedHashMap<>();
        load();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public synchronized int getWins(UUID player) {
        return wins.getOrDefault(player.toString(), 0);
    }

    public synchronized int addWin(UUID player, String displayName) {
        String key   = player.toString();
        int    total = wins.getOrDefault(key, 0) + 1;
        wins.put(key, total);
        if (displayName != null) names.put(key, displayName);
        save();
        return total;
    }

    public synchronized void setWins(UUID player, int amount, String displayName) {
        String key = player.toString();
        wins.put(key, Math.max(0, amount));
        if (displayName != null) names.put(key, displayName);
        save();
    }

    public synchronized String getDisplayName(UUID player) {
        return names.getOrDefault(player.toString(), player.toString());
    }

    /** Returns a defensive copy sorted by wins descending. */
    public synchronized List<Map.Entry<String, Integer>> getLeaderboard() {
        return wins.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .toList();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(filePath)) {
            logger.info("[HighBL] wins.json not found — starting fresh.");
            return;
        }
        try (Reader r = Files.newBufferedReader(filePath)) {
            Map<String, Object> root = GSON.fromJson(r, Map.class);
            if (root == null) return;

            Object rawWins  = root.get("wins");
            Object rawNames = root.get("names");

            if (rawWins instanceof Map<?,?> wMap) {
                wMap.forEach((k, v) -> {
                    if (k instanceof String ks && v instanceof Number n)
                        wins.put(ks, n.intValue());
                });
            }
            if (rawNames instanceof Map<?,?> nMap) {
                nMap.forEach((k, v) -> {
                    if (k instanceof String ks && v instanceof String vs)
                        names.put(ks, vs);
                });
            }
            logger.info("[HighBL] Loaded {} win record(s).", wins.size());
        } catch (IOException e) {
            logger.error("[HighBL] Failed to load wins.json", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            try (Writer w = Files.newBufferedWriter(filePath)) {
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("wins",  wins);
                root.put("names", names);
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            logger.error("[HighBL] Failed to save wins.json", e);
        }
    }
}
