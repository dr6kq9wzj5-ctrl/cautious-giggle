package com.highbl.fabric;

import com.highbl.fabric.commands.ArenaCommands;
import com.highbl.fabric.commands.LobbyCommands;
import com.highbl.fabric.commands.WinsCommand;
import com.highbl.fabric.config.HighBLConfig;
import com.highbl.fabric.network.NetworkManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HighBL Fabric Mod — deploy this JAR to BOTH lobby and arena servers.
 *
 * The config file  config/highbl.json  controls which role the server plays.
 * Set  "serverType": "LOBBY"  on the lobby, and  "serverType": "ARENA"  on
 * each arena.  The correct commands activate automatically.
 *
 * Protocol channels (Fabric ↔ Velocity):
 *   highbl:toproxy  — Fabric backend  →  Velocity
 *   highbl:toserver — Velocity        →  Fabric backend
 *
 * Message format: plain UTF-8 strings, pipe-delimited fields
 *   PING                              test connection
 *   SEND_GROUP|uuid1,uuid2,...        lobby: send these players to an arena
 *   RETURN_TO_LOBBY|uuid1,uuid2,...   arena: return players (no win)
 *   GAME_END|winnerUUID|allUUIDs      arena: record winner + return everyone
 *   GET_WINS|targetUUID|playerName    either: ask Velocity for stored wins
 */
public class HighBLMod implements ModInitializer {

    public static final String MOD_ID = "highbl";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    private static HighBLConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("[HighBL] Loading...");

        config = HighBLConfig.load();
        LOGGER.info("[HighBL] Server role: {}", config.getServerType());

        // Register plugin-message channels (send + receive)
        NetworkManager.register();

        // Register commands depending on server role
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /wins — available everywhere
            WinsCommand.register(dispatcher);

            switch (config.getServerType()) {
                case LOBBY -> {
                    ArenaCommands.register(dispatcher);
                    LOGGER.info("[HighBL] Registered LOBBY commands (/arena ...)");
                }
                case ARENA -> {
                    LobbyCommands.register(dispatcher);
                    LOGGER.info("[HighBL] Registered ARENA commands (/lobby ...)");
                }
                default -> LOGGER.warn("[HighBL] serverType is UNKNOWN — set it in config/highbl.json");
            }
        });

        // Remove players from the dispatch queue when they disconnect from the lobby
        if (config.getServerType() == HighBLConfig.ServerType.LOBBY) {
            ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ArenaCommands.removeFromQueue(handler.player.getUUID()));
            LOGGER.info("[HighBL] Registered queue cleanup on player disconnect.");
        }

        LOGGER.info("[HighBL] Ready.");
    }

    public static HighBLConfig getConfig() { return config; }
}
