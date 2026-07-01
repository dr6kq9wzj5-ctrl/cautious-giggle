package com.highbl.fabric.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C payload — this server sends messages on this channel; Velocity intercepts
 * them before they reach the client.  Channel: highbl:toproxy
 */
public record HighBLToProxyPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<HighBLToProxyPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("highbl", "toproxy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HighBLToProxyPayload> CODEC =
            StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data()),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new HighBLToProxyPayload(bytes);
                }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
