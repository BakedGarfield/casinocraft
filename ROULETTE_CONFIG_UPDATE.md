# CasinoCraft 26.3 — roulette timers, rotating wheel display and automatic hologram cleanup

This update is based on the latest `CasinoCraft-26.3-slots-results-stay-visible.zip` source version. All five games and the persistent slots outcomes are retained.

## Default roulette sequence

1. The first confirmed diamond bet at a registered roulette block starts a 30-second betting window.
2. Players using *the same table* can continue placing bets during this window. The on-screen chest GUI shows the remaining seconds.
3. When bets close, the roulette enters a separate spinning phase with a random duration between 15 and 30 seconds (inclusive).
4. The floating text changes during spinning and cycles through adjacent entries in the true European wheel order.
5. At the end of the spin, **one randomly selected number from 0–36** settles **all bets at this table**, and winnings are credited.
6. The result remains visible for 10 seconds by default, after which the TextDisplay is discarded. The next betting round can begin immediately after settlement.

## Server-side configuration

On first startup, a file is written to `config/casinocraft/roulette.json` on the Fabric server. Change this file while the server is stopped, then start the server again (the file is loaded at startup). Changing the configuration does not require recompiling the mod.

```json
{
  "bettingSeconds": 30,
  "minimumSpinSeconds": 15,
  "maximumSpinSeconds": 30,
  "resultDisplaySeconds": 10,
  "title": "✦ ROULETTE ✦",
  "bettingText": "INZETTEN SLUITEN OVER {seconds}s",
  "spinningText": "WIEL DRAAIT... {seconds}s",
  "resultText": "WINNAAR: {number} {color}",
  "betsText": "{bets} inzetten geplaatst"
}
```

Supported placeholders: `{seconds}` (current phase), `{bets}` (total chips on the table), `{number}` (winning number), `{color}` (ROOD/ZWART/GROEN). The `title` and other values can be changed to your preferred Dutch text. Avoid very long lines so the hologram stays readable.

Limits: betting 5–600 seconds; spin 1–300 seconds, max >= min; result visibility 0–600 seconds. Invalid config files fall back to defaults, with a console error.

## Old holograms

The mod tags all new roulette holograms. Before creating a new hologram at a registered table, it removes nearby leftover TextDisplay entities whose visible text says `ROULETTE`, as well as any tagged CasinoCraft roulette display. An initial cleanup is attempted on already loaded registered tables shortly after server start. Other loaded areas are not searched globally. If an orphan hologram is far from any currently registered roulette block, remove it manually with a narrow `/kill` selector.

## Notes and limitations

- The roulette spin is a textual number animation, not a 3D roulette wheel.
- The actual winning number is random and independent of the number shown during animation.
- The last result does not remain visible forever: it is removed after the configured time.
- The project is source code and **has not been compiled or Minecraft-tested** in this environment. Build via GitHub Actions (Java 25 / Gradle 9.8) and test with low-value bets on a backup server.
