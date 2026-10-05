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
    private boolean[] halfStep;  // per sector: the floor surface is half a block up (a slab)
    private boolean[] hazard;    // per sector: a damaging floor (nukage, slime, lava)
    private boolean[] lift;      // per sector: a lift, built raised as Doom stores it
    private int[] doomFloor, doomCeil; // per sector, Doom units, doors already opened
    private int[] colCeil;       // per column: its ceiling block, after lintel clearance
    private int[] skyTop;        // per column: top air block if open to the sky, else MIN_VALUE
    private LineIndex lineIndex;

    /** How far an outdoor area's tallest sky reaches out to raise the walls around it. */
    private static final int SKY_WALL_RADIUS = 24;

    /** Doom lifts: W1, S1, SR, WR, and the turbo four. Lowered by tag. */
    private static final Set<Integer> DOOM_LIFTS = Set.of(10, 21, 62, 88, 120, 121, 122, 123);
    /** Hexen: Plat_DownWaitUpStay. */
    private static final Set<Integer> HEXEN_LIFTS = Set.of(62);

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
        thinWalls();
        columnCeilings();

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
                List.copyOf(materials), columns, sectorOf.clone(), blocks);
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
        doomFloor = floors;
        doomCeil = ceilings;
        lift = findLifts();

        floorY = new int[n];
        ceilY = new int[n];
        sky = new boolean[n];
        halfStep = new boolean[n];
        hazard = new boolean[n];
        for (int s = 0; s < n; s++) {
            hazard[s] = options.hazards() && damaging(sectors.get(s).special());
            // Floors in half blocks, so a Doom staircase of small steps becomes
            // slab, block, slab, block: walkable without a single jump. floorY is
            // the block the floor's surface sits in; a half step puts a bottom
            // slab in it. A hazard floor is never a slab: lava has no half.
            long halves = Math.round(floors[s] * 2 / scale);
            if (options.halfSteps() && !hazard[s]) {
                floorY[s] = (int) Math.floorDiv(halves, 2);
                halfStep[s] = (halves & 1) != 0;
            } else {
                floorY[s] = (int) Math.round(floors[s] / scale);
            }
            ceilY[s] = (int) Math.round(ceilings[s] / scale);
            // Rounding must never seal a space a Doom player could walk through:
            // two blocks of headroom, three when standing on a slab.
            if (ceilings[s] - floors[s] >= DOOM_PLAYER_HEIGHT) {
                ceilY[s] = Math.max(ceilY[s], floorY[s] + (halfStep[s] ? 3 : 2));
            }
            sky[s] = sectors.get(s).ceilingFlat().startsWith("F_SKY");
        }
    }

    /** Sectors a lift special lowers, found by tag (Hexen: by its first argument). */
    private boolean[] findLifts() {
        boolean hexen = map.format() == WadFile.MapFormat.HEXEN;
        Set<Integer> tags = new java.util.HashSet<>();
        for (LineDef line : map.lines()) {
            if ((hexen ? HEXEN_LIFTS : DOOM_LIFTS).contains(line.special()) && line.tag() != 0) tags.add(line.tag());
        }
        boolean[] lifts = new boolean[map.sectors().size()];
        for (int s = 0; s < lifts.length; s++) lifts[s] = tags.contains(map.sectors().get(s).tag());
        return lifts;
    }

    /** Could a Doom player walk from sector a into sector b? 56 units of opening, a step of 24 at most. */
    private boolean doomPassable(int a, int b) {
        int open = Math.min(doomCeil[a], doomCeil[b]) - Math.max(doomFloor[a], doomFloor[b]);
        return open >= DOOM_PLAYER_HEIGHT && Math.abs(doomFloor[a] - doomFloor[b]) <= 24;
    }

    /**
     * Each column's ceiling, raised where it has to be for someone to walk in.
     *
     * <p>Rounding is per sector, but walking is between them. A doorway can be
     * 56 units high in Doom, exactly enough, and still come out a block and a
     * half above the higher of the two floors once each side is rounded on its
     * own; a player on the high side then cannot duck under the lintel. So the
     * edge column of the lower ceiling is raised until there is room to stand on
     * either floor beneath it. Found in E1M2, by a test that walks every
     * opening.</p>
     */
    private void columnCeilings() {
        colCeil = new int[width * depth];
        skyTop = new int[width * depth];
        java.util.Arrays.fill(skyTop, Integer.MIN_VALUE);
        for (int j = 0; j < depth; j++) {
            for (int i = 0; i < width; i++) {
                int k = j * width + i, s = sectorOf[k];
                if (s < 0) continue;
                int c = ceilY[s];
                if (c > floorY[s]) {
                    // Corners too: a doorway's edge can run diagonally, and a
                    // player cutting the corner meets the column it touches.
                    for (int[] d : NEIGHBOURS_8) {
                        int n = sectorAt(i + d[0], j + d[1]);
                        if (n < 0 || n == s || !doomPassable(s, n)) continue;
                        int standHalves = Math.max(surface(s), surface(n));
                        c = Math.max(c, Math.floorDiv(standHalves + 4 + 1, 2)); // 2 blocks of headroom
                    }
                }
                colCeil[k] = c;
                if (sky[s]) skyTop[k] = c - 1;
            }
        }
    }

    private int colCeilAt(int i, int j) {
        return colCeil[j * width + i];
    }

    /**
     * The tallest sky within reach of a wall column. Doom never draws a wall
     * between two sky ceilings of different heights: it paints sky above the
     * lower one, so an outdoor area looks walled in by sky. Minecraft's sky is
     * the world, so the walls around outdoor areas are raised to the tallest sky
     * nearby, or the player sees straight out over them.
     */
    private int nearbySkyTop(int i, int j) {
        int best = Integer.MIN_VALUE;
        for (int y = Math.max(0, j - SKY_WALL_RADIUS); y <= Math.min(depth - 1, j + SKY_WALL_RADIUS); y++) {
            for (int x = Math.max(0, i - SKY_WALL_RADIUS); x <= Math.min(width - 1, i + SKY_WALL_RADIUS); x++) {
                best = Math.max(best, skyTop[y * width + x]);
            }
        }
        return best;
    }

    /**
     * A floor that hurts: Doom's damaging sector specials (4, 5, 7, 11, 16), and
     * Boom's generalised ones, which carry damage in bits 5-6. Hexen numbers its
     * sector specials differently and is left alone.
     */
    private boolean damaging(int special) {
        if (map.format() != WadFile.MapFormat.DOOM) return false;
        if (special >= 32) return (special & 0x60) != 0;
        return special == 4 || special == 5 || special == 7 || special == 11 || special == 16;
    }

    /** Floor surface in half blocks: a slab adds one. */
    private int surface(int s) {
        return floorY[s] * 2 + (halfStep[s] ? 1 : 0);
    }

    /**
     * A half step right beside a floor half a block higher is a stair facing up
     * it, so a run of small steps reads as a staircase rather than as slabs.
     * Only when exactly one side is that step up; a corner stays a slab.
     *
     * @return {@link Material#FACING_NONE}, or a facing toward the higher floor
     */
    private int stairFacing(int i, int j, int s) {
        int found = Material.FACING_NONE;
        for (int k = 0; k < NEIGHBOURS_4.length; k++) {
            int n = sectorAt(i + NEIGHBOURS_4[k][0], j + NEIGHBOURS_4[k][1]);
            if (n < 0 || surface(n) != surface(s) + 1) continue;
            if (found != Material.FACING_NONE) return Material.FACING_NONE;
            found = FACING_OF[k];
        }
        return found;
    }

    /** A ladder faces away from the block it hangs on: a lift to the east means facing west. */
    private static final int[] LADDER_FACING = {Material.FACING_WEST, Material.FACING_EAST,
            Material.FACING_NORTH, Material.FACING_SOUTH};

    /** NEIGHBOURS_4's directions as Material facings: east, west, south, north. */
    private static final int[] FACING_OF = {Material.FACING_EAST, Material.FACING_WEST,
            Material.FACING_SOUTH, Material.FACING_NORTH};

    /**
     * Lava flows. It is only safe where every neighbour's floor is at least as
     * high, so the blocks beside it at its own level are solid; anywhere it could
     * spill onto a lower floor, the solid stand-in is used instead.
     */
    private boolean contained(int i, int j, int f) {
        for (int[] d : NEIGHBOURS_4) {
            int n = sectorAt(i + d[0], j + d[1]);
            if (n >= 0 && floorY[n] < f) return false;
        }
        return true;
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

    /** Off the grid, so a test point never sits exactly on an integer vertex or line. */
    private static final double NUDGE_X = 0.21, NUDGE_Y = 0.37;

    private void classifyColumns() {
        sectorOf = new int[width * depth];
        List<LineDef> lines = map.lines();
        List<Vertex> v = map.vertices();
        List<LineDef> spanning = new ArrayList<>();
        for (int j = 0; j < depth; j++) {
            // Nudged off the grid so the ray never passes exactly through a vertex.
            double py = centreY(j) + NUDGE_Y;
            spanning.clear();
            for (LineDef line : lines) {
                double y1 = v.get(line.v1()).y(), y2 = v.get(line.v2()).y();
                if ((y1 > py) != (y2 > py)) spanning.add(line);
            }
            for (int i = 0; i < width; i++) {
                double px = centreX(i) + NUDGE_X;
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

    /**
     * Walls and sectors thinner than a column.
     *
     * <p>Each column takes the sector at its centre, so anything that falls
     * between two centres simply vanishes: a wall drawn as two one-sided lines
     * 0-16 units apart, a thin pillar, and - found facing east from E1M2's start -
     * a window, whose sill and lintel are a sector a few units deep between the
     * room and the outdoors. With it gone the room ran straight into the yard and
     * the wall above and below each window went missing.</p>
     *
     * <p>So the segment joining each pair of neighbouring centres is followed
     * through every line it crosses. If it passes through the void, the column
     * nearer that crossing becomes wall. If it passes through a thin sector that
     * blocks more than either end does (a sill higher than both floors, a lintel
     * lower than both ceilings, a shut door), the nearer column becomes that
     * sector. A thin step on a staircase blocks nothing, and is left alone.</p>
     */
    private void thinWalls() {
        for (int j = 0; j < depth; j++) {
            for (int i = 0; i < width; i++) {
                for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                    int ni = i + d[0], nj = j + d[1];
                    if (ni >= width || nj >= depth) continue;
                    int k = j * width + i, nk = nj * width + ni;
                    int a = sectorOf[k], b = sectorOf[nk];
                    if (a < 0 || b < 0) continue;
                    // Nudged exactly as classifyColumns nudges: column centres fall on
                    // multiples of 16, where Doom lines very often lie, and a segment
                    // that starts on a line does not cross it. E1M2's start-room
                    // windows were lost that way after this pass first went in.
                    double ax = centreX(i) + NUDGE_X, ay = centreY(j) + NUDGE_Y;
                    double bx = centreX(ni) + NUDGE_X, by = centreY(nj) + NUDGE_Y;
                    List<LineIndex.Crossing> crossings = lineIndex.crossings(ax, ay, bx, by);
                    if (crossings.size() < 2 && !(crossings.size() == 1 && !crossings.get(0).line().twoSided())) {
                        continue; // straight from a to b: nothing in between
                    }
                    // Walk the regions between crossings; the last is b's own.
                    int pick = Integer.MIN_VALUE; // -1 = void, else a sector
                    double pickT = 0;
                    for (int c = 0; c < crossings.size(); c++) {
                        LineIndex.Crossing x = crossings.get(c);
                        if (!x.line().twoSided()) {
                            pick = -1;
                            pickT = x.t();
                            break;
                        }
                        if (c == crossings.size() - 1) break;
                        int region = sideTowards(x.line(), bx, by);
                        if (region < 0 || region == a || region == b) continue;
                        if (blocksMore(region, a, b) && (pick == Integer.MIN_VALUE || opening(region) < opening(pick))) {
                            pick = region;
                            pickT = (x.t() + crossings.get(c + 1).t()) / 2;
                        }
                    }
                    if (pick == Integer.MIN_VALUE) continue;
                    if (pickT <= 0.5) sectorOf[k] = pick;
                    else sectorOf[nk] = pick;
                }
            }
        }
    }

    /** The sector on the side of {@code line} that the point (x, y) is on, or -1. */
    private int sideTowards(LineDef line, double x, double y) {
        Vertex a = map.vertices().get(line.v1()), b = map.vertices().get(line.v2());
        double cross = (b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x());
        return map.sectorOf(cross < 0 ? line.front() : line.back());
    }

    /** Does sector {@code c} stand in the way more than a and b do: a sill, a lintel, a shut door? */
    private boolean blocksMore(int c, int a, int b) {
        return doomFloor[c] > Math.max(doomFloor[a], doomFloor[b]) + 8
                || doomCeil[c] < Math.min(doomCeil[a], doomCeil[b]) - 8
                || doomCeil[c] - doomFloor[c] < DOOM_PLAYER_HEIGHT;
    }

    private int opening(int s) {
        return s < 0 ? Integer.MIN_VALUE : doomCeil[s] - doomFloor[s];
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
            boolean bySky = false;
            for (int[] d : NEIGHBOURS_8) {
                int n = sectorAt(i + d[0], j + d[1]);
                if (n < 0) continue;
                int nc = colCeilAt(i + d[0], j + d[1]);
                lo = Math.min(lo, floorY[n] - 1);
                hi = Math.max(hi, sky[n] ? nc - 1 : nc);
                bySky |= sky[n];
            }
            if (bySky) hi = Math.max(hi, nearbySkyTop(i, j));
            if (lo <= hi) runs.add(lo, hi, wall(outerWallTexture(px, py)));
            return runs.toArray();
        }

        int f = floorY[s], c = colCeilAt(i, j);
        int lowest = f, highest = c;
        int ladderTop = Integer.MIN_VALUE, ladderFacing = Material.FACING_NONE;
        for (int k = 0; k < NEIGHBOURS_4.length; k++) {
            int[] d = NEIGHBOURS_4[k];
            int n = sectorAt(i + d[0], j + d[1]);
            if (n < 0) continue;
            int nc = colCeilAt(i + d[0], j + d[1]);
            // A raised neighbouring column (a lintel cleared above) needs this
            // one solid up to its height too, sector or not.
            if (n == s) {
                if (!sky[s]) highest = Math.max(highest, nc - 1);
                continue;
            }
            lowest = Math.min(lowest, floorY[n]);
            if (!(sky[s] && sky[n])) highest = Math.max(highest, nc - 1);
            // A lift too high to step onto gets a ladder up its face, on this side.
            if (lift[n] && !lift[s] && floorY[n] - floorY[s] >= 2 && floorY[n] - 1 > ladderTop) {
                ladderTop = floorY[n] - 1;
                ladderFacing = LADDER_FACING[k];
            }
        }

        if (c <= f) {
            // Shut: a door left closed, or a crusher. Solid, in its upper texture.
            int top = Math.max(c, highest);
            runs.add(Math.min(lowest, f - 1), Math.max(top, f), wall(stepTexture(px, py, s, true)));
            return runs.toArray();
        }

        String floorFlat = map.sectors().get(s).floorFlat();
        // The lower wall reaches one below the lowest neighbour's floor, to its
        // floor block's level, so nothing beside a pool at that level is open.
        if (lowest < f) runs.add(lowest - 1, f - 2, wall(stepTexture(px, py, s, false)));
        if (hazard[s]) {
            boolean liquid = contained(i, j, f);
            if (liquid && lowest >= f) runs.add(f - 2, f - 2, flat(floorFlat)); // a bed under the lava
            runs.add(f - 1, f - 1, new Material(Material.Kind.HAZARD, floorFlat, liquid ? 0 : 1));
        } else {
            runs.add(f - 1, f - 1, flat(floorFlat));
        }
        if (halfStep[s]) {
            runs.add(f, f, new Material(Material.Kind.SLAB, floorFlat, stairFacing(i, j, s)));
            f++; // air starts above the slab's block
        }

        int airTop = c - 1;
        if (ladderFacing != Material.FACING_NONE) {
            int top = Math.min(ladderTop, airTop);
            runs.add(f, top, new Material(Material.Kind.LADDER, "", ladderFacing));
            f = top + 1;
        }
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
