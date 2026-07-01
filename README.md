# HighBL — Arena Routing System

> Two Java projects that bridge your Fabric game servers to the Velocity proxy.

> Looking for the full command list? See [COMMANDS.md](COMMANDS.md).

```
  Player Client
       │ Minecraft protocol
  Velocity Proxy          ← highbl-velocity plugin lives here
       │
  ┌────┴─────┐
Lobby     Arena-1, Arena-2, ...   ← highbl-fabric mod lives on ALL of these
```

---

## Requirements

| Component       | Version                      |
| --------------- | ---------------------------- |
| Minecraft       | **26.2**                     |
| Fabric Loader   | **0.19.3** or newer          |
| Fabric API      | **0.152.2+26.2** or newer    |
| Java            | **25** (JDK 25+)             |
| Velocity        | **3.3.0** or newer           |
| Gradle          | **9.5.1** (wrapper included) |

---

## Project Structure

| Directory          | Deploy the JAR to…                        |
| ------------------ | ----------------------------------------- |
| `highbl-fabric/`   | `mods/` on the **lobby** + every **arena** |
| `highbl-velocity/` | `plugins/` on the **Velocity proxy** only  |

---

## 1 · Build

**Windows**
```bat
cd highbl-fabric  &&  gradlew.bat build
:: → build/libs/highbl-fabric-1.1.0.jar

cd highbl-velocity  &&  gradlew.bat build
:: → build/libs/highbl-velocity-1.0.0.jar
```

> **Staying up to date:** check <https://fabricmc.net/develop/> for the latest
> `minecraft_version`, `loader_version`, and `fabric_api_version`, then update
> `highbl-fabric/gradle.properties` and rebuild.

---

## 2 · Deploy

| File                         | Destination                              |
| ---------------------------- | ---------------------------------------- |
| `highbl-fabric-1.1.0.jar`    | `mods/` on the **lobby** server          |
| `highbl-fabric-1.1.0.jar`    | `mods/` on **every arena** server        |
| `highbl-velocity-1.0.0.jar`  | `plugins/` on the **Velocity** proxy     |

Fabric API must also be present in `mods/` on every backend server.
No additional mods or plugins are required.

---

## 3 · Configure the Fabric Mod

On first start the mod creates `config/highbl.json`. Edit it per server:

**Lobby** — `config/highbl.json`
```json
{
  "serverType": "LOBBY",
  "serverId":   "Lobby"
}
```

**Arena** — `config/highbl.json`
```json
{
  "serverType": "ARENA",
  "serverId":   "arena-1"
}
```

Restart after editing. The mod logs its detected role on startup:
```
[HighBL] Server role: LOBBY
[HighBL] Registered LOBBY commands (/arena ...)
[HighBL] Ready.
```

---

## 4 · Velocity Server Names

The Velocity plugin auto-detects arenas by looking for servers whose name
contains **`arena`** (case-insensitive) in `velocity.toml`. Your lobby server
name must contain **`lobby`**.

Example `velocity.toml` servers block:
```toml
[servers]
  lobby   = "127.0.0.1:25568"
  arena-1 = "127.0.0.1:25567"
  arena-2 = "127.0.0.1:25566"
  arena-3 = "127.0.0.1:25565"
```

---

## 5 · Permissions (MC 26.x)

Minecraft 26.x uses named permissions instead of integer op levels. HighBL
uses `COMMANDS_GAMEMASTER` (**Gamemaster** role, equivalent to old op level 2).

Players opped with `/op <name>` default to **Owner**, which includes all
Gamemaster permissions.

---

## 6 · Command Reference

Full command tables and manual testing steps live in [COMMANDS.md](COMMANDS.md).

---

## 7 · Datapack Integration

### Lobby side — sending a group to an arena

```mcfunction
# data/highbl/functions/queue_players.mcfunction
# Call from a command block, button trigger, or advancement

execute as @a[tag=arena_ready] run arena queue add @s
tag @a[tag=arena_ready] remove arena_ready
arena dispatch
```

```
function highbl:queue_players
```

### Arena side — ending a game

**Someone won** (tag the winner with `arena_winner`):
```mcfunction
# data/highbl/functions/game_end_win.mcfunction
execute as @a[tag=arena_winner,limit=1] run lobby win @s
tag @a remove arena_winner
```

**No winner** (timer expired, everyone died, etc.):
```mcfunction
# data/highbl/functions/game_end_no_winner.mcfunction
lobby send @a
```

---

## 8 · Message Flow

```
[1]  Player presses button in lobby
       ↓  datapack: /arena queue add + /arena dispatch
[2]  Lobby mod   →  Velocity     SEND_GROUP | uuid1, uuid2, …
       ↓
[3]  Velocity picks the first free arena and reserves it
       ↓
[4]  Velocity teleports each player to the arena
       ↓
[5]  Velocity  →  Lobby mod      TRANSFER_OK | arena-1
[6]  Lobby mod notifies Gamemaster+ players: "Transfer OK"

     --- game runs ---

[7]  Datapack detects winner → calls: lobby win <player>
       ↓
[8]  Arena mod  →  Velocity      GAME_END | winnerUUID | allUUIDs
       ↓
[9]  Velocity records win in plugins/highbl/wins.json
[10] Velocity  →  Arena mod      WIN_CONFIRMED | uuid | totalWins | name
[11] Velocity teleports everyone back to lobby
       ↓
[12] Arena mod tells the winner: "You now have N win(s)!"
```

**Plugin-messaging channels**

| Channel            | Direction             |
| ------------------ | --------------------- |
| `highbl:toproxy`   | Backend → Velocity    |
| `highbl:toserver`  | Velocity → Backend    |


## 9 · Troubleshooting

| Symptom                           | Fix                                                                                              |
| --------------------------------- | ------------------------------------------------------------------------------------------------ |
| `/arena ping` gets no PONG        | Verify channel IDs are exactly `highbl:toproxy` / `highbl:toserver`; check the Velocity plugin loaded cleanly |
| "No free arena available"         | Run `/proxy arenas` — arenas must show **FREE** with 0 players                                  |
| Players not transferred           | Confirm Fabric API is in `mods/` on **both** lobby and arena servers                             |
| Commands not showing              | Check `config/highbl.json` — `serverType` must be `LOBBY` or `ARENA` (case-sensitive)           |
| HighBL commands not restricted    | Uses `COMMANDS_GAMEMASTER`; only Gamemaster-level or higher players can run them                 |
| Win data lost on restart          | Ensure Velocity has write access to `plugins/highbl/wins.json`                                   |
| Velocity plugin not loading       | Requires Velocity 3.3+; confirm JAR is in `plugins/` and `velocity-plugin.json` was generated   |

---

## 10 · Win Data Storage

Wins are saved to `<velocity-root>/plugins/highbl/wins.json` after every change.

```json
{
  "wins":  { "<uuid>": 5,            "…": 0   },
  "names": { "<uuid>": "PlayerName", "…": "…" }
}
```

Back this file up regularly if you want persistent leaderboards across proxy restarts.
