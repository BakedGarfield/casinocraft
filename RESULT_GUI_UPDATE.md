# Result GUI update

- Roulette already uses true red/black/green color classifications, with a zero in green; betting icons reflect these colors and the floating `TextDisplay` uses matching colored text.
- Blackjack now shows a result screen after bust, stand, loss, win, or draw; it explains the exact score or dealer outcome.
- Higher/lower now shows why a guess was wrong, including whether the next card was higher, lower or equal. Cashing out also shows a result screen.
- Slots now shows its final three symbols and why it did or did not pay out.
- Multiplayer roulette now shows each participating player their own result (winning number/color, payout and total wager) while the floating display shows the shared result.
- Result screens remain for about five seconds and close automatically, or can be dismissed by clicking the barrier item. They do not permit further bets while open.

This is source code only, not a compiled/tested JAR. Run the included GitHub Actions build and test on a backup server before using survival diamonds. The result message is transient UI state; pending payouts are still handled by the existing persistence code, which requires an end-to-end crash/restart audit before production use.
