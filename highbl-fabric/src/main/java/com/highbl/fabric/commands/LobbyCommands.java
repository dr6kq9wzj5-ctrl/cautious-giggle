package com.highbl.fabric.commands;

import com.highbl.fabric.network.NetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Commands registered on ARENA servers only.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  /lobby ping              — test Velocity connection                     │
 * │  /lobby send <players>    — send specific player(s) to lobby             │
 * │  /lobby win <player>      — record winner, then return everyone to lobby │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * DATAPACK INTEGRATION
 * ─────────────────────
 * At the end of a round your datapack can do:
 *
 *   # No winner (e.g. time ran out) — send everyone
 *   lobby send @a
 *
 *   # Someone won — assume the winner has the tag "arena_winner"
 *   execute as @a[tag=arena_winner,limit=1] run lobby win @s
 *
 * The mod sends a single GAME_END message to Velocity which atomically
 * records the win and transfers everyone back to the lobby.
 */
public class LobbyCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("lobby")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))

                .then(Commands.literal("ping")
                    .executes(LobbyCommands::executePing))

                .then(Commands.literal("send")
                    .then(Commands.argument("players", EntityArgument.players())
                        .executes(LobbyCommands::executeSend)))

                .then(Commands.literal("win")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(LobbyCommands::executeWin)))
        );
    }

    private static int executePing(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] Must be run by a player"));
            return 0;
        }
        NetworkManager.sendToProxy(player, "PING");
        ctx.getSource().sendSuccess(() -> Component.literal("§6[HighBL] §fPing sent — watch for PONG..."), false);
        return 1;
    }

    private static int executeSend(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");

        if (players.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] §fNo players matched."));
            return 0;
        }

        String uuids = players.stream()
            .map(p -> p.getUUID().toString())
            .collect(Collectors.joining(","));

        NetworkManager.sendToProxy(players.iterator().next(), "RETURN_TO_LOBBY|" + uuids);

        final int count = players.size();
        ctx.getSource().sendSuccess(() ->
            Component.literal("§6[HighBL] §fSending §e" + count + " §fplayer(s) to lobby..."), false);
        return 1;
    }

    private static int executeWin(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer winner  = EntityArgument.getPlayer(ctx, "player");
        List<ServerPlayer> all = ctx.getSource().getServer().getPlayerList().getPlayers();

        String winnerUuid = winner.getUUID().toString();
        String allUuids   = all.stream()
            .map(p -> p.getUUID().toString())
            .collect(Collectors.joining(","));

        NetworkManager.sendToProxy(winner, "GAME_END|" + winnerUuid + "|" + allUuids);

        String winnerName = winner.getName().getString();
        all.forEach(p -> p.sendSystemMessage(Component.literal(
            "§6[HighBL] §e" + winnerName + " §fwon! Returning to lobby..."
        )));

        ctx.getSource().sendSuccess(() ->
            Component.literal("§a[HighBL] §fRecorded win for §e" + winnerName
                + " §fand returning §e" + all.size() + " §fplayer(s) to lobby."), false);
        return 1;
    }
}
