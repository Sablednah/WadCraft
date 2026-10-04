package com.sablednah.wadcraft.wad;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One level's geometry, read from the binary map lumps.
 *
 * <p>Record sizes follow id's {@code p_setup.c} ({@code mapvertex_t},
 * {@code maplinedef_t} and friends). Hexen's format, recognised by its
 * {@code BEHAVIOR} lump, widens THINGS to 20 bytes and LINEDEFS to 16 (a one-byte
 * special and five arguments in place of special and tag); vertices, sidedefs
 * and sectors are the same in both.</p>
 *
 * <p>The node tree ({@code NODES}, {@code SEGS}, {@code SSECTORS}) is deliberately
 * not read. The builder decides which sector a point is in from the linedefs
 * alone, so a map built with any node builder, or none, works the same.</p>
 */
public record DoomMap(String name, WadFile.MapFormat format, List<Vertex> vertices,
        List<LineDef> lines, List<SideDef> sides, List<Sector> sectors, List<Thing> things) {

    /** "No sidedef": the back of a one-sided line. */
    public static final int NONE = 0xFFFF;

    public record Vertex(int x, int y) {}

    /**
     * @param special the line's action, in its own format's numbering
     * @param tag     Doom's sector tag; for Hexen, the special's first argument
     * @param front   sidedef on the right of v1→v2, or {@link #NONE}
     * @param back    sidedef on the left, or {@link #NONE} for a one-sided line
     */
    public record LineDef(int v1, int v2, int flags, int special, int tag, int front, int back) {
        public boolean twoSided() {
            return back != NONE && front != NONE;
        }
    }

    /** Textures are upper-cased names; "-" means none. */
    public record SideDef(int xOffset, int yOffset, String upper, String lower, String middle, int sector) {}

    public record Sector(int floor, int ceiling, String floorFlat, String ceilingFlat,
            int light, int special, int tag) {}

    /** {@code angle} in degrees: 0 east, 90 north. */
    public record Thing(int x, int y, int angle, int type, int flags) {}

    public static DoomMap read(WadFile wad, String mapName) throws IOException {
        WadFile.MapEntry entry = wad.map(mapName)
                .orElseThrow(() -> new IOException(wad.label() + " has no map called " + mapName));
        if (entry.format() == WadFile.MapFormat.UDMF) {
            throw new IOException(mapName + " is a UDMF (text) map, which WadCraft cannot read yet");
        }
        boolean hexen = entry.format() == WadFile.MapFormat.HEXEN;
        int marker = entry.markerIndex();

        List<Vertex> vertices = new ArrayList<>();
        ByteBuffer b = lump(wad, marker, "VERTEXES");
        while (b.remaining() >= 4) vertices.add(new Vertex(b.getShort(), b.getShort()));

        List<LineDef> lines = new ArrayList<>();
        b = lump(wad, marker, "LINEDEFS");
        if (hexen) {
            while (b.remaining() >= 16) {
                int v1 = u16(b), v2 = u16(b), flags = u16(b);
                int special = b.get() & 0xFF;
                int arg0 = b.get() & 0xFF;
                b.position(b.position() + 4); // args 1-4
                lines.add(new LineDef(v1, v2, flags, special, arg0, u16(b), u16(b)));
            }
        } else {
            while (b.remaining() >= 14) {
                lines.add(new LineDef(u16(b), u16(b), u16(b), u16(b), u16(b), u16(b), u16(b)));
            }
        }

        List<SideDef> sides = new ArrayList<>();
        b = lump(wad, marker, "SIDEDEFS");
        while (b.remaining() >= 30) {
            int xo = b.getShort(), yo = b.getShort();
            String upper = name8(b), lower = name8(b), middle = name8(b);
            sides.add(new SideDef(xo, yo, upper, lower, middle, u16(b)));
        }

        List<Sector> sectors = new ArrayList<>();
        b = lump(wad, marker, "SECTORS");
        while (b.remaining() >= 26) {
            int floor = b.getShort(), ceiling = b.getShort();
            String floorFlat = name8(b), ceilingFlat = name8(b);
            sectors.add(new Sector(floor, ceiling, floorFlat, ceilingFlat, b.getShort(), b.getShort(), b.getShort()));
        }

        List<Thing> things = new ArrayList<>();
        b = lump(wad, marker, "THINGS");
        if (hexen) {
            while (b.remaining() >= 20) {
                b.getShort(); // thing id
                int x = b.getShort(), y = b.getShort();
                b.getShort(); // starting height
                int angle = b.getShort(), type = b.getShort(), flags = b.getShort();
                b.position(b.position() + 6); // special + 5 args
                things.add(new Thing(x, y, angle, type, flags));
            }
        } else {
            while (b.remaining() >= 10) {
                things.add(new Thing(b.getShort(), b.getShort(), b.getShort(), b.getShort(), b.getShort()));
            }
        }

        DoomMap map = new DoomMap(entry.name(), entry.format(), List.copyOf(vertices), List.copyOf(lines),
                List.copyOf(sides), List.copyOf(sectors), List.copyOf(things));
        map.validate(wad.label());
        return map;
    }

    /** Refuse a map whose indexes point nowhere, rather than throwing halfway through a build. */
    private void validate(String label) throws IOException {
        for (LineDef l : lines) {
            if (l.v1() >= vertices.size() || l.v2() >= vertices.size()) {
                throw new IOException(label + " " + name + ": a linedef names a vertex that does not exist");
            }
            for (int s : new int[] {l.front(), l.back()}) {
                if (s != NONE && s >= sides.size()) {
                    throw new IOException(label + " " + name + ": a linedef names a sidedef that does not exist");
                }
            }
        }
        for (SideDef s : sides) {
            if (s.sector() >= sectors.size()) {
                throw new IOException(label + " " + name + ": a sidedef names a sector that does not exist");
            }
        }
    }

    /** Player 1's start (thing type 1), if the map has one. */
    public Optional<Thing> playerStart() {
        return things.stream().filter(t -> t.type() == 1).findFirst();
    }

    /** The sector on the given side of a line, or -1. */
    public int sectorOf(int sidedef) {
        return sidedef == NONE ? -1 : sides.get(sidedef).sector();
    }

    private static ByteBuffer lump(WadFile wad, int marker, String name) throws IOException {
        return wad.bytes(wad.mapLump(marker, name)
                .orElseThrow(() -> new IOException(wad.label() + ": map is missing its " + name + " lump")));
    }

    private static int u16(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }

    private static String name8(ByteBuffer b) {
        byte[] raw = new byte[8];
        b.get(raw);
        return WadFile.name(raw, 0);
    }
}
