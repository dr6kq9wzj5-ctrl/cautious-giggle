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

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Commands registered on the LOBBY server only.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  /arena ping                    — test Velocity connection  → 1/0          │
 * │  /arena queue add <player>      — add player to dispatch queue → 1/0       │
 * │  /arena queue remove <player>   — remove player from queue   → 1/0         │
 * │  /arena queue list              — show current queue         → queue size  │
 * │  /arena queue clear             — empty the queue            → 1           │
 * │  /arena dispatch                — send queued players to a free arena → 1/0│
 * │  /arena send <players>          — quick-send without touching the queue → 1/0│
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * DATAPACK INTEGRATION
 * ─────────────────────
 * Your datapack can trigger the transfer like this:
 *
 *   # Tag every player who pressed the button
 *   execute as @a[tag=ready] run arena queue add @s
 *
 *   # Then fire dispatch from a function or command block
 *   arena dispatch
 *
 * The mod queues them up and sends a single SEND_GROUP message to Velocity.
 * All queued players are cleared automatically after dispatch.
 */
public class ArenaCommands {

    /** Shared queue — UUIDs of players waiting to be sent to an arena. */
    private static final List<UUID> QUEUE = new CopyOnWriteArrayList<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("arena")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))

                .then(Commands.literal("ping")
                    .executes(ArenaCommands::executePing))

                .then(Commands.literal("queue")
                    .then(Commands.literal("add")
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(ArenaCommands::executeQueueAdd)))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(ArenaCommands::executeQueueRemove)))
                    .then(Commands.literal("list")
                        .executes(ArenaCommands::executeQueueList))
                    .then(Commands.literal("clear")
                        .executes(ArenaCommands::executeQueueClear)))

                .then(Commands.literal("dispatch")
                    .executes(ArenaCommands::executeDispatch))

                .then(Commands.literal("send")
                    .then(Commands.argument("players", EntityArgument.players())
                        .executes(ArenaCommands::executeSend)))
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

    private static int executeQueueAdd(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

        if (QUEUE.contains(target.getUUID())) {
            final int pos = QUEUE.indexOf(target.getUUID()) + 1;
            ctx.getSource().sendSuccess(() ->
                Component.literal("§e[HighBL] §f" + target.getName().getString()
                    + " is already in the queue at position §e" + pos), false);
            return pos;
        }

        QUEUE.add(target.getUUID());
        final int size = QUEUE.size();
        ctx.getSource().sendSuccess(() ->
            Component.literal("§a[HighBL] §fAdded §e" + target.getName().getString()
                + " §fto queue at position §e" + size), false);
        return size;
    }

    private static int executeQueueRemove(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean removed = QUEUE.remove(target.getUUID());

        if (removed) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("§6[HighBL] §fRemoved §e" + target.getName().getString() + " §ffrom queue"), false);
        } else {
            ctx.getSource().sendSuccess(() ->
                Component.literal("§c[HighBL] §f" + target.getName().getString() + " is not in the queue"), false);
        }
        return removed ? 1 : 0;
    }

    private static int executeQueueList(CommandContext<CommandSourceStack> ctx) {
        int size = QUEUE.size();
        if (size == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§6[HighBL] §fQueue is empty."), false);
        } else {
            ctx.getSource().sendSuccess(() ->
                Component.literal("§6[HighBL] §fQueue (§e" + size + "§f player(s)):"), false);

            var mgr = ctx.getSource().getServer().getPlayerList();
            QUEUE.forEach(uuid -> {
                ServerPlayer p = mgr.getPlayer(uuid);
                String label = p != null ? "§e" + p.getName().getString() : "§7(offline) " + uuid;
                ctx.getSource().sendSuccess(() -> Component.literal("  · " + label), false);
            });
        }
        return size;
    }

    private static int executeQueueClear(CommandContext<CommandSourceStack> ctx) {
        int count = QUEUE.size();
        QUEUE.clear();
        ctx.getSource().sendSuccess(() ->
            Component.literal("§6[HighBL] §fCleared §e" + count + " §fplayer(s) from queue."), false);
        return 1;
    }

    private static int executeDispatch(CommandContext<CommandSourceStack> ctx) {
        if (QUEUE.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] §fQueue is empty — use /arena queue add <player> first."));
            return 0;
        }

        var mgr = ctx.getSource().getServer().getPlayerList();
        ServerPlayer transport = QUEUE.stream()
            .map(mgr::getPlayer)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (transport == null) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] §fNo queued players are currently online."));
            return 0;
        }

        String uuids   = QUEUE.stream().map(UUID::toString).collect(Collectors.joining(","));
        int    sending = QUEUE.size();
        NetworkManager.sendToProxy(transport, "SEND_GROUP|" + uuids);
        QUEUE.clear();

        ctx.getSource().sendSuccess(() ->
            Component.literal("§a[HighBL] §fDispatching §e" + sending + " §fplayer(s) to Velocity..."), false);
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

        ServerPlayer first = players.iterator().next();
        NetworkManager.sendToProxy(first, "SEND_GROUP|" + uuids);

        final int count = players.size();
        ctx.getSource().sendSuccess(() ->
            Component.literal("§a[HighBL] §fSending §e" + count + " §fplayer(s) to an arena..."), false);
        return 1;
    }

    public static List<UUID> getQueue() { return Collections.unmodifiableList(QUEUE); }

    public static void removeFromQueue(UUID uuid) { QUEUE.remove(uuid); }
}
