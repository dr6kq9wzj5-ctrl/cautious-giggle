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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /wins — available on BOTH lobby and arena servers.
 *
 *   /wins              — check your own wins (any player)
 *   /wins <player>     — check another player's wins (op only)
 *
 * The request is forwarded to Velocity; the response comes back
 * asynchronously as a WINS_RESULT plugin message.
 */
public class WinsCommand {

    /** Populated by WINS_RESULT / WIN_CONFIRMED messages from Velocity. */
    private static final Map<UUID, Integer> CACHE = new ConcurrentHashMap<>();

    /** Called by MessageHandler when a WINS_RESULT or WIN_CONFIRMED arrives. */
    public static void updateCache(UUID uuid, int wins) {
        CACHE.put(uuid, wins);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("wins")
                .executes(WinsCommand::executeOwn)
                .then(Commands.argument("player", EntityArgument.player())
                    .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(WinsCommand::executeOther))
        );
    }

    private static int executeOwn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] Must be run by a player"));
            return 0;
        }
        NetworkManager.sendToProxy(player,
            "GET_WINS|" + player.getUUID() + "|" + player.getName().getString());
        ctx.getSource().sendSuccess(() -> Component.literal("§6[HighBL] §fFetching your wins..."), false);
        return CACHE.getOrDefault(player.getUUID(), 0);
    }

    private static int executeOther(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer requester = ctx.getSource().getPlayer();
        if (requester == null) {
            ctx.getSource().sendFailure(Component.literal("§c[HighBL] Must be run by a player"));
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        NetworkManager.sendToProxy(requester,
            "GET_WINS|" + target.getUUID() + "|" + target.getName().getString());
        ctx.getSource().sendSuccess(() ->
            Component.literal("§6[HighBL] §fFetching wins for §e" + target.getName().getString() + "§f..."), false);
        return CACHE.getOrDefault(target.getUUID(), 0);
    }
}
