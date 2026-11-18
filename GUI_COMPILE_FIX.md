# Minecraft 26.3 GUI item compile fix

The previous build failed because old `Items.*` constants for colored blocks (and two dyes) were not found.

This patch uses Minecraft 26.3 colored block collections (e.g. `Blocks.WOOL.red().asItem()` and `Blocks.CONCRETE.lime().asItem()`). It replaces green and blue dye icons with `Items.SLIME_BALL` and `Items.LAPIS_LAZULI`. The red glass pane icon is replaced with red wool. Gameplay logic is unchanged.

Upload files to GitHub and run the included workflow. This has NOT been compiled or tested locally; check GitHub Actions for any further errors.
