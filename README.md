# CasinoCraft — Minecraft 26.3 / Fabric (alpha source project)

**Status: uncompiled development prototype.** This is a Java source project, NOT a production-ready or verified mod jar. It has not been built or tested against Minecraft 26.3 in the current environment. Test only on a backup world, NOT on your live SMP economy.

## Gameplay

- Server-side entrypoint. Vanilla Minecraft players need no client mod.
- Admin looks at **any existing vanilla block**, including a custom-textured player head, and registers it as a game with `/casino block set <game>`.
- A player **right-clicks that exact block** to open its game controls. They then use vanilla **clickable chat buttons**; there is no `/casino` player lobby. The hidden `/casinoplay` command only works if they have recently interacted with a registered block and remain within 8 blocks.
- Games: slots, roulette, blackjack (simplified), higher/lower (simplified), horse racing.
- Diamond wagers are removed from inventory; wins are created by the server.
- Horse racing: 5 concentric ellipses generated around an admin-chosen center, 5 horses, weighted winner, 60-second enrollment countdown. Horse entities are moved server-side. The course currently uses a simple elliptical approximation, not editable waypoints.
- Winning horse probability and payout multipliers are admin configurable.
- The configuration is saved in `<world>/casinocraft.json`.

## Requirements / project build

- Minecraft Java 26.3, Fabric Loader 0.20.1, Fabric API 0.166.0+26.3.
- JDK 25.
- Gradle 9.7.1 (or compatible version for Fabric Loom 1.19-SNAPSHOT).
- An internet connection is needed for Gradle to fetch Minecraft/Fabric dependencies.

There is **no Gradle wrapper executable/binary** in this zip (this environment cannot fetch the official wrapper). Install Gradle locally and in the project folder run:

```bash
gradle build
```

If successful, the resulting `.jar` will appear in `build/libs/`. Install the built jar and Fabric API in the server's `mods/` folder. If compilation errors appear, resolve the changed Minecraft 26.3 APIs before deploying.

## Admin setup

1. Create a test world or make a backup of the existing world.
2. Build a horse track around the intended center. Current generated lane radii are x=16/19/22/25/28 and z=9/12/15/18/21. Ensure 3-wide clear corridors and at least 3 blocks of headroom.
3. Stand at the exact center and run `/casino horse center`.
4. Place the player head or other vanilla block you want to act as the betting station.
5. Point at it (within 5 blocks), then run `/casino block set horse`.
6. Optional other stations: `slots`, `blackjack`, `roulette`, `higherlower`.
7. Right-click the station, select a horse, select an amount, then click "Bevestig inzet".
8. On the first bet a 60-second countdown begins.
9. Test with low stakes and verify payouts, restarts and menu behavior before allowing real players.

Commands requiring permission level 2:

```
/casino block set horse
/casino block set blackjack
/casino block set roulette
/casino block set slots
/casino block set higherlower
/casino block remove
/casino horse center
/casino horse chance 1 40
/casino horse payout 1 2
/casino horse start
```

Admin can change horse chances one-by-one. A race requires their total to be exactly 100%.

## Current limitations and must-fix items before production

- **Not yet compiled or gameplay-tested.** Minecraft and Fabric APIs can change; a successful build is not guaranteed.
- Vanilla chat buttons rather than a custom-looking inventory GUI (server-only design).
- Blackjack is a simplified implementation (no splitting, doubling, soft ace handling or natural-blackjack bonus). Higher/lower is also simplified.
- Roulette uses European single-zero (0–36), with authentic red/black number colors.
- Roulette bets: red/black, even/odd, 1–18/19–36 (2x total payout); 1–12/13–24/25–36 and the three 12-number columns (3x); and individual 0–36 numbers (36x).
- Roulette uses clickable chat options and number submenus. Clicking a betting option ADDS a chip to the current betting slip; it does not spin. Use the `DRAAI ROULETTE` chat control to debit the combined stake and spin once for every selected option. Use the remove-last and clear-all controls to edit the slip. Zero loses on all outside bets. All multipliers INCLUDE the original stake; all outcomes are server-generated.
- Multiple roulette bets are supported per spin, including duplicate betting fields. The total is capped at 256 diamonds and 64 individual chips per spin. Separate players currently spin independently, not as one shared table round.
- The horse route uses concentric mathematical ellipses; it is not yet waypoint-based and horse animation/teleport synchronization needs validation in-game.
- Horse entry during an active race is blocked, but spectator controls and rewards notifications are rudimentary.
- **Crash safety is incomplete.** Pending bets, inventory changes and pending rewards are not persisted in an atomic transaction. A crash/restart at certain moments might cause loss or duplication. Do not use this build for public diamond betting until durable transaction logging and restart simulation tests are implemented.
- Unpaid rewards should be stress-tested with full inventories and relogging. A dedicated escrow/mailbox is recommended.
- Registered blocks should be unregistered automatically when they are broken/replaced; this prototype does not yet do so.
- Head custom textures must be supplied via vanilla Minecraft heads; this mod does not create new textures or blocks.
- Several payouts imply positive or negative expected diamond creation; choose probabilities and multipliers responsibly for your server economy.

