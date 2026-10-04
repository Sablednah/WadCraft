# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**WadCraft**: reads Doom WAD files and builds their maps as Minecraft
structures. A standalone library mod, usable on its own (command) and by other
mods (`api/WadCraftApi`). Sable's sci-fi pack and the planned "Space Husk" mod
are its first customers (see LegendQuest's `docs/IDEAS.md`).

**Server-side only, vanilla first.** It places ordinary blocks; an unmodded
client sees everything. `displayTest="IGNORE_ALL_VERSION"` in the mods.toml
template.

## NEVER commit or ship a WAD

`Wads-DONOTSHIP/` holds commercial id Software data (DOOM.WAD, DOOM2.WAD,
Plutonia, TNT, Hexen). It must never be committed, attached to a release or put
in a jar. `.gitignore` ignores every `*.wad` in any case and that folder by
name; keep it that way. Freedoom (BSD) could be shipped, but at ~28MB each it
does not belong in git either. Before any commit:
`git status --short | grep -iE "\.wad|DONOTSHIP"` must print nothing.

`Doom3.WAD` in that folder is a **damaged** copy (its directory's last 94
entries are garbage). It is refused with a clear message, and that is correct.

## Build

There is no system Java:

```bash
export JAVA_HOME=/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew build    # tests included
```

Tests (`WadFilesTest`) read every WAD on disk in the project folder and
`Wads-DONOTSHIP/`, build every binary map (267 at the time of writing), and
write top-down renders to `build/test-renders/*.png`. Look at a render after
changing the builder: a wrong sector lookup shows at once as speckle or a
flooded void. Missing WADs skip, never fail.

## Layout

| Package | What | Minecraft imports? |
|---|---|---|
| `wad` | WAD directory, map lumps (Doom + Hexen binary), texture colours | **no** |
| `build` | `Voxelizer`: map to block columns of material runs | **no** |
| `neoforge` | block palette, placement jobs, queue, commands, wads folder | yes |
| `api` | `WadCraftApi`, `BuildResult` | yes |

Keep `wad` and `build` free of Minecraft: that is what lets the tests check
real WADs without a game, and what keeps a version port to the `neoforge` side.

## Decisions worth not relitigating

- **Sector lookup by ray cast, not the BSP.** A ray east from each column's
  centre; the first linedef crossed, and the side facing the point, name the
  sector. Needs only linedefs, so node format does not matter. The ray is
  nudged off the grid (+0.37, +0.21) so it never passes exactly through an
  integer vertex.
- **Walls go in the void columns** just outside the level (8-neighbourhood),
  and steps/lowered ceilings become extra solid on the short side
  (4-neighbourhood). Watertight without tracing walls.
- **Doom-passable stays passable:** a sector at least 56 units high always gets
  at least 2 blocks of air after rounding.
- **Doors are built open** (ceiling to lowest neighbouring ceiling - 4, as the
  engine opens them). Door specials: Doom local/tagged lists and Hexen 11/12/13
  in `Voxelizer`.
- **Light blocks go in the top air block of every room 2+ high**, every 4th
  column. A light block has no collision and is invisible, so eye level is fine.
  (A first version kept them out of 2-high rooms after a test "failed" on the
  shareware E1M1 start. The test was wrong: it counted a light block as solid.
  E1M1's 2-high start room was then pitch dark in game.)
- **Half-block floors.** `floorY` is the block the floor surface sits in;
  `halfStep` puts a bottom slab in it, and headroom over a slab is 3 blocks.
  A half step with exactly one cardinal neighbour half a block higher is a
  stair facing it (`Material.level` carries the facing; `BuildJob` rotates
  every state with the build). A corner stays a slab.
- **Hazard floors are lava only where contained**: Doom damaging specials
  (4, 5, 7, 11, 16, and Boom's bits 5-6). Lava needs every 4-neighbour floor at
  least as high and a bed under it; otherwise magma. Lower walls reach one
  below the lowest neighbour's floor so nothing beside a pool is open. The test
  `stepsAndHazards` checks every liquid block is enclosed.
- **No flammable blocks in the automatic palette.** Overworld planks were in it
  and E1M1 caught fire on the rig: wood-brown floors beside the nukage-lava.
  Crimson and warped planks do not burn and stay. Do not add anything that
  burns back to `CANDIDATES` or `SLABS`.
- **Colours come from the WAD**, matched to blocks by redmean distance;
  `config/wadcraft/blocks.json` overrides by name. A bare PWAD borrows an IWAD
  from the folder of the same style (ExMy vs MAPxx).
- **Placement: no neighbour updates** (`UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`),
  20,000 blocks a tick, one job at a time. Undo records replaced block states
  (not block entities), per player, in memory, up to 8M blocks.
- The `.gitignore` roots `/build/`: a bare `build/` silently swallowed the
  `com.sablednah.wadcraft.build` package on the first commit attempt.

## Testing in game

Use a Vivo rig (see LegendQuest's CLAUDE.md and `~/dev/README.md` on Vivo).
WadCraft's dev ports are **25586 / RCON 25596**; `clientBuddy` joins 25586.
