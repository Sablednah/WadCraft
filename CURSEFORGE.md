# WadCraft

![WadCraft](https://raw.githubusercontent.com/Sablednah/WadCraft/main/src/main/resources/wadcraft.png)

**Walk into E1M1.** WadCraft reads Doom WAD files and builds their maps as
Minecraft structures, in seconds, with one command.

Drop a WAD on your server, type `/wadcraft build freedoom1.wad E1M1`, and you
are standing on the player start, facing the way Doom faces you, with the level
built around you: rooms, corridors, stairs, windows, outdoor yards, the lot.

**Server-side only.** WadCraft places ordinary blocks, so players with an
unmodded client see everything it builds. Nothing to install on the client.

![A whole Doom level, built in Minecraft](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/map-from-above.jpg)

---

## What you get

- **The level's real shape.** Every floor and ceiling height, sealed walls,
  rooms open to the sky where Doom has sky.
- **Colours from the WAD itself.** Every wall texture and floor is matched to the
  Minecraft block nearest its average colour, read straight from the WAD's own
  palette and graphics. Any WAD, even one nobody has seen before, builds in the
  right colours without a single table being written.
- **Stairs you can walk up.** Floors are worked out to the half block: small
  steps become slabs, and a slab beside a higher floor becomes a stair facing
  up it.
- **Toxic floors are lava.** Wherever Doom hurts you for standing (nukage,
  slime), you get lava, in pools that cannot spill. Where lava could run out,
  it becomes magma.
- **Doors are open, lifts have ladders**, so the whole level can be walked.
- **The level's own lighting**, using invisible light blocks at each area's
  brightness.
- **Every opening a Doom player can walk through is walkable.** Each build is
  checked against that rule across hundreds of maps, so you will not get stuck
  under a lintel that was fine in Doom.
- **Nothing in a build can burn**, so a lava pool next to a wooden floor will
  not take the level down with it.
- **Big maps do not freeze the server.** Builds are placed a slice per tick, with
  progress shown as they go.
- **Undo.** `/wadcraft undo` puts back whatever your last build replaced.

---

![A lava hall](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/lava-hall.jpg)

![A lit corridor](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/lit-corridor.jpg)

![A cave with lava](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/lava-cave.jpg)

![A pillared room](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/pillared-room.jpg)

![A star-shaped courtyard, from above](https://raw.githubusercontent.com/Sablednah/WadCraft/main/docs/images/star-map-from-above.jpg)

## Commands

All commands need operator permission.

| Command | What it does |
|---|---|
| `/wadcraft list` | the WAD files in the server's `wads` folder |
| `/wadcraft maps <wad>` | the maps in one WAD; click a map to build it |
| `/wadcraft info <wad> <map>` | how big a map will be, before you build it |
| `/wadcraft build <wad> <map>` | build it where you stand |
| `/wadcraft build <wad> <map> look` | build it on the block you are looking at |
| `/wadcraft undo` | put back what your last build replaced |
| `/wadcraft cancel` | stop builds in progress |

Add a number at the end of `build` to change the scale: it is Doom units per
block, 32 by default (a Doom player is about the height of a Minecraft one), and
16 builds everything twice the size.

The map turns to face the way you are facing, to the nearest quarter turn.

---

## WAD files

**WadCraft contains no Doom data and ships none.** You bring the WADs.

- **Freedoom** (freedoom.github.io) is free to download, use and share. It
  is the easiest place to start: two full games of maps.
- The original Doom WADs work too, including the shareware episode, Doom II,
  Plutonia and TNT. They are not yours to give away, so keep them on your own
  server.
- Custom maps (PWADs) work. A custom map with no textures of its own borrows its
  colours from a main game WAD in the same folder.
- Doom and Hexen format maps are supported. UDMF (text format) maps are not yet.

Put the files in the `wads` folder in your server's main folder (it is created
the first time the server starts). In single player, that is your game instance's
folder.

---

## Customising

`config/wadcraft/blocks.json` lets you choose the block for any texture or flat
by name, with `*` as a wildcard. Want a wood floor after all, or glass where the
computer panels are? One line each. You can also change the block used for toxic
floors.

---

## For mod and pack developers

WadCraft is a library as much as a mod. Everything is in one class,
`WadCraftApi`: read a WAD from a file or from bytes you carry yourself, get the
block layout without placing anything, or build a map at any position and
rotation and be told when it finishes. The source and an example are on GitHub.

---

## Not yet

- Monsters, items and keys
- Doors and lifts that move (doors are built open, lifts get ladders)
- UDMF maps

---

Requires NeoForge for Minecraft 1.21.11. MIT licensed.
Source: https://github.com/Sablednah/WadCraft
