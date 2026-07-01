package com.highbl.velocity;

import com.google.inject.Inject;
import com.highbl.velocity.arena.ArenaManager;
import com.highbl.velocity.commands.ProxyCommand;
import com.highbl.velocity.network.MessageHandler;
import com.highbl.velocity.storage.WinStorage;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * HighBL Velocity Plugin
 *
 * Channels:
 *   highbl:toproxy  — received from Fabric backends
 *   highbl:toserver — sent back to Fabric backends
 *
 * Protocol (Fabric → Velocity on highbl:toproxy):
 *   PING                              → PONG
 *   SEND_GROUP|uuid1,uuid2,...        → pick free arena, transfer players → TRANSFER_OK|name or TRANSFER_FAIL|reason
 *   RETURN_TO_LOBBY|uuid1,uuid2,...   → move players to lobby server
 *   GAME_END|winnerUUID|allUUIDs      → record win + move all to lobby → WIN_CONFIRMED|uuid|total|name
 *   GET_WINS|uuid|name                → look up wins → WINS_RESULT|uuid|wins|name
 *
 * Proxy commands (/proxy ...):
 *   /proxy arenas [add|remove|refresh]
 *   /proxy wins [<player>|set|add]
 *   /proxy move <player> <server>
 *   /proxy test
 */
@Plugin(
    id          = "highbl-velocity",
    name        = "HighBL Velocity",
    version     = "1.0.0",
    description = "Arena routing and win tracking for HighBL",
    authors     = { "HighBL" }
)
public class HighBLVelocity {

    /** Backend → Velocity channel */
    public static final MinecraftChannelIdentifier TO_PROXY =
        MinecraftChannelIdentifier.from("highbl:toproxy");

    /** Velocity → Backend channel */
    public static final MinecraftChannelIdentifier TO_SERVER =
        MinecraftChannelIdentifier.from("highbl:toserver");

    private final ProxyServer proxy;
    private final Logger      logger;
    private final Path        dataDirectory;

    private ArenaManager arenaManager;
    private WinStorage   winStorage;

    @Inject
    public HighBLVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy         = proxy;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        logger.info("[HighBL] Loading Velocity plugin...");

        // 1. Register plugin-message channels so Velocity doesn't drop them
        proxy.getChannelRegistrar().register(TO_PROXY);
        proxy.getChannelRegistrar().register(TO_SERVER);

        // 2. Initialise subsystems
        winStorage   = new WinStorage(dataDirectory, logger);
        arenaManager = new ArenaManager(proxy, logger);

        // 3. Register the plugin-message listener
        proxy.getEventManager().register(this, new MessageHandler(proxy, logger, arenaManager, winStorage));

        // 4. Register the /proxy command
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("proxy")
                .aliases("hbl")
                .plugin(this)
                .build(),
            new ProxyCommand(proxy, arenaManager, winStorage, logger)
        );

        logger.info("[HighBL] Velocity plugin ready.  Arenas detected: {}", arenaManager.getArenaServerNames());
    }

    // ── Getters (used by command handler) ────────────────────────────────────
    public ProxyServer  getProxy()         { return proxy;         }
    public ArenaManager getArenaManager()  { return arenaManager;  }
    public WinStorage   getWinStorage()    { return winStorage;    }
}
