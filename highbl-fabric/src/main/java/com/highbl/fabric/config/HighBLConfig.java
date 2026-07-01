package com.highbl.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config loaded from  config/highbl.json  on startup.
 *
 * Minimal example for the LOBBY server:
 * {
 *   "serverType": "LOBBY"
 * }
 *
 * Minimal example for an ARENA server:
 * {
 *   "serverType": "ARENA",
 *   "serverId":   "arena-1"
 * }
 */
public class HighBLConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("highbl-config");
    private static final Gson   GSON   = new GsonBuilder().setPrettyPrinting().create();

    public enum ServerType { LOBBY, ARENA, UNKNOWN }

    // ── Fields (mapped directly from JSON) ─────────────────────────────────
    private String serverType = "UNKNOWN";  // LOBBY | ARENA | UNKNOWN
    private String serverId   = "arena-1";  // used for logging / identification

    // ── Public API ──────────────────────────────────────────────────────────
    public ServerType getServerType() {
        try {
            return ServerType.valueOf(serverType.toUpperCase());
        } catch (Exception e) {
            return ServerType.UNKNOWN;
        }
    }

    public String getServerId() { return serverId; }

    // ── Load / save ─────────────────────────────────────────────────────────
    public static HighBLConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("highbl.json");

        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                HighBLConfig cfg = GSON.fromJson(r, HighBLConfig.class);
                if (cfg != null) return cfg;
            } catch (IOException e) {
                LOGGER.error("[HighBL] Failed to read config, using defaults", e);
            }
        }

        // First-run: write a default config so the admin knows what to edit
        HighBLConfig defaults = new HighBLConfig();
        defaults.save(path);
        LOGGER.warn("[HighBL] Created default config at {}  — set 'serverType' to LOBBY or ARENA!", path);
        return defaults;
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            LOGGER.error("[HighBL] Failed to save config", e);
        }
    }
}