## Package structure

```
src/main/java/be/wevelgem/casinocraft/CasinoCraft.java
src/main/resources/fabric.mod.json
build.gradle
gradle.properties
settings.gradle
```

The prototype is intentionally a small monolithic baseline for further refactoring and testing.


## Gekleurd lederen paardenpantser (Horse Racing)

Elk van de vijf paarden verschijnt met `minecraft:leather_horse_armor` in een eigen RGB-kleur. De kleur wordt **ook in het aanklikbare paardkeuzemenu** weergegeven: de tekst voor het paard krijgt dezelfde kleur en vermeldt de exacte hexcode. De naam boven het racepaard krijgt eveneens die kleur.

Standaardkleuren:

| Paard | Kleur | RGB |
|---|---|---|
| 1 Bliksem | Geel | `FFD700` |
| 2 Storm | Blauw | `3098F0` |
| 3 Turbo | Rood | `EB3434` |
| 4 Schaduw | Paars | `9A50E0` |
| 5 Rocket | Groen | `30BB60` |

Wijzig een kleur als admin via `/casino horse color <1-5> <RRGGBB>`, bijvoorbeeld `/casino horse color 1 FF6600` voor oranje pantser op paard 1. Gebruik zes hextekens zonder hekje. De RGB-waarden worden automatisch opgeslagen als `horseColors` in `casinocraft.json` en blijven na een serverherstart behouden. Kleuren zijn decoratief en veranderen de kansen of uitbetalingen niet. Wijzigingen gelden voor nieuwe races.

**Buildstatus:** Nog niet gecompileerd/getest op Minecraft 26.3. Er kunnen incompatibiliteiten in de Fabric/Minecraft-API bestaan; compileer en test eerst op een aparte server. De betalingen en crashbestendigheid zijn nog alfa.


## Compile fixes (2026-11-18)
- Minecraft 26.3 `Horse` import moved to `net.minecraft.world.entity.animal.equine.Horse`.
- Horse entity type is now defined in `EntityTypes.HORSE`.
- Commands permission check uses `src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)`.
- CI installs Gradle 9.8.0 for Loom 1.19.4.

These source-level fixes have **not yet been compiled or tested**. Run the GitHub Actions workflow and inspect any remaining errors.


## Adjustable oval track + roulette hologram update

The roulette TextDisplay is now a two-line, colored label with a dark background and `CENTER` billboard constraints. Each player sees it facing their camera (vanilla client-side rendering, no additional mod). It shows the 10-second countdown, spins through wheel numbers during the last five seconds, and shows the final winning number/color.

The horse track is still a **smooth oval** (not a straight racetrack). Its configurable values describe the centerline of the **outermost** lane, not the outer fence. Example administrator commands:

```text
/casino horse center
/casino horse radiusx 40
/casino horse radiusz 24
/casino horse lanewidth 3
/casino horse info
```

These values make an outermost centerline approximately 81 × 49 blocks, with five nested lanes 3 blocks apart. The outer fence must be farther out to give horses room. The width must satisfy both outer radii >= 4×laneWidth+3. To accommodate a larger lane width than the current values allow, first increase both radii; to make the track smaller, lower lane width first. Commands are restricted while a horse betting round or race is in progress. Values persist in `casinocraft.json` and default to 28/21/3 to preserve the previous layout.

**Development status:** source patch only. No verified Minecraft 26.3 compilation or in-game test. Back up the world before using. Known caveat: the race moves horses with `setPos`, and entity hologram cleanup across server restarts still needs testing.

## Roulette hologram/timer config (new)

The roulette has a configurable 30-second betting period, followed by a separate random 15–30-second spinning period. The winning number is settled after spinning ends. The hologram disappears automatically 10 seconds after the result. The values and text can be configured on the server in `config/casinocraft/roulette.json`. See `ROULETTE_CONFIG_UPDATE.md` for the full reference.
