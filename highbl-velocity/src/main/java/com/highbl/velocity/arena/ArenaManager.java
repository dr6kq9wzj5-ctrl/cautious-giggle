package com.highbl.velocity.arena;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Knows which servers are arenas, which are free, and which are reserved.
 *
 * Arena servers are auto-detected by name containing "arena" (case-insensitive).
 * You can also manage the list at runtime with /proxy arenas add|remove|refresh.
 *
 * RESERVATION SYSTEM
 * ──────────────────
 * When a group is about to be sent to an arena, we immediately mark it
 * "reserved" for 10 seconds.  This prevents a second simultaneous group
 * from landing on the same server before the first group's transfer
 * packets arrive.
 */
public class ArenaManager {

    private final ProxyServer  proxy;
    private final Logger       logger;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Ordered list so we always try arena-1 before arena-2, etc. */
    private final List<String>  arenaNames  = new CopyOnWriteArrayList<>();
    /** Short-lived reservation set to prevent double-booking */
    private final Set<String>   reserved    = ConcurrentHashMap.newKeySet();

    public ArenaManager(ProxyServer proxy, Logger logger) {
        this.proxy  = proxy;
        this.logger = logger;
        autoDetect();
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Called at startup and by /proxy arenas refresh */
    public void autoDetect() {
        arenaNames.clear();
        proxy.getAllServers().forEach(s -> {
            if (s.getServerInfo().getName().toLowerCase().contains("arena")) {
                arenaNames.add(s.getServerInfo().getName());
            }
        });
        // Sort so we get a predictable fill order (arena-1, arena-2, ...)
        Collections.sort(arenaNames);
        logger.info("[HighBL] Arena servers: {}", arenaNames);
    }

    public void addArena(String name) {
        if (!arenaNames.contains(name)) {
            arenaNames.add(name);
            Collections.sort(arenaNames);
            logger.info("[HighBL] Manually added arena: {}", name);
        }
    }

    public void removeArena(String name) {
        arenaNames.remove(name);
        logger.info("[HighBL] Removed arena: {}", name);
    }

    // ── Arena selection ───────────────────────────────────────────────────────

    /**
     * Find the first arena that has 0 players and is not reserved.
     * Returns empty if no arena is available.
     */
    public Optional<RegisteredServer> findFreeArena() {
        for (String name : arenaNames) {
            Optional<RegisteredServer> opt = proxy.getServer(name);
            if (opt.isEmpty()) continue;
            RegisteredServer server = opt.get();
            if (server.getPlayersConnected().isEmpty() && !reserved.contains(name)) {
                return Optional.of(server);
            }
        }
        return Optional.empty();
    }

    /**
     * Reserve an arena for up to 10 s to prevent a second group being sent
     * to the same server before the first group's transfer completes.
     */
    public void reserve(String name) {
        reserved.add(name);
        scheduler.schedule(() -> reserved.remove(name), 10, TimeUnit.SECONDS);
        logger.debug("[HighBL] Reserved {} for 10 s", name);
    }

    public void releaseReservation(String name) {
        reserved.remove(name);
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public List<ArenaStatus> getStatus() {
        List<ArenaStatus> out = new ArrayList<>();
        for (String name : arenaNames) {
            proxy.getServer(name).ifPresent(s -> {
                int  players = s.getPlayersConnected().size();
                boolean res  = reserved.contains(name);
                out.add(new ArenaStatus(name, players, res));
            });
        }
        return out;
    }

    public List<String> getArenaServerNames() { return Collections.unmodifiableList(arenaNames); }

    public record ArenaStatus(String name, int playerCount, boolean reserved) {
        public boolean isFree() { return playerCount == 0 && !reserved; }
        public String label() {
            if (isFree())        return "§aFREE";
            if (reserved)        return "§eRESERVED";
            return "§c" + playerCount + " player(s)";
        }
    }
}
