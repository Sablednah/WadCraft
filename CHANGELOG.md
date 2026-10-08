# Changelog

All notable changes to WadCraft are recorded here.
This project follows [Semantic Versioning](https://semver.org/).

## 0.1.1 — 2026-10-08

For Minecraft 26.3 only: it now needs NeoForge 26.3.0.58-beta or newer, like
LegendQuest 2.9.1 and CrawlSpace 0.2.0. Nothing else changes, and the other
versions stay on 0.1.0. For 26.3.0.33-beta to 26.3.0.36-beta, keep 0.1.0.

## 0.1.0 — 2026-10-07

The first version, for Minecraft 1.21.11 on NeoForge; builds for 26.1.2, 26.2 and 26.3 followed the same day.

- Reads IWADs and PWADs, with Doom and Hexen binary maps.
- `/wadcraft list`, `maps`, `info`, `build [here|look] [scale]`, `undo` and
  `cancel`, for operators. `WadCraftApi` for other mods.
- Builds each map's floors, ceilings and walls, sealed all round, with rooms
  open to the sky where Doom has sky, and outdoor walls raised to the nearby sky.
- Colours each texture and flat with the block nearest its average colour, read
  from the WAD; `config/wadcraft/blocks.json` overrides by name.
- Half-block floors: slabs for half steps, and stairs facing up a step.
- Damaging floors become lava where it cannot spill, magma where it could.
- Doors are built open; lifts are built raised, with a ladder up the face.
- Thin walls, windows, lintels and door frames narrower than a block are kept,
  a block thick, rather than lost between columns.
- Every opening a Doom player can walk through is walkable: checked across all
  267 maps of the WADs it was developed against.
- Sector lighting with invisible light blocks.
- No flammable blocks in the automatic palette.
- Placement is a slice per tick; `/wadcraft undo` restores what the last build
  replaced (block states only, kept in memory until the server stops).
