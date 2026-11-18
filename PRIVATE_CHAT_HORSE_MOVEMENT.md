# CasinoCraft update: private game messages + smoother horse races

- Race countdown, start and winner messages are sent only to UUIDs with a bet in that race. Other games already use direct player messages and private inventory result menus; shared roulette holograms remain visible in the world.
- The five horses now face along the tangent of their oval (head + body rotation) and travel an approximately constant distance along the ellipse each tick. This removes the old uneven oval-angle motion. The winning horse still follows the configured weighted winner probability.
- Horse movement remains server-controlled using vanilla entities; exact visual animation and entity synchronization require testing on the target Minecraft 26.3 server. Terrain must be level at the center Y+1, and the route chunks must be loaded. This is not terrain-following horse AI.
- Race status messages are private to bettors. Players standing by the track can still watch the horses.
- Build status: SOURCE ONLY; compilation and in-game testing not performed.

## Usage
Copy the entire contents of this ZIP into the GitHub repository root and run the existing GitHub Actions workflow. Replace the JAR only after the build succeeds. Back up your world before trying with real diamonds.
