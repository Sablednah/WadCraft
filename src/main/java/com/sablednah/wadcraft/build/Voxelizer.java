package com.sablednah.wadcraft.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import com.sablednah.wadcraft.wad.DoomMap;
import com.sablednah.wadcraft.wad.DoomMap.LineDef;
import com.sablednah.wadcraft.wad.DoomMap.Sector;
import com.sablednah.wadcraft.wad.DoomMap.Vertex;
import com.sablednah.wadcraft.wad.WadFile;

/**
 * Turns a Doom map into columns of blocks.
 *
 * <p><b>Why this works at all:</b> Doom is 2.5D. Every point of a level is in
 * exactly one <i>sector</i>, and a sector is one floor height and one ceiling
 * height. So each block column is: solid below the floor, air up to the ceiling,
 * solid above. The only real work is deciding which sector a column is in, and
 * closing the gaps where neighbouring columns differ.</p>
 *
 * <p><b>Which sector:</b> cast a ray east from the column's centre and take the
 * first linedef it crosses. The side of that line facing the point names the
 * sector; a side with no sidedef is the void outside the level. That needs
 * nothing but linedefs, so it does not care which node builder made the map.</p>
 *
 * <p><b>Walls:</b> Doom's walls are infinitely thin, and a block is not. So the
 * outer walls go in the void columns just outside the level, and a step or a
 * lowered ceiling becomes extra solid in the column on the short side. That is
 * enough to make the build watertight without tracing a single wall.</p>
 *
 * <p>No Minecraft imports: the output names textures, and a later step turns
 * those into blocks.</p>
 */
public final class Voxelizer {

    /** Doom's player is 56 units tall; anything at least this high was walkable. */
    private static final int DOOM_PLAYER_HEIGHT = 56;

    /** Lines that open the sector behind them when used (DR and SR doors). */
    private static final Set<Integer> DOOM_LOCAL_DOORS = Set.of(1, 26, 27, 28, 31, 32, 33, 34, 117, 118);
    /** Lines that open sectors by tag. */
    private static final Set<Integer> DOOM_TAGGED_DOORS = Set.of(2, 4, 29, 46, 61, 63, 86, 90, 99,
            103, 105, 106, 108, 109, 111, 112, 114, 115, 133, 134, 135, 136, 137);
    /** Hexen: Door_Open, Door_Raise, Door_LockedRaise. Tag 0 means "the sector behind". */
    private static final Set<Integer> HEXEN_DOORS = Set.of(11, 12, 13);

    private final DoomMap map;
    private final BuildOptions options;
    private final double scale;

    private int minX, minY, width, depth;
    private int[] sectorOf;      // per column, -1 = void
    private int[] floorY, ceilY; // per sector, in blocks
    private boolean[] sky;       // per sector
    private LineIndex lineIndex;

    private final List<Material> materials = new ArrayList<>();
    private final Map<Material, Integer> materialIds = new HashMap<>();

    private Voxelizer(DoomMap map, BuildOptions options) {
        this.map = map;
        this.options = options;
        this.scale = options.unitsPerBlock();
        material(Material.AIR); // id 0
    }

    public static VoxelModel build(DoomMap map, BuildOptions options) {
        return new Voxelizer(map, options).run();
    }

    private VoxelModel run() {
        bounds();
        sectorHeights();
        classifyColumns();
        lineIndex = new LineIndex(map, minX, minY, scale);

        int[][] columns = new int[width * depth][];
        long blocks = 0;
        for (int j = 0; j < depth; j++) {
            for (int i = 0; i < width; i++) {
                int[] runs = column(i, j);
                columns[j * width + i] = runs;
                for (int r = 0; r < runs.length; r += 3) blocks += runs[r + 1] - runs[r] + 1;
            }
        }

        // The anchor: player 1's start, standing on its sector's floor.
        int originI = width / 2, originJ = depth / 2, originY = Integer.MAX_VALUE, startAngle = 90;
        var start = map.playerStart();
        if (start.isPresent()) {
            originI = toI(start.get().x());
            originJ = toJ(start.get().y());
            int s = sectorOf[originJ * width + originI];
            if (s >= 0) originY = floorY[s];
            startAngle = start.get().angle();
        }
        if (originY == Integer.MAX_VALUE) {
            for (int s = 0; s < map.sectors().size(); s++) originY = Math.min(originY, floorY[s]);
        }
        return new VoxelModel(map.name(), width, depth, originI, originJ, originY, startAngle,
                List.copyOf(materials), columns, blocks);
    }

