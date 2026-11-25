# CasinoCraft 0.1.2-alpha — separate configs and custom language

The server creates these files under `config/casinocraft/` at startup:

| File | Settings |
|---|---|
| `general.json` | Shared stake limits, result window duration, enabled games and language |
| `roulette.json` | Betting/spin durations, odds, payouts, limits, colors and hologram templates |
| `slots.json` | Symbol weights, pair/triple payouts and animation timing |
| `blackjack.json` | Win/tie payouts and dealer stand threshold |
| `higherlower.json` | Minimum correct guesses to cash out, progression and maximum payout |
| `horse.json` | Race/betting time, optional odds, payouts and armor colors |
| `lang/nl_nl.json` | Editable Dutch display text dictionary |

Existing `games.json` values seed missing split files, including horse overrides. Existing split files take precedence. Keep the old file as a backup until the migration is verified; afterwards it may be moved out of the config directory. World data (registered tables, race geometry, balances) remains unchanged. Horse override arrays may be null to preserve the saved world values and admin commands. Shared stake limits remain in general.json.

## Language

The default is `"language": "nl_nl"` in general.json. You can edit `lang/nl_nl.json` directly or copy it to `lang/en_us.json` (or any custom name using letters, digits, underscores or hyphens) and select `"language": "en_us"`. A missing selected file is generated as an editable Dutch template, not an automatic translation. No complete English translation is bundled.

The dictionary maps original display strings or fragments (left) to replacement text (right). Keep keys intact, preserve leading/trailing spaces where needed, and keep roulette placeholders `{seconds}`, `{number}`, `{color}`, `{bets}` as applicable. Dynamic numbers are supplied by the game. Change related entries together, e.g. uppercase and lowercase color names and multiple fragments for the same word. Longer matching fragments take precedence; text is replaced once at rendering, not recursively. Example entries:

```json
{
  "WIEL DRAAIT...": "WHEEL SPINNING...",
  "Wiel draait...": "Wheel spinning...",
  "WINNAAR: {number} {color}": "WINNER: {number} {color}",
  "ROOD": "RED",
  "ZWART": "BLACK",
  "GROEN": "GREEN"
}
```

This is an editable server-wide text dictionary, not Minecraft's per-client language system. It covers chat, menu labels, result screens and roulette holograms via rendering-time substitutions. Commands and internal status identifiers stay unchanged; console diagnostics are not localized. Roulette hologram templates may also be edited directly in roulette.json; if customized there, use their new text as dictionary keys if translation is desired. Some dynamic sentences are split into multiple dictionary fragments rather than complete reorderable templates.

Use `/casinocraft reload` when no active game or bets remain, or restart the server. Invalid configs are logged. No visible spin countdown is added. Existing odds and payouts are unchanged.

## Verification

Source syntax and archive/config consistency checked; full Fabric build and live test still required (Java 25 / Gradle not available here). Use GitHub Actions and a backup test server.
