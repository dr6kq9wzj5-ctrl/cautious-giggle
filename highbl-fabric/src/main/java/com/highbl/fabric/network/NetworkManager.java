package com.highbl.fabric.network;

import com.highbl.fabric.HighBLMod;
import com.highbl.fabric.handlers.MessageHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around Fabric's plugin-messaging API (CustomPacketPayload, 1.21.4+).
 *
 * Channels:
 *   highbl:toproxy  — this server → Velocity (Velocity intercepts before client sees it)
 *   highbl:toserver — Velocity   → this server (arrives as a C2S plugin message)
 */
public class NetworkManager {

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(HighBLToServerPayload.TYPE, HighBLToServerPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HighBLToProxyPayload.TYPE, HighBLToProxyPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HighBLToServerPayload.TYPE, (payload, context) -> {
            String msg = new String(payload.data(), StandardCharsets.UTF_8);
            context.server().execute(() ->
                MessageHandler.handle(msg, context.server(), context.player()));
        });

        HighBLMod.LOGGER.info("[HighBL] Plugin-message channels registered.");
    }

    /**
     * Send a plain-text message to Velocity via the given player's connection.
     * Velocity intercepts packets on highbl:toproxy before they reach the client.
     *
     * @param player  Any online player (used as a transport handle)
     * @param message Pipe-delimited protocol string, e.g. "SEND_GROUP|uuid1,uuid2"
     */
    public static void sendToProxy(ServerPlayer player, String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        ServerPlayNetworking.send(player, new HighBLToProxyPayload(data));
        HighBLMod.LOGGER.debug("[HighBL → Proxy] {}", message);
    }
}
