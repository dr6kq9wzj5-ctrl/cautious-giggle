package com.highbl.fabric.handlers;

import com.highbl.fabric.HighBLMod;
import com.highbl.fabric.commands.WinsCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.UUID;

/**
 * Handles every message pushed to this backend by Velocity (on highbl:toserver).
 *
 * Protocol (Velocity → Fabric):
 *   PONG                            — response to our PING
 *   TRANSFER_OK|arenaName           — group was successfully moved to arenaName
 *   TRANSFER_FAIL|reason            — no arena available or other error
 *   WIN_CONFIRMED|uuid|totalWins    — win was stored; notify the winner
 *   WINS_RESULT|uuid|wins|name      — response to GET_WINS
 */
public class MessageHandler {

    public static void handle(String raw, MinecraftServer server, ServerPlayer via) {
        HighBLMod.LOGGER.debug("[Proxy → HighBL] {}", raw);

        String[] parts = raw.split("\\|", -1);
        if (parts.length == 0) return;

        switch (parts[0]) {

            case "PONG" -> {
                HighBLMod.LOGGER.info("[HighBL] PONG received — proxy connection is healthy.");
                broadcastToOps(server, "§a[HighBL] §fProxy connection: §aOK ✔");
            }

            case "TRANSFER_OK" -> {
                String arena = parts.length > 1 ? parts[1] : "unknown";
                HighBLMod.LOGGER.info("[HighBL] Transfer OK → {}", arena);
                broadcastToOps(server, "§a[HighBL] §fGroup transferred to §e" + arena);
            }

            case "TRANSFER_FAIL" -> {
                String reason = parts.length > 1 ? parts[1] : "unknown reason";
                HighBLMod.LOGGER.warn("[HighBL] Transfer FAILED: {}", reason);
                broadcastToOps(server, "§c[HighBL] §fTransfer failed: " + reason);

                if (via != null && server.getPlayerList().getPlayer(via.getUUID()) != null) {
                    via.sendSystemMessage(Component.literal("§c[HighBL] §fCouldn't find a free arena: " + reason));
                }
            }

            case "WIN_CONFIRMED" -> {
                if (parts.length < 3) return;
                try {
                    UUID uuid   = UUID.fromString(parts[1].trim());
                    int  total  = Integer.parseInt(parts[2].trim());
                    String name = parts.length > 3 ? parts[3] : "Player";
                    WinsCommand.updateCache(uuid, total);
                    ServerPlayer winner = server.getPlayerList().getPlayer(uuid);
                    if (winner != null) {
                        winner.sendSystemMessage(Component.literal(
                            "§6[HighBL] §fWin recorded! You now have §6" + total + " win(s)§f."
                        ));
                    }
                    broadcastToOps(server, "§6[HighBL] §e" + name + " §fnow has §6" + total + " win(s)");
                } catch (Exception e) {
                    HighBLMod.LOGGER.error("[HighBL] Bad WIN_CONFIRMED payload: {}", raw, e);
                }
            }

            case "WINS_RESULT" -> {
                if (parts.length < 3) return;
                try {
                    UUID   uuid = UUID.fromString(parts[1].trim());
                    int    wins = Integer.parseInt(parts[2].trim());
                    String name = parts.length > 3 ? parts[3] : uuid.toString();
                    WinsCommand.updateCache(uuid, wins);

                    String display = "§6[HighBL] §e" + name + " §fhas §6" + wins + " win(s)§f.";

                    if (via != null && server.getPlayerList().getPlayer(via.getUUID()) != null) {
                        via.sendSystemMessage(Component.literal(display));
                    } else {
                        broadcastToOps(server, display);
                    }
                } catch (Exception e) {
                    HighBLMod.LOGGER.error("[HighBL] Bad WINS_RESULT payload: {}", raw, e);
                }
            }

            default -> HighBLMod.LOGGER.warn("[HighBL] Unknown message from proxy: {}", raw);
        }
    }

    private static void broadcastToOps(MinecraftServer server, String message) {
        server.getPlayerList().getPlayers().forEach(p -> {
            if (p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                p.sendSystemMessage(Component.literal(message));
            }
        });
    }
}
