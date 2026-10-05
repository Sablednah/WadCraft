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

## Where it stands (2026-10-05)

**0.1.0, unreleased, judged "an acceptable WAD library" by Sable** after testing
E1M1 and E1M2 in his own instance: walls show, and the texture-to-block
matching was right from the start. Remaining gaps are those of a 1-block
(0.5 with slabs) rendering of a 2.5D map, plus the "Not yet" list.

- 1.21.11 only, on `main`. No version branches yet; when they come, follow
  LegendQuest's branch-per-version rule. The `neoforge` package is the part
  that will need porting; `wad` and `build` have no Minecraft imports.
- Public repo: https://github.com/Sablednah/WadCraft. No CurseForge project
  yet; `CURSEFORGE.md` is the drafted description. No release workflow yet.
- Store screenshots are in `screenshots/` (gitignored), taken on the Vivo rig:
  E1M1 hangar, nukage, lava cave, armour stairs and aerial; E1M2 windows and
  aerial; E1M8 pentagram; freedoom E1M1 aerial. Several show id's shareware
  maps, so the owner chooses which go on a store page; freedoom ones are the
  safe choice.
- **Not yet:** monsters, items and keys (things are read; only player 1's start
  is used); moving doors and lifts; UDMF maps; the shipped-WAD path for Space
  Husk / the sci-fi pack beyond `WadCraftApi.read(label, bytes)`.

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
- **Thin walls are thickened, not lost.** Doom walls between rooms are often
  thinner than a column (two one-sided lines 0-16 units apart), and each column
  takes the sector at its centre, so they vanished and rooms merged. `thinWalls`
  turns the column nearer any one-sided line crossing the segment between two
  neighbouring centres into void (and so a wall). Thin pillars survive the same
  way. **Thin sectors too**: if that segment passes through a sector that
  blocks more than either end (a sill above both floors, a lintel below both
  ceilings, a shut door), the nearer column becomes it. E1M2's start-room
  windows (16 units deep) were missing until this; thin staircase steps block
  nothing and are left alone.
- **Every test point is nudged off the grid** (`NUDGE_X`/`NUDGE_Y`). Column
  centres fall on multiples of 16 and so do many Doom lines; a segment that
  starts on a line does not cross it. The thin-sector pass first went in
  without the nudge and still lost the E1M2 windows for exactly that reason.
- **Ceilings are per column (`colCeil`), cleared for walking.** Rounding each
  sector alone left E1M2's lintels 1.5 blocks over the higher floor (Doom: 56
  units, exactly enough). Where two 8-neighbour columns' sectors are
  Doom-passable (opening >= 56, step <= 24), the lower ceiling is raised to give
  2 blocks over the higher floor. Corners count: diagonal edges.
- **Outdoor walls reach the nearby sky.** Doom draws no wall between two sky
  ceilings of different heights (it paints sky), so outer walls by a sky sector
  are raised to the tallest sky within 24 columns. Otherwise you see out.
- **Lifts are ladders.** Lift sectors (Doom 10/21/62/88/120-123, Hexen 62,
  by tag) stay raised as Doom stores them; the lower column beside each gets a
  ladder up the lift's face.
- **`everyDoomOpeningIsWalkable`** checks every 8-neighbour column pair of
  Doom-passable sectors in every map on disk (786k at the time of writing)
  for 2 blocks of headroom and a step of a block at most. It found the
  lintels, the merged thin walls and misread ladders; keep it at zero.
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
WadCraft's dev ports are **25586 / RCON 25596** (password `wcdev`);
`clientBuddy` joins 25586.

**The rig exists: `~/rig/wadcraft/` on Vivo**, display `:5`.
- Refresh and restart in one go, from here:
  `git ls-files -z | tar --null -T - -czf - | ssh -i ~/.ssh/vivo_ed25519 sable@192.168.7.246 'cd ~/rig/wadcraft/WadCraft && tar xzf - && timeout 400 ~/rig/wadcraft/restart.sh'`
  (`git ls-files` sends working-tree content, so uncommitted edits go too.)
  `restart.sh` stops server and client by repo path, restarts both, and
  prints JOINED when TestBuddy is in. Start `:5` first with
  `bash ~/dev/xstart.sh :5`.
- `run/wads/` there holds `freedoom1.wad` and `DOOM1.WAD` (Sable's own machine,
  so not distribution).
- Build as the player over RCON:
  `execute as TestBuddy at TestBuddy run wadcraft build DOOM1.WAD E1M1`.
  Facing yaw 180 makes a north-facing start (angle 90) build unturned, so
  model offsets map straight onto world x/z.
- Look with **spectator** mode (creative falls), window `xdotool windowsize
  <win> 1280 720`, `F1` hides the HUD, `tutorialStep:none` in
  `runBuddy/options.txt` (edit only while the client is stopped). Teleport the
  camera to the floor y, not one above it: in a 2-high room one block up puts
  the eye above the ceiling, which looks like a missing roof.
- To prove walking rather than infer it: creative, `windowfocus`, `xdotool
  keydown w; sleep 4; keyup w`, then compare `data get entity TestBuddy Pos`.
- Shut everything down after (stop, kill by repo path, kill the Xvfb by its
  lock-file pid, `running.sh prune`).

## Debugging a spot someone reports

Sable reports with a Minecraft screenshot and often the same view in Doom. The
fast path is to work in **Doom coordinates and the model**, not in the world:
small throwaway Java programs compiled against `build/classes/java/main`
(no Minecraft needed) that read the map and the `VoxelModel` and print:

- the sectors bordering one sector through two-sided lines, with heights and
  how many columns each got in the build (`0` = lost between columns);
- a grid of `model.sectors()` around a Doom x/y range, with column centres;
- where a Doom x/y (or a flat, or a material kind) lands as dx/dz from the
  player start, to teleport the camera there.

Recover the grid as the builder does: `minX = min vertex x - scale`,
`maxY = max vertex y + scale`, `i = floor((x - minX) / scale)`,
`j = floor((maxY - y) / scale)`. Every bug this session was found that way
before it was seen, and then confirmed on the rig.
