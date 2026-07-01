package com.highbl.fabric.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S payload — Velocity pushes messages onto this channel using the player
 * connection as a transport.  Channel: highbl:toserver
 */
public record HighBLToServerPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<HighBLToServerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("highbl", "toserver"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HighBLToServerPayload> CODEC =
            StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data()),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new HighBLToServerPayload(bytes);
                }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
