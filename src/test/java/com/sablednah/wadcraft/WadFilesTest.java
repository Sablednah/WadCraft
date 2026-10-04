package com.sablednah.wadcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.sablednah.wadcraft.build.BuildOptions;
import com.sablednah.wadcraft.build.Material;
import com.sablednah.wadcraft.build.VoxelModel;
import com.sablednah.wadcraft.build.Voxelizer;
import com.sablednah.wadcraft.wad.DoomMap;
import com.sablednah.wadcraft.wad.TextureColours;
import com.sablednah.wadcraft.wad.WadFile;

/**
 * Against real WADs, when they are on disk. None are in git (see .gitignore):
 * the commercial ones may not be redistributed, and the Freedoom ones are too
 * big. A missing WAD skips its test rather than failing it.
 */
class WadFilesTest {

    private static final Path DIR = Path.of(System.getProperty("wadcraft.wadDir", "."));
    private static final Path OUT = DIR.resolve("build/test-renders");

    private static List<Path> allWads() throws IOException {
        List<Path> wads = new ArrayList<>();
        for (Path dir : List.of(DIR, DIR.resolve("Wads-DONOTSHIP"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wad")).sorted().forEach(wads::add);
            }
        }
        return wads;
    }

    private static Path find(String name) throws IOException {
        for (Path p : allWads()) if (p.getFileName().toString().equalsIgnoreCase(name)) return p;
        return null;
    }

    @Test
    void freedoomE1M1() throws IOException {
        Path path = find("freedoom1.wad");
        assumeTrue(path != null, "freedoom1.wad not present");
        WadFile wad = WadFile.read(path);
        assertEquals(WadFile.Kind.IWAD, wad.kind());
        assertTrue(wad.map("E1M1").isPresent());
        DoomMap map = DoomMap.read(wad, "E1M1");
        assertTrue(map.playerStart().isPresent(), "E1M1 has a player start");
        VoxelModel model = Voxelizer.build(map, BuildOptions.defaults());
        int[] origin = model.column(model.originI(), model.originJ());
        assertTrue(hasAirAt(model, origin, model.originY()), "the player starts standing in air");
        assertTrue(hasAirAt(model, origin, model.originY() + 1), "with headroom");
        render(model, "freedoom1-E1M1");
    }

    @Test
    void shareWareE1M1() throws IOException {
        Path path = find("doom1.wad");
        assumeTrue(path != null, "doom1.wad not present");
        DoomMap map = DoomMap.read(WadFile.read(path), "E1M1");
        VoxelModel model = Voxelizer.build(map, BuildOptions.defaults());
        render(model, "doom1-E1M1");
        render(Voxelizer.build(DoomMap.read(WadFile.read(path), "E1M2"), BuildOptions.defaults()), "doom1-E1M2");
        int[] origin = model.column(model.originI(), model.originJ());
        System.out.println("doom1 start " + map.playerStart().get() + " origin column " + java.util.Arrays.toString(origin)
                + " originY " + model.originY());
        assertTrue(hasAirAt(model, origin, model.originY()) && hasAirAt(model, origin, model.originY() + 1));
    }

    /**
     * Shareware E1M1 has both: the steps up to the armour beside the start are
     * small enough to become slabs, and its nukage is a damaging floor. Every
     * liquid block must be enclosed, with solid on all four sides and below,
     * or it will run out of the pool the moment it is placed.
     */
    @Test
    void stepsAndHazards() throws IOException {
        Path path = find("doom1.wad");
        assumeTrue(path != null, "doom1.wad not present");
        VoxelModel model = Voxelizer.build(DoomMap.read(WadFile.read(path), "E1M1"), BuildOptions.defaults());
        int slabs = 0, stairs = 0, liquid = 0, spill = 0;
        for (int j = 0; j < model.depth(); j++) {
            for (int i = 0; i < model.width(); i++) {
                int[] runs = model.column(i, j);
                for (int r = 0; r < runs.length; r += 3) {
                    Material m = model.materials().get(runs[r + 2]);
                    if (m.kind() == Material.Kind.SLAB && m.level() == Material.FACING_NONE) slabs++;
                    if (m.kind() == Material.Kind.SLAB && m.level() != Material.FACING_NONE) stairs++;
                    if (m.kind() == Material.Kind.HAZARD && m.level() == 1) spill++;
                    if (m.kind() == Material.Kind.HAZARD && m.level() == 0) {
                        liquid++;
                        int y = runs[r];
                        assertTrue(solidAt(model, i, j, y - 1), "liquid at " + i + "," + j + " has nothing under it");
                        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int ni = i + d[0], nj = j + d[1];
                            boolean ok = solidAt(model, ni, nj, y) || liquidAt(model, ni, nj, y);
                            assertTrue(ok, "liquid at " + i + "," + j + " can spill towards " + ni + "," + nj);
                        }
                    }
                }
            }
        }
        System.out.printf("doom1 E1M1: %d slabs, %d stairs, %d lava, %d magma%n", slabs, stairs, liquid, spill);
        assertTrue(stairs > 0, "a half step beside a higher floor is a stair");
        assertTrue(slabs > 0, "E1M1's small steps become slabs");
        assertTrue(liquid > 0, "E1M1's nukage becomes a liquid where it is enclosed");
    }

