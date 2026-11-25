# CasinoCraft 0.1.1-alpha — balance and configuration

Minecraft 26.3 / Fabric. Diamond-only game economy. Existing registered blocks and saved world data remain in place.

## Install
Replace the repository files with this source package and run the existing GitHub Actions workflow. Download the generated JAR and replace the old JAR while the server is stopped. Back up the world and config first. The server creates missing configuration files in `config/casinocraft/`. Existing roulette configuration is read and extended with new default fields. Example configs are included here. Do not replace the world save `casinocraft.json`.

## Roulette
The betting phase defaults to 30 seconds; spinning is 15–30 seconds. Monotonic elapsed time is checked each server tick; a fully stalled server can only settle when it resumes. Floating numbers are colored and the result label is white, with colored number/color tokens. Black uses dark gray for visibility. Results remain for 10 server seconds by default. The wheel slows visually near the end. Only the betting phase shows a countdown; the spinning phase shows a status and colored numbers without remaining time, including in the GUI. All bets share one independent outcome. Legacy chat bets now go through the shared table, not a separate instant spin. Both GUI pages expose every number.

Default `zeroWeight=2` models two green pockets combined into one displayed zero (a custom variant, not literal European roulette). Each nonzero number is 1/38, zero is 2/38, and a red/black, odd/even or low/high bet is 18/38. Zero pays 18x, other numbers 36x, outside bets 2x, columns/dozens 3x. Each has a 5.26% house edge with defaults. For standard European roulette use zeroWeight=1 and zeroPayout=36. All payouts include original stake. The roulette menu displays the current number odds and payouts. Colors are decimal RGB integers. The config has a hard maximum spin duration of 30 seconds.

## Slots
Array order: Diamond, Emerald, Gold, Redstone, Amethyst, Nether Star. Each reel independently samples the configured weights. With equal weights and the default payout arrays, 120/216 outcomes lose, 60/216 return stake, and 36/216 give net profit. Exact expected return is 182/216 = 84.26% (previously 272/216 = 125.93%). A pair of diamonds or stars pays 2x; all other pairs pay 1x. Triples retain original payouts. x1 is labeled as stake returned, not a win. Changing weights or multipliers changes return.

## Config and reload
`roulette.json`: odds, payouts, limits, colors, timings and hologram texts.
`general.json`, `slots.json`, `blackjack.json`, `higherlower.json` and `horse.json` replace `games.json`; see [CONFIG_LANGUAGE.md](CONFIG_LANGUAGE.md).

`/casino reload` or `/casinocraft reload` (moderator permission) reloads while no wagers or card rounds are active. Invalid configuration is logged; failed reload returns command failure. Existing game rules such as higher/lower minimum two correct remain by default.

## Validation
Source modifications and exact enumeration of all slot outcomes checked. A full Fabric build and live server test were not available in the editing environment (Java 21, no Gradle; project needs Java 25). Run the supplied GitHub Actions workflow before deployment.
