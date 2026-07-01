package com.highbl.velocity.commands;

import com.highbl.velocity.arena.ArenaManager;
import com.highbl.velocity.storage.WinStorage;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /proxy (alias /hbl) — proxy-level management command.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  /proxy arenas                       — list arena status                 │
 * │  /proxy arenas add <server>          — add server to arena pool          │
 * │  /proxy arenas remove <server>       — remove from pool                  │
 * │  /proxy arenas refresh               — re-auto-detect from velocity.toml │
 * │                                                                          │
 * │  /proxy wins                         — top-10 leaderboard                │
 * │  /proxy wins <player>                — check a player's wins             │
 * │  /proxy wins set <player> <n>        — override win count                │
 * │  /proxy wins add <player> <n>        — add to win count                  │
 * │                                                                          │
 * │  /proxy move <player> <server>       — manually move a player            │
 * │  /proxy test                         — dump all server/player info       │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Permission: highbl.admin  (console always allowed)
 */
public class ProxyCommand implements SimpleCommand {

    private final ProxyServer  proxy;
    private final ArenaManager arenas;
    private final WinStorage   wins;
    private final Logger       logger;

    public ProxyCommand(ProxyServer proxy, ArenaManager arenas, WinStorage wins, Logger logger) {
        this.proxy  = proxy;
        this.arenas = arenas;
        this.wins   = wins;
        this.logger = logger;
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Override
    public void execute(Invocation invocation) {
        CommandSource src  = invocation.source();
        String[]      args = invocation.arguments();

        if (args.length == 0) { sendHelp(src); return; }

        switch (args[0].toLowerCase()) {
            case "arenas"  -> handleArenas(src, args);
            case "wins"    -> handleWins(src, args);
            case "move"    -> handleMove(src, args);
            case "test"    -> handleTest(src);
            default        -> sendHelp(src);
        }
    }

    // ── /proxy arenas ─────────────────────────────────────────────────────────

    private void handleArenas(CommandSource src, String[] args) {
        if (args.length == 1) {
            // Status listing
            List<ArenaManager.ArenaStatus> statuses = arenas.getStatus();
            if (statuses.isEmpty()) {
                src.sendMessage(msg("§eNo arenas configured. Try §f/proxy arenas refresh §eor §f/proxy arenas add <name>", NamedTextColor.YELLOW));
                return;
            }
            src.sendMessage(Component.text("═══ Arena Status ═══", NamedTextColor.GOLD));
            statuses.forEach(s -> src.sendMessage(
                Component.text("  " + s.name() + "  ", NamedTextColor.WHITE)
                    .append(LegacyComponentSerializer.legacySection().deserialize(s.label()))
            ));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length < 3) { src.sendMessage(usage("/proxy arenas add <servername>")); return; }
                if (proxy.getServer(args[2]).isEmpty()) {
                    src.sendMessage(err("Server '" + args[2] + "' not found in velocity.toml"));
                    return;
                }
                arenas.addArena(args[2]);
                src.sendMessage(ok("Added §e" + args[2] + "§a to arena pool."));
            }
            case "remove" -> {
                if (args.length < 3) { src.sendMessage(usage("/proxy arenas remove <servername>")); return; }
                arenas.removeArena(args[2]);
                src.sendMessage(ok("Removed §e" + args[2] + "§a from arena pool."));
            }
            case "refresh" -> {
                arenas.autoDetect();
                src.sendMessage(ok("Arena pool refreshed: §e" + arenas.getArenaServerNames()));
            }
            default -> sendHelp(src);
        }
    }

    // ── /proxy wins ───────────────────────────────────────────────────────────

    private void handleWins(CommandSource src, String[] args) {
        // /proxy wins  →  leaderboard
        if (args.length == 1) {
            var board = wins.getLeaderboard();
            if (board.isEmpty()) {
                src.sendMessage(msg("No wins recorded yet.", NamedTextColor.GOLD));
                return;
            }
            src.sendMessage(Component.text("═══ Win Leaderboard ═══", NamedTextColor.GOLD));
            int rank = 1;
            for (var entry : board.stream().limit(10).toList()) {
                String name  = wins.getDisplayName(UUID.fromString(entry.getKey()));
                int    count = entry.getValue();
                src.sendMessage(Component.text(
                    "  " + rank++ + ". " + name + " — " + count + " win(s)", NamedTextColor.WHITE));
            }
            return;
        }

        // /proxy wins set|add <player> <n>
        if (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("add")) {
            if (args.length < 4) { src.sendMessage(usage("/proxy wins " + args[1] + " <player> <amount>")); return; }

            Optional<Player> pOpt = proxy.getPlayer(args[2]);
            if (pOpt.isEmpty()) {
                src.sendMessage(err("Player '" + args[2] + "' is not online."));
                return;
            }
            Player p;
            int amount;
            try { amount = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) { src.sendMessage(err("Not a number: " + args[3])); return; }

            p = pOpt.get();
            if (args[1].equalsIgnoreCase("set")) {
                wins.setWins(p.getUniqueId(), amount, p.getUsername());
                src.sendMessage(ok("Set §e" + p.getUsername() + "§a's wins to §e" + amount));
            } else {
                int newTotal = wins.getWins(p.getUniqueId()) + amount;
                wins.setWins(p.getUniqueId(), newTotal, p.getUsername());
                src.sendMessage(ok("Added §e" + amount + "§a wins to §e" + p.getUsername()
                    + "§a (total: §e" + newTotal + "§a)"));
            }
            return;
        }

        // /proxy wins <player>  →  look-up
        String targetName = args[1];
        Optional<Player> pOpt = proxy.getPlayer(targetName);
        if (pOpt.isPresent()) {
            int count = wins.getWins(pOpt.get().getUniqueId());
            src.sendMessage(msg("§e" + targetName + " §fhas §6" + count + " §fwin(s).", NamedTextColor.WHITE));
        } else {
            src.sendMessage(err("Player '" + targetName + "' is not online (UUID lookup requires them to be connected)."));
        }
    }

    // ── /proxy move <player> <server> ─────────────────────────────────────────

    private void handleMove(CommandSource src, String[] args) {
        if (args.length < 3) { src.sendMessage(usage("/proxy move <player> <server>")); return; }

        Optional<Player>         pOpt = proxy.getPlayer(args[1]);
        Optional<RegisteredServer> sOpt = proxy.getServer(args[2]);

        if (pOpt.isEmpty()) { src.sendMessage(err("Player '" + args[1] + "' not online.")); return; }
        if (sOpt.isEmpty()) { src.sendMessage(err("Server '" + args[2] + "' not found."));  return; }

        pOpt.get().createConnectionRequest(sOpt.get()).fireAndForget();
        src.sendMessage(ok("Moved §e" + args[1] + "§a → §e" + args[2]));
    }

    // ── /proxy test ───────────────────────────────────────────────────────────

    private void handleTest(CommandSource src) {
        src.sendMessage(Component.text("═══ Proxy Status ═══", NamedTextColor.GOLD));
        src.sendMessage(Component.text("Registered servers:", NamedTextColor.YELLOW));

        Set<String> arenaSet = new HashSet<>(arenas.getArenaServerNames());
        proxy.getAllServers().forEach(s -> {
            String  name    = s.getServerInfo().getName();
            int     players = s.getPlayersConnected().size();
            boolean isArena = arenaSet.contains(name);
            NamedTextColor c = isArena ? NamedTextColor.AQUA : NamedTextColor.WHITE;
            String tag = isArena ? " [ARENA]" : (name.toLowerCase().contains("lobby") ? " [LOBBY]" : "");
            src.sendMessage(Component.text("  " + name + tag + "  — " + players + " player(s)", c));
        });

        src.sendMessage(Component.text("Win records: " + wins.getLeaderboard().size(), NamedTextColor.WHITE));
        src.sendMessage(Component.text("Channel highbl:toproxy  registered: true", NamedTextColor.GREEN));
        src.sendMessage(Component.text("Channel highbl:toserver registered: true", NamedTextColor.GREEN));
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) return filter(List.of("arenas", "wins", "move", "test"), args.length == 1 ? args[0] : "");
        if (args.length == 2) return switch (args[0].toLowerCase()) {
            case "arenas" -> filter(List.of("add", "remove", "refresh"), args[1]);
            case "wins"   -> filter(
                concat(List.of("set", "add"),
                    proxy.getAllPlayers().stream().map(Player::getUsername).toList()),
                args[1]);
            case "move"   -> filter(proxy.getAllPlayers().stream().map(Player::getUsername).toList(), args[1]);
            default       -> List.of();
        };
        if (args.length == 3) return switch (args[0].toLowerCase()) {
            case "arenas" -> args[1].equalsIgnoreCase("add")
                ? filter(proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).toList(), args[2])
                : filter(arenas.getArenaServerNames(), args[2]);
            case "wins"   -> filter(proxy.getAllPlayers().stream().map(Player::getUsername).toList(), args[2]);
            case "move"   -> filter(proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).toList(), args[2]);
            default       -> List.of();
        };
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Console always permitted
        if (!(invocation.source() instanceof Player)) return true;
        return invocation.source().hasPermission("highbl.admin");
    }

    // ── Component helpers ─────────────────────────────────────────────────────

    private static Component ok(String text) {
        return Component.text("[HighBL] ", NamedTextColor.GREEN)
            .append(LegacyComponentSerializer.legacySection().deserialize(text)
                .colorIfAbsent(NamedTextColor.GREEN));
    }
    private static Component err(String text) {
        return Component.text("[HighBL] ", NamedTextColor.RED)
            .append(LegacyComponentSerializer.legacySection().deserialize(text)
                .colorIfAbsent(NamedTextColor.RED));
    }
    private static Component msg(String text, NamedTextColor color) {
        return Component.text("[HighBL] ", NamedTextColor.GOLD)
            .append(LegacyComponentSerializer.legacySection().deserialize(text)
                .colorIfAbsent(color));
    }
    private static Component usage(String text) {
        return Component.text("Usage: " + text, NamedTextColor.YELLOW);
    }

    private static void sendHelp(CommandSource src) {
        src.sendMessage(Component.text("═══ HighBL /proxy ═══", NamedTextColor.GOLD));
        List.of(
            "/proxy arenas                — list arena status",
            "/proxy arenas add <srv>      — add to pool",
            "/proxy arenas remove <srv>   — remove from pool",
            "/proxy arenas refresh        — re-detect arenas",
            "/proxy wins                  — leaderboard",
            "/proxy wins <player>         — player wins",
            "/proxy wins set <p> <n>      — set wins",
            "/proxy wins add <p> <n>      — add wins",
            "/proxy move <player> <srv>   — move player",
            "/proxy test                  — server status dump"
        ).forEach(line -> src.sendMessage(Component.text("  " + line, NamedTextColor.WHITE)));
    }

    // ── Misc utils ────────────────────────────────────────────────────────────

    private static List<String> filter(List<String> opts, String prefix) {
        if (prefix == null || prefix.isEmpty()) return opts;
        String lc = prefix.toLowerCase();
        return opts.stream().filter(o -> o.toLowerCase().startsWith(lc)).collect(Collectors.toList());
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) out.addAll(l);
        return out;
    }
}
