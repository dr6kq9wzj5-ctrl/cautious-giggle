package com.highbl.velocity.network;

import com.highbl.velocity.HighBLVelocity;
import com.highbl.velocity.arena.ArenaManager;
import com.highbl.velocity.storage.WinStorage;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives pipe-delimited plugin messages from Fabric backends on highbl:toproxy
 * and dispatches the appropriate response.
 *
 * Every reply is written back to the SAME ServerConnection via sendToBackend(),
 * which means the Fabric mod receives it through its registerGlobalReceiver handler.
 */
public class MessageHandler {

    private final ProxyServer   proxy;
    private final Logger        logger;
    private final ArenaManager  arenas;
    private final WinStorage    wins;

    public MessageHandler(ProxyServer proxy, Logger logger, ArenaManager arenas, WinStorage wins) {
        this.proxy  = proxy;
        this.logger = logger;
        this.arenas = arenas;
        this.wins   = wins;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // Only handle messages coming FROM a backend server
        if (!(event.getSource() instanceof ServerConnection conn)) return;
        if (!event.getIdentifier().equals(HighBLVelocity.TO_PROXY))  return;

        // Mark as handled so Velocity doesn't forward it to the client
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String   raw   = new String(event.getData(), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", -1);

        logger.debug("[Proxy ← Backend] {}", raw);

        switch (parts[0]) {
            case "PING"             -> handlePing(conn);
            case "SEND_GROUP"       -> handleSendGroup(parts, conn);
            case "RETURN_TO_LOBBY"  -> handleReturnToLobby(parts);
            case "GAME_END"         -> handleGameEnd(parts, conn);
            case "GET_WINS"         -> handleGetWins(parts, conn);
            default -> logger.warn("[HighBL] Unknown message from backend: {}", raw);
        }
    }

    // ── PING ─────────────────────────────────────────────────────────────────

    private void handlePing(ServerConnection conn) {
        logger.info("[HighBL] PING received from {}", conn.getServerInfo().getName());
        sendToBackend(conn, "PONG");
    }

    // ── SEND_GROUP ────────────────────────────────────────────────────────────

    private void handleSendGroup(String[] parts, ServerConnection conn) {
        if (parts.length < 2 || parts[1].isBlank()) {
            sendToBackend(conn, "TRANSFER_FAIL|Empty UUID list");
            return;
        }

        List<Player> players = resolvePlayers(parts[1]);
        if (players.isEmpty()) {
            sendToBackend(conn, "TRANSFER_FAIL|No online players found");
            return;
        }

        Optional<RegisteredServer> arenaOpt = arenas.findFreeArena();
        if (arenaOpt.isEmpty()) {
            sendToBackend(conn, "TRANSFER_FAIL|No free arena available");
            players.forEach(p -> p.sendMessage(
                Component.text("[HighBL] ", NamedTextColor.GOLD)
                    .append(Component.text("No arenas are free right now — try again shortly.", NamedTextColor.RED))));
            return;
        }

        RegisteredServer arena     = arenaOpt.get();
        String           arenaName = arena.getServerInfo().getName();

        // Reserve immediately to block a second simultaneous request
        arenas.reserve(arenaName);

        logger.info("[HighBL] Sending {} player(s) → {}", players.size(), arenaName);

        int total = players.size();
        AtomicInteger failCount = new AtomicInteger(0);

        players.forEach(p -> {
            p.sendMessage(Component.text("[HighBL] ", NamedTextColor.GOLD)
                .append(Component.text("Sending you to " + arenaName + "!", NamedTextColor.GREEN)));
            p.createConnectionRequest(arena).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    logger.warn("[HighBL] Transfer of {} to {} failed", p.getUsername(), arenaName);
                    p.sendMessage(Component.text("[HighBL] ", NamedTextColor.RED)
                        .append(Component.text("Transfer to " + arenaName + " failed — please try again.", NamedTextColor.RED)));
                    if (failCount.incrementAndGet() == total) {
                        arenas.releaseReservation(arenaName);
                        logger.warn("[HighBL] All transfer(s) to {} failed; reservation released.", arenaName);
                    }
                }
            });
        });

        sendToBackend(conn, "TRANSFER_OK|" + arenaName);
    }

    // ── RETURN_TO_LOBBY ───────────────────────────────────────────────────────

    private void handleReturnToLobby(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) return;

        Optional<RegisteredServer> lobbyOpt = findLobby();
        if (lobbyOpt.isEmpty()) {
            logger.error("[HighBL] Cannot find a lobby server! Check server names in velocity.toml.");
            return;
        }

        RegisteredServer lobby = lobbyOpt.get();
        for (String uuidStr : parts[1].split(",")) {
            resolvePlayer(uuidStr).ifPresent(p -> {
                p.createConnectionRequest(lobby).fireAndForget();
                p.sendMessage(Component.text("[HighBL] ", NamedTextColor.GOLD)
                    .append(Component.text("Returning you to the lobby.", NamedTextColor.WHITE)));
            });
        }
    }

    // ── GAME_END ──────────────────────────────────────────────────────────────
    // Format: GAME_END|winnerUUID|uuid1,uuid2,...

    private void handleGameEnd(String[] parts, ServerConnection conn) {
        if (parts.length < 3) return;

        // 1. Record win
        resolvePlayer(parts[1]).ifPresentOrElse(
            winner -> {
                int total = wins.addWin(winner.getUniqueId(), winner.getUsername());
                logger.info("[HighBL] Win recorded: {} (total {})", winner.getUsername(), total);
                sendToBackend(conn,
                    "WIN_CONFIRMED|" + winner.getUniqueId() + "|" + total + "|" + winner.getUsername());
            },
            () -> {
                // Player offline — still try to record by UUID
                try {
                    UUID uuid  = UUID.fromString(parts[1].trim());
                    int  total = wins.addWin(uuid, null);
                    sendToBackend(conn, "WIN_CONFIRMED|" + uuid + "|" + total + "|" + uuid);
                } catch (IllegalArgumentException e) {
                    logger.warn("[HighBL] GAME_END: bad winner UUID {}", parts[1]);
                }
            }
        );

        // 2. Return everyone to lobby
        String[] returnParts = { "RETURN_TO_LOBBY", parts[2] };
        handleReturnToLobby(returnParts);
    }

    // ── GET_WINS ──────────────────────────────────────────────────────────────
    // Format: GET_WINS|targetUUID|displayName

    private void handleGetWins(String[] parts, ServerConnection conn) {
        if (parts.length < 2) return;
        try {
            UUID   uuid  = UUID.fromString(parts[1].trim());
            String name  = parts.length > 2 ? parts[2] : wins.getDisplayName(uuid);
            int    count = wins.getWins(uuid);
            sendToBackend(conn, "WINS_RESULT|" + uuid + "|" + count + "|" + name);
        } catch (IllegalArgumentException e) {
            logger.warn("[HighBL] GET_WINS: bad UUID {}", parts[1]);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendToBackend(ServerConnection conn, String message) {
        logger.debug("[Proxy → Backend] {}", message);
        conn.sendPluginMessage(HighBLVelocity.TO_SERVER, message.getBytes(StandardCharsets.UTF_8));
    }

    private List<Player> resolvePlayers(String commaSeparated) {
        List<Player> out = new ArrayList<>();
        for (String uuidStr : commaSeparated.split(",")) {
            resolvePlayer(uuidStr).ifPresent(out::add);
        }
        return out;
    }

    private Optional<Player> resolvePlayer(String uuidStr) {
        try {
            return proxy.getPlayer(UUID.fromString(uuidStr.trim()));
        } catch (IllegalArgumentException e) {
            logger.warn("[HighBL] Invalid UUID: {}", uuidStr);
            return Optional.empty();
        }
    }

    private Optional<RegisteredServer> findLobby() {
        return proxy.getAllServers().stream()
            .filter(s -> s.getServerInfo().getName().toLowerCase().contains("lobby"))
            .findFirst();
    }
}
