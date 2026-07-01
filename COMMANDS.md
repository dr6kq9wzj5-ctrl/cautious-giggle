# HighBL — Command Reference

> See [README.md](README.md) for how the system works, setup, and configuration.

---

## Lobby server — Gamemaster or higher

| Command                         | Description                                     | Returns                          |
| -------------------------------- | ----------------------------------------------- | -------------------------------- |
| `/arena ping`                   | Test lobby → Velocity connection                | `1` ok · `0` not a player       |
| `/arena queue add <player>`     | Add a player to the dispatch queue              | queue position (1-based)         |
| `/arena queue remove <player>`  | Remove a player from the queue                  | `1` removed · `0` not in queue  |
| `/arena queue list`             | Show everyone currently queued                  | queue size (0 – N)               |
| `/arena queue clear`            | Empty the queue                                 | `1` always                       |
| `/arena dispatch`               | Send ALL queued players to the first free arena | `1` sent · `0` empty / offline  |
| `/arena send <players>`         | Quick-send players (bypasses the queue)         | `1` sent · `0` no match         |
| `/wins`                         | Check your own win count (any player)           | cached win count · `0` not a player or uncached |
| `/wins <player>`                | Check another player's wins (Gamemaster+)       | cached win count · `0` not a player or uncached |

## Arena servers — Gamemaster or higher

| Command                    | Description                                       | Returns                    |
| --------------------------- | ------------------------------------------------- | --------------------------- |
| `/lobby ping`              | Test arena → Velocity connection                  | `1` ok · `0` not a player |
| `/lobby send <players>`    | Send specific player(s) to lobby (supports `@a`)  | `1` sent · `0` no match   |
| `/lobby win <player>`      | Record a win for `<player>`, then return everyone | `1` always                 |
| `/wins`                    | Check your own win count (any player)             | cached win count · `0` not a player or uncached |
| `/wins <player>`           | Check another player's wins (Gamemaster+)         | cached win count · `0` not a player or uncached |

## Velocity proxy — console or `highbl.admin` permission

Velocity commands use `void execute()` — no integer return value.

| Command                           | Description                                           |
| ----------------------------------- | ----------------------------------------------------- |
| `/proxy arenas`                   | List arenas with status (FREE / RESERVED / N players) |
| `/proxy arenas add <server>`      | Manually add a server to the arena pool               |
| `/proxy arenas remove <server>`   | Remove a server from the pool                         |
| `/proxy arenas refresh`           | Re-auto-detect arenas from velocity.toml              |
| `/proxy wins`                     | Top-10 leaderboard                                    |
| `/proxy wins <player>`            | Look up a player's wins                               |
| `/proxy wins set <player> <n>`    | Override a win count                                  |
| `/proxy wins add <player> <n>`    | Add wins to a player                                  |
| `/proxy move <player> <server>`   | Manually move any player to any server                |
| `/proxy test`                     | Dump all server / player / channel status             |

All `/proxy` subcommands have tab completion. `/hbl` is an alias for `/proxy`.

---

## Testing Without a Datapack

Drive the full flow manually as an operator:

```
# Lobby — send players straight to an arena
/arena send Player1 Player2

# Or use the queue
/arena queue add Player1
/arena queue add Player2
/arena queue list
/arena dispatch

# Arena — end the game
/lobby win Player1        ← records a win and returns everyone
/lobby send @a            ← returns everyone, no win recorded

# Check wins from anywhere
/wins Player1

# Velocity console — verify wiring
/proxy test
/proxy arenas
/proxy wins
```
