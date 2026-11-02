# Aura Points

An installer that sets up a ready-to-join instance of our server's modpack in your launcher, and a small updater
that keeps everyone's Aura Points current. The mod itself (source, releases, `latest.json`) lives in
[ToastbutBuried/aura-points](https://github.com/ToastbutBuried/aura-points).

**How do I get Aura?** See the [full quest list and other Aura sources](https://github.com/ToastbutBuried/aura-points/blob/main/QUESTS.md).

## Join the server: one-command installer

**Windows** - download [`install-windows.bat`](https://github.com/ToastbutBuried/aura-points-installer/raw/main/install-windows.bat)
and double-click it, or paste this into PowerShell:

```powershell
irm https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install.ps1 | iex
```

**macOS / Linux** - paste this into a terminal:

```bash
curl -fsSL https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install.sh | bash
```

It asks two things:

1. **A name** for the new instance (default `Aura Server`).
2. **Which mods**:
   - **Needed** - only what you need to join (35 mods).
   - **Server mods** - everything the server runs that also works on your side (46 mods): the needed ones plus
     Xaero's Minimap + World Map, JEI, Distant Horizons, Do a Barrel Roll, Terralith and the performance mods
     (Lithium, FerriteCore, ModernFix, Clumps).

Then it finds your launcher and makes a **new** instance - your existing ones are never touched:

| Launcher | What you get |
|---|---|
| **Prism Launcher** / **MultiMC** | a finished instance (Minecraft 1.21.1 + NeoForge 21.1.251), Aura Points auto-update already switched on. It shows up in the launcher right away. |
| **Modrinth App** / **ATLauncher** | a `.mrpack` modpack file in your Downloads that the launcher imports |
| **Minecraft Launcher** (official) | NeoForge installed, a new installation with its own game folder (`.minecraft/aura-instances/<name>`). Close the launcher before running the installer. |
| anything else | a folder with all the mods, to drop into an instance yourself |

Several launchers on one computer? It lists them and you pick one. CurseForge isn't supported - use Prism
(https://prismlauncher.org), it's the easiest.

**What it downloads, and from where:** no mod files are stored in this repo. [`modpack.json`](modpack.json) lists
every mod with its official download link (Modrinth's CDN, FTB's own Maven for the FTB mods, the releases of
[aura-points](https://github.com/ToastbutBuried/aura-points/releases) for Aura Points) and its checksum; the installer checks every file against it. It needs **Java 17+** to run - it
uses the one that comes with Prism, the Minecraft Launcher or the Modrinth App if you have no Java of your own,
and if there is none at all it downloads a temporary one (Eclipse Temurin 21) that's deleted afterwards. The
installer's source is in [`installer/AuraInstaller.java`](installer/AuraInstaller.java).

## Auto-update setup (Prism Launcher)

The installer does this for you. For an instance you made yourself:

1. Download [`aura-updater.jar`](https://github.com/ToastbutBuried/aura-points-installer/raw/main/aura-updater.jar).
2. In Prism, right-click the instance → **Folder**, and put `aura-updater.jar` in that folder
   (the instance folder itself, next to `instance.cfg` - not in `minecraft/` or `mods/`).
3. Right-click the instance → **Edit** → **Settings** → **Custom commands**, tick the box, and paste
   this into **Pre-launch command**:

   ```
   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
   ```

That's it. On every launch it checks this repo; if there is a newer Aura Points it downloads it,
verifies it, and replaces the old jar before the game starts. Its messages show up at the top of
Prism's log as `[aura-updater]`.

It can't get in the way of playing: without internet, if GitHub is down, or if anything takes longer
than 45 seconds, it just starts the game with the jar you already have. Old copies of the mod are
found by their mod id, so a renamed one (like `Aura Points (1).jar`) is cleaned up too.

**If you ever delete `aura-updater.jar`, also clear the Pre-launch command** - otherwise Prism can't
run it and won't start the game.

## Keys

Options → Controls → **Aura Points**: **Ability 1 / 2 / 3** (default Left Alt, X, \`) work for every aura -
each aura puts its abilities on them (`/aura info` shows which is where). **Ultimate** (default V) fires an awakened
aura's Ultimate. **Item Use**, **Item Secondary** and **Item Tertiary** are for datapack items (see below). Double Jump uses jump.
If a key you need is unbound or clashes with another key, the mod tells you in chat after joining
(turn it off: Mods → Aura Points → Config → Feedback → Keybind hint).

## For datapack makers

Everything is plain vanilla - no mod knowledge needed:

| What | How |
|---|---|
| A player's aura | scoreboard `aura_type`: 0 none, 1 Shadow, 2 Water, 3 Fire, 4 Wind, 5 Frost, 6 Verdant, 7 Thunder, 8 Resonance, 9 Earth, 10 Light, 11 Blood, 12 Void |
| Aura Mastery | scoreboards `aura_mastery_<aura>` (e.g. `aura_mastery_fire`): the player's mastery level 0-5 for every aura; `aura_mastery`: the level of the aura they hold now. Read-only (the mod rewrites them; change levels with `/aura mastery set`) |
| Item keys | scoreboards `itemuse`, `itemsecondary` and `itemtertiary`: 1 while the player holds the key, 0 otherwise |
| Ability cooldowns | scoreboards `aura_cd_<ability>` (e.g. `aura_cd_fireball`): ticks left, 0 = ready. Writable: `/scoreboard players set @s aura_cd_fireball 100` = 5s cooldown, `0` = ready right away |
| Left click (M1) | advancement `aurapoints:toastbutburiedm1` is granted (and taken back) on every left click at air, a block or an entity; its reward runs function `pluss:generalm1` as the player |
| Accessory slots | slot names `accessory.0` .. `accessory.11` and `accessory.*` work in `/item` and `execute if items`, e.g. `execute if items entity @s accessory.* minecraft:totem_of_undying`. How many are on: `ACCESSORY_SLOTS` in `aurapoints-general.json` (default 2, max 12); `ACCESSORY_SINGLE_ITEM` (default true) = one item per slot, false = whole stacks |
| Aura currency | scoreboard `aura_points` (add to it to pay players) |
| An ability was used | function tag `#aurapoints:ability/<ability>` runs as and at the player, e.g. `data/aurapoints/tags/function/ability/fireball.json` |
| Aura changed | function tag `#aurapoints:aura_changed` runs as and at the player |

Ability names for the tags and `aura_cd_` scoreboards: `shockwave`, `healing_tide`, `abyssal_bubble`, `vanish`, `dash`, `super_wind_charge`,
`double_jump`, `fireball`, `heat_wave`, `fire_bending`, `ice_spike`, `vine_hook`, `entanglement`, `verdant_viper`,
`thunderbolt`, `chidori`.

Example `data/aurapoints/tags/function/ability/fireball.json`:
```json
{ "values": ["mypack:on_fireball"] }
```

## What Aura Points itself needs

To run the mod at all (any world or server):

| | Version |
|---|---|
| Minecraft | **1.21.1** |
| NeoForge | **21.1.251** or newer 21.1.x |
| Java | **21** (Prism picks it automatically) |
| [Player Animator](https://modrinth.com/mod/playeranimator) | **2.0.4+1.21.1** or newer - client only, for the ability animations |

For `/aura redeem` and the skill trees it also needs
[Pufferfish's Skills](https://modrinth.com/mod/skills) and [Pufferfish's Attributes](https://modrinth.com/mod/attributes)
on the server (and so on every player too - see below). Everything else (abilities, shop, HUD, quests' Aura rewards) works without them.

## What you need to join our server

The installer above sets all of this up. By hand: NeoForge **21.1.251** for Minecraft **1.21.1**, and these mods. Use **exactly** these versions where it
says so - a different version of a mod that adds blocks, items or ships can stop you from joining or crash
the game.

**Required (exact versions):**

| Mod | Version |
|---|---|
| Aura Points | the newest [release](https://github.com/ToastbutBuried/aura-points/releases) (the updater keeps it current) |
| Player Animator | 2.0.4+1.21.1 |
| Create | 6.0.10 |
| Create Aeronautics (bundled) | 1.3.2 |
| Sable | 2.0.6 |
| Create Big Cannons | 5.11.7 |
| Ritchie's Projectile Library | 2.1.2 |
| **Forgified Fabric API** | **0.116.15+2.3.5** - *not* 2.3.6, that one crashes on NeoForge 21.1.251 |
| FTB Library | 2101.1.37 |
| FTB Teams | 2101.1.11 |
| FTB Quests | 2101.1.36 |
| Pufferfish's Skills | 0.19.1 |
| Pufferfish's Attributes | 0.8.3 |
| Sophisticated Core | 1.5.7.2381 |
| Sophisticated Backpacks | 3.26.9.2195 |
| Sophisticated Backpacks Create Integration | 0.2.2.188 |
| Sophisticated Storage | 1.6.2.2159 |
| Sophisticated Storage Create Integration | 0.1.22.238 |
| Gravestone | 1.0.40 |
| Horseshoes | 1.3.3 |
| Vanilla Backport | 1.1.7.10 |
| Call Your Horse | 1.3.11 |
| Two Players One Horse | 1.0.2 |
| Veinminer | 2.11.2 |
| FallingTree | 1.21.1.11 |
| Right Click Harvest | 4.6.1 |
| Libraries the above need: Architectury 13.0.11, Balm 21.0.66, Cloth Config 15.0.140, Konkrete 1.9.9, Platform 1.3.3, JamLib 1.3.6, YetAnotherConfigLib 3.8.2, KotlinLangForge 2.15.0, Kotlin for Forge 5.12.0 | |

**Recommended (any version for 1.21.1 works):**

| Mod | Why |
|---|---|
| Do a Barrel Roll 3.7.3 | elytra flying like everyone else on the server (needs the Forgified Fabric API above) |
| Xaero's Minimap + Xaero's World Map 1.47.0 | the map (the server has the world map part) |
| Just Enough Items (JEI) | recipes |
| Distant Horizons 3.3.3 | far view distance, the server sends the far terrain |
| Sodium, Iris, ... | performance and shaders - entirely up to you |

**Not needed on your side** (server only): NeoEssentials, Root Companion, InvHistory, ServerCore, spark,
squaremap, SkinRestorer, Biome Replacer, Terralith, Lithostitched, Lithium, FerriteCore, ModernFix, Clumps.

**Easiest:** use the installer at the top.