    /**
     * Wherever a Doom player could walk from one sector into the next, a
     * Minecraft player must be able to walk between those columns: two blocks of
     * headroom above the higher floor, and a step of a block at most. Rounding
     * each sector on its own broke this at E1M2's lintels until ceilings were
     * cleared per column. Corners count: a diagonal edge is walked across them.
     */
    @Test
    void everyDoomOpeningIsWalkable() throws IOException {
        List<Path> wads = allWads();
        assumeTrue(!wads.isEmpty(), "no WADs present");
        int checked = 0;
        List<String> blocked = new ArrayList<>();
        for (Path path : wads) {
            WadFile wad;
            try {
                wad = WadFile.read(path);
            } catch (IOException damaged) {
                continue;
            }
            for (WadFile.MapEntry entry : wad.maps()) {
                if (entry.format() == WadFile.MapFormat.UDMF) continue;
                DoomMap map = DoomMap.read(wad, entry.name());
                VoxelModel model = Voxelizer.build(map, BuildOptions.defaults());
                int found = 0;
                for (int j = 0; j < model.depth(); j++) {
                    for (int i = 0; i < model.width(); i++) {
                        int a = model.sectors()[j * model.width() + i];
                        if (a < 0) continue;
                        for (int[] d : new int[][] {{1, 0}, {0, 1}, {1, 1}, {1, -1}}) {
                            int ni = i + d[0], nj = j + d[1];
                            if (ni < 0 || nj < 0 || ni >= model.width() || nj >= model.depth()) continue;
                            int b = model.sectors()[nj * model.width() + ni];
                            if (b < 0 || b == a || !doomPassable(map, a, b)) continue;
                            int[] sa = standing(model, model.column(i, j)), sb = standing(model, model.column(ni, nj));
                            if (sa == null || sb == null) continue; // a door left shut, or a lift
                            checked++;
                            int headroomHalves = Math.min(sa[1], sb[1]) * 2 - Math.max(sa[0], sb[0]);
                            if (headroomHalves < 4 || Math.abs(sa[0] - sb[0]) > 2) {
                                if (found++ == 0) {
                                    blocked.add(String.format("%s %s sectors %d/%d: headroom %.1f, step %.1f",
                                            path.getFileName(), entry.name(), a, b, headroomHalves / 2.0,
                                            Math.abs(sa[0] - sb[0]) / 2.0));
                                }
                            }
                        }
                    }
                }
            }
        }
        blocked.forEach(System.out::println);
        System.out.println("walkable openings checked: " + checked + ", maps with a blocked one: " + blocked.size());
        assertTrue(blocked.isEmpty(), blocked.size() + " map(s) have an opening Doom can walk and the build cannot");
    }

    private static boolean doomPassable(DoomMap map, int a, int b) {
        var sa = map.sectors().get(a);
        var sb = map.sectors().get(b);
        int open = Math.min(sa.ceiling(), sb.ceiling()) - Math.max(sa.floor(), sb.floor());
        // Doors are opened by the builder, so a shut door's sector is not judged here.
        return open >= 56 && Math.abs(sa.floor() - sb.floor()) <= 24;
    }

    /** {standing surface in half blocks, first solid block above}, or null if the column has no space. */
    private static int[] standing(VoxelModel model, int[] runs) {
        for (int r = 0; r < runs.length; r += 3) {
            Material.Kind kind = model.materials().get(runs[r + 2]).kind();
            // A ladder at a lift's foot fills the bottom of the space, and is walked past.
            if (kind == Material.Kind.LADDER) {
                int bottom = runs[r];
                int[] rest = standing(model, java.util.Arrays.copyOfRange(runs, r + 3, runs.length));
                if (rest == null) return null;
                Material below = at(model, runs, bottom - 1);
                return new int[] {bottom * 2 - (below != null && below.kind() == Material.Kind.SLAB ? 1 : 0), rest[1]};
            }
            if (kind != Material.Kind.AIR) continue;
            int surface = runs[r] * 2;
            Material below = at(model, runs, runs[r] - 1);
            if (below != null && below.kind() == Material.Kind.SLAB) surface--;
            int top = runs[r + 1] + 1;
            Material above = at(model, runs, top);
            if (above != null && above.kind() == Material.Kind.LIGHT) top++;
            return new int[] {surface, top};
        }
        return null;
    }

    private static Material at(VoxelModel model, int[] runs, int y) {
        for (int r = 0; r < runs.length; r += 3) {
            if (y >= runs[r] && y <= runs[r + 1]) return model.materials().get(runs[r + 2]);
        }
        return null;
    }