    // --- grid ---

    private void bounds() {
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        minX = Integer.MAX_VALUE;
        minY = Integer.MAX_VALUE;
        for (Vertex v : map.vertices()) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
        }
        // One column of margin all round, for the outer walls.
        minX -= (int) scale;
        minY -= (int) scale;
        maxX += (int) scale;
        maxY += (int) scale;
        width = (int) Math.ceil((maxX - minX) / scale);
        depth = (int) Math.ceil((maxY - minY) / scale);
        this.maxY = maxY;
    }

    private int maxY;

    /** Doom x/y at the centre of column (i, j). Rows run north to south, as Minecraft's z does. */
    private double centreX(int i) {
        return minX + (i + 0.5) * scale;
    }

    private double centreY(int j) {
        return maxY - (j + 0.5) * scale;
    }

    private int toI(int x) {
        return clamp((int) Math.floor((x - minX) / scale), width);
    }

    private int toJ(int y) {
        return clamp((int) Math.floor((maxY - y) / scale), depth);
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    // --- sectors ---

    private void sectorHeights() {
        List<Sector> sectors = map.sectors();
        int n = sectors.size();
        int[] floors = new int[n], ceilings = new int[n];
        for (int s = 0; s < n; s++) {
            floors[s] = sectors.get(s).floor();
            ceilings[s] = sectors.get(s).ceiling();
        }
        if (options.openDoors()) openDoors(floors, ceilings);

        floorY = new int[n];
        ceilY = new int[n];
        sky = new boolean[n];
        for (int s = 0; s < n; s++) {
            floorY[s] = (int) Math.round(floors[s] / scale);
            ceilY[s] = (int) Math.round(ceilings[s] / scale);
            // Rounding must never seal a space a Doom player could walk through.
            if (ceilings[s] - floors[s] >= DOOM_PLAYER_HEIGHT) ceilY[s] = Math.max(ceilY[s], floorY[s] + 2);
            sky[s] = sectors.get(s).ceilingFlat().startsWith("F_SKY");
        }
    }

    /**
     * Doors in a Doom map are stored shut: a sector whose ceiling sits on its
     * floor. Built as they are, every door is a wall. So a sector that a door
     * special would open is opened the way the engine opens it: the ceiling
     * rises to 4 units below the lowest neighbouring ceiling.
     */
    private void openDoors(int[] floors, int[] ceilings) {
        boolean hexen = map.format() == WadFile.MapFormat.HEXEN;
        Set<Integer> doorSectors = new java.util.HashSet<>();
        Set<Integer> doorTags = new java.util.HashSet<>();
        for (LineDef line : map.lines()) {
            int sp = line.special();
            boolean local = hexen ? HEXEN_DOORS.contains(sp) && line.tag() == 0 : DOOM_LOCAL_DOORS.contains(sp);
            boolean tagged = hexen ? HEXEN_DOORS.contains(sp) && line.tag() != 0 : DOOM_TAGGED_DOORS.contains(sp);
            if (local && line.back() != DoomMap.NONE) doorSectors.add(map.sectorOf(line.back()));
            if (tagged) doorTags.add(line.tag());
        }
        for (int s = 0; s < map.sectors().size(); s++) {
            if (doorTags.contains(map.sectors().get(s).tag())) doorSectors.add(s);
        }
        for (int s : doorSectors) {
            if (ceilings[s] - floors[s] >= DOOM_PLAYER_HEIGHT) continue; // already open
            int lowest = Integer.MAX_VALUE;
            for (LineDef line : map.lines()) {
                if (!line.twoSided()) continue;
                int a = map.sectorOf(line.front()), b = map.sectorOf(line.back());
                if (a == s && b != s) lowest = Math.min(lowest, ceilings[b]);
                if (b == s && a != s) lowest = Math.min(lowest, ceilings[a]);
            }
            if (lowest != Integer.MAX_VALUE && lowest - 4 > floors[s]) ceilings[s] = lowest - 4;
        }
    }

    private void classifyColumns() {
        sectorOf = new int[width * depth];
        List<LineDef> lines = map.lines();
        List<Vertex> v = map.vertices();
        List<LineDef> spanning = new ArrayList<>();
        for (int j = 0; j < depth; j++) {
            // Nudged off the grid so the ray never passes exactly through a vertex.
            double py = centreY(j) + 0.37;
            spanning.clear();
            for (LineDef line : lines) {
                double y1 = v.get(line.v1()).y(), y2 = v.get(line.v2()).y();
                if ((y1 > py) != (y2 > py)) spanning.add(line);
            }
            for (int i = 0; i < width; i++) {
                double px = centreX(i) + 0.21;
                LineDef best = null;
                double bestX = Double.MAX_VALUE;
                for (LineDef line : spanning) {
                    Vertex a = v.get(line.v1()), b = v.get(line.v2());
                    double xi = a.x() + (py - a.y()) * (b.x() - a.x()) / (double) (b.y() - a.y());
                    if (xi > px && xi < bestX) {
                        bestX = xi;
                        best = line;
                    }
                }
                int sector = -1;
                if (best != null) {
                    Vertex a = v.get(best.v1()), b = v.get(best.v2());
                    double cross = (b.x() - a.x()) * (py - a.y()) - (b.y() - a.y()) * (px - a.x());
                    // Front is the right-hand side of v1->v2, which is cross < 0.
                    sector = map.sectorOf(cross < 0 ? best.front() : best.back());
                }
                sectorOf[j * width + i] = sector;
            }
        }
    }

    private int sectorAt(int i, int j) {
        if (i < 0 || j < 0 || i >= width || j >= depth) return -1;
        return sectorOf[j * width + i];
    }

    // --- columns ---

    private static final int[][] NEIGHBOURS_4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NEIGHBOURS_8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Runs for one column, flattened as (yLow, yHigh, material) triples. */
    private int[] column(int i, int j) {
        int s = sectorAt(i, j);
        Runs runs = new Runs();
        double px = centreX(i), py = centreY(j);

        if (s < 0) {
            // The void: a wall here if it borders the level.
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int[] d : NEIGHBOURS_8) {
                int n = sectorAt(i + d[0], j + d[1]);
                if (n < 0) continue;
                lo = Math.min(lo, floorY[n] - 1);
                hi = Math.max(hi, sky[n] ? ceilY[n] - 1 : ceilY[n]);
            }
            if (lo <= hi) runs.add(lo, hi, wall(outerWallTexture(px, py)));
            return runs.toArray();
        }

        int f = floorY[s], c = ceilY[s];
        int lowest = f, highest = c;
        for (int[] d : NEIGHBOURS_4) {
            int n = sectorAt(i + d[0], j + d[1]);
            if (n < 0 || n == s) continue;
            lowest = Math.min(lowest, floorY[n]);
            if (!(sky[s] && sky[n])) highest = Math.max(highest, ceilY[n] - 1);
        }

        if (c <= f) {
            // Shut: a door left closed, or a crusher. Solid, in its upper texture.
            int top = Math.max(c, highest);
            runs.add(Math.min(lowest, f - 1), Math.max(top, f), wall(stepTexture(px, py, s, true)));
            return runs.toArray();
        }

        if (lowest < f - 1) runs.add(lowest, f - 2, wall(stepTexture(px, py, s, false)));
        runs.add(f - 1, f - 1, flat(map.sectors().get(s).floorFlat()));

        int airTop = c - 1;
        // In the top air block. A light block has no collision and cannot be seen,
        // so even at eye level in a two-high corridor it is never in the way.
        boolean lightHere = options.lights() && Math.floorMod(i, 4) == 2 && Math.floorMod(j, 4) == 2 && c - f >= 2;
        // Doom's 0-255 onto 0-15, a little bright: Minecraft light falls off
        // between sources where Doom's is flat across a sector.
        int level = Math.min(15, Math.round(map.sectors().get(s).light() / 15f));
        if (lightHere && level > 0) {
            runs.add(f, airTop - 1, Material.AIR);
            runs.add(airTop, airTop, light(level));
        } else {
            runs.add(f, airTop, Material.AIR);
        }

        if (!sky[s]) {
            runs.add(c, c, flat(map.sectors().get(s).ceilingFlat()));
            if (highest > c) runs.add(c + 1, highest, wall(stepTexture(px, py, s, true)));
        } else if (highest >= c) {
            runs.add(c, highest, wall(stepTexture(px, py, s, true)));
        }
        return runs.toArray();
    }

    // --- textures ---

    /** A one-sided wall's middle texture, from the nearest one-sided line. */
    private String outerWallTexture(double px, double py) {
        LineDef line = lineIndex.nearest(px, py, idx -> !map.lines().get(idx).twoSided());
        if (line == null) return Material.UNKNOWN;
        int side = line.front() != DoomMap.NONE ? line.front() : line.back();
        var sd = map.sides().get(side);
        return first(sd.middle(), sd.upper(), sd.lower());
    }

    /**
     * The texture on a step (lower) or a lowered ceiling (upper) beside sector
     * {@code s}. Doom draws it from the sidedef facing the other sector, which is
     * the side a player looks at it from.
     */
    private String stepTexture(double px, double py, int s, boolean upper) {
        IntPredicate touches = idx -> {
            LineDef l = map.lines().get(idx);
            return l.twoSided() && (map.sectorOf(l.front()) == s || map.sectorOf(l.back()) == s);
        };
        LineDef line = lineIndex.nearest(px, py, touches);
        if (line == null) return outerWallTexture(px, py);
        var facing = map.sides().get(map.sectorOf(line.front()) == s ? line.back() : line.front());
        var own = map.sides().get(map.sectorOf(line.front()) == s ? line.front() : line.back());
        return upper
                ? first(facing.upper(), own.upper(), facing.middle(), facing.lower())
                : first(facing.lower(), own.lower(), facing.middle(), facing.upper());
    }

    private static String first(String... names) {
        for (String n : names) if (n != null && !n.isEmpty() && !n.equals("-")) return n;
        return Material.UNKNOWN;
    }

    // --- materials ---

    private int material(Material m) {
        return materialIds.computeIfAbsent(m, k -> {
            materials.add(k);
            return materials.size() - 1;
        });
    }

    private Material wall(String texture) {
        return new Material(Material.Kind.WALL, texture, 0);
    }

    private Material flat(String flat) {
        return new Material(Material.Kind.FLAT, flat, 0);
    }

    private Material light(int level) {
        return new Material(Material.Kind.LIGHT, "", level);
    }

    private final class Runs {
        private final List<int[]> list = new ArrayList<>();

        void add(int lo, int hi, Material m) {
            if (hi < lo) return;
            list.add(new int[] {lo, hi, material(m)});
        }

        int[] toArray() {
            int[] out = new int[list.size() * 3];
            for (int k = 0; k < list.size(); k++) System.arraycopy(list.get(k), 0, out, k * 3, 3);
            return out;
        }
    }
}