    private static Material at(VoxelModel model, int i, int j, int y) {
        if (i < 0 || j < 0 || i >= model.width() || j >= model.depth()) return null;
        int[] runs = model.column(i, j);
        for (int r = 0; r < runs.length; r += 3) {
            if (y >= runs[r] && y <= runs[r + 1]) return model.materials().get(runs[r + 2]);
        }
        return null;
    }

    private static boolean solidAt(VoxelModel model, int i, int j, int y) {
        Material m = at(model, i, j, y);
        return m != null && (m.kind() == Material.Kind.WALL || m.kind() == Material.Kind.FLAT
                || m.kind() == Material.Kind.SLAB || (m.kind() == Material.Kind.HAZARD && m.level() == 1));
    }

    private static boolean liquidAt(VoxelModel model, int i, int j, int y) {
        Material m = at(model, i, j, y);
        return m != null && m.kind() == Material.Kind.HAZARD && m.level() == 0;
    }

    /** Every binary map in every WAD present reads and builds without throwing. */
    @Test
    void everyMapBuilds() throws IOException {
        List<Path> wads = allWads();
        assumeTrue(!wads.isEmpty(), "no WADs present");
        int built = 0, skipped = 0;
        for (Path path : wads) {
            WadFile wad;
            try {
                wad = WadFile.read(path);
            } catch (IOException damaged) {
                System.out.println("unreadable: " + damaged.getMessage());
                continue;
            }
            List<WadFile> withIwad = List.of(wad);
            TextureColours colours = TextureColours.read(withIwad);
            System.out.printf("%-14s %s  %3d maps  %4d textures  %3d flats%n", path.getFileName(), wad.kind(),
                    wad.maps().size(), colours.wallCount(), colours.flatCount());
            for (WadFile.MapEntry entry : wad.maps()) {
                if (entry.format() == WadFile.MapFormat.UDMF) {
                    skipped++;
                    continue;
                }
                DoomMap map = DoomMap.read(wad, entry.name());
                VoxelModel model = Voxelizer.build(map, BuildOptions.defaults());
                assertTrue(model.blocks() > 0, path.getFileName() + " " + entry.name() + " built nothing");
                built++;
            }
        }
        System.out.println("built " + built + " maps, skipped " + skipped + " UDMF");
        assertTrue(built > 0);
    }

    @Test
    void hexenMap() throws IOException {
        Path path = find("hexen.wad");
        assumeTrue(path != null, "hexen.wad not present");
        WadFile wad = WadFile.read(path);
        WadFile.MapEntry first = wad.maps().get(0);
        assertEquals(WadFile.MapFormat.HEXEN, first.format());
        VoxelModel model = Voxelizer.build(DoomMap.read(wad, first.name()), BuildOptions.defaults());
        render(model, "hexen-" + first.name());
    }

    /** Air or a light block: both can be walked through. */
    private static boolean hasAirAt(VoxelModel model, int[] runs, int y) {
        for (int r = 0; r < runs.length; r += 3) {
            if (y >= runs[r] && y <= runs[r + 1]) {
                Material.Kind kind = model.materials().get(runs[r + 2]).kind();
                return kind == Material.Kind.AIR || kind == Material.Kind.LIGHT;
            }
        }
        return false;
    }

    /**
     * Top-down picture, 4px per column: floor height as brightness, walls
     * dark red, void black, the player start green. A wrong sector lookup
     * shows up at once as speckle or a flooded void.
     */
    private static void render(VoxelModel model, String name) throws IOException {
        Files.createDirectories(OUT);
        int px = 4;
        BufferedImage img = new BufferedImage(model.width() * px, model.depth() * px, BufferedImage.TYPE_INT_RGB);
        int[] range = model.heightRange();
        for (int j = 0; j < model.depth(); j++) {
            for (int i = 0; i < model.width(); i++) {
                int[] runs = model.column(i, j);
                int colour = 0;
                int floor = Integer.MIN_VALUE;
                boolean air = false, wall = false;
                for (int r = 0; r < runs.length; r += 3) {
                    Material m = model.materials().get(runs[r + 2]);
                    if (m.kind() == Material.Kind.AIR) {
                        air = true;
                        floor = runs[r];
                    }
                    if (m.kind() == Material.Kind.WALL) wall = true;
                }
                if (air) {
                    int v = 60 + (int) (180.0 * (floor - model.originY() - range[0]) / Math.max(1, range[1] - range[0]));
                    v = Math.max(0, Math.min(255, v));
                    colour = v << 16 | v << 8 | v;
                } else if (wall) {
                    colour = 0x802020;
                }
                if (i == model.originI() && j == model.originJ()) colour = 0x00FF00;
                for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) img.setRGB(i * px + x, j * px + y, colour);
            }
        }
        ImageIO.write(img, "png", OUT.resolve(name + ".png").toFile());
        System.out.printf("%s: %dx%d columns, %d blocks, height %d..%d%n", name, model.width(), model.depth(),
                model.blocks(), range[0], range[1]);
    }
}
