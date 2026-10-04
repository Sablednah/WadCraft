package com.sablednah.wadcraft.wad;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A WAD file: a 12-byte header, a pile of lumps, and a directory naming them.
 *
 * <p>Layout, as id's {@code w_wad.c} reads it: the header is the four bytes
 * {@code IWAD} or {@code PWAD}, then the lump count and the directory's offset
 * (both little-endian ints). Each directory entry is 16 bytes: offset, size, and
 * an 8-byte name padded with NULs. Lump names are not unique — every map has its
 * own {@code THINGS} — so lumps are found by position as often as by name.</p>
 *
 * <p>No Minecraft imports anywhere in this package, on purpose: parsing is where
 * a mistake is easy and silent, and code that runs without a game is code the
 * tests can check against real WADs.</p>
 */
public final class WadFile {

    public enum Kind { IWAD, PWAD }

    /** One directory entry. {@code index} is its position in the directory. */
    public record Lump(int index, String name, int offset, int size) {}

    private final String label;
    private final Kind kind;
    private final ByteBuffer data;
    private final List<Lump> lumps;

    private WadFile(String label, Kind kind, ByteBuffer data, List<Lump> lumps) {
        this.label = label;
        this.kind = kind;
        this.data = data;
        this.lumps = List.copyOf(lumps);
    }

    public static WadFile read(Path path) throws IOException {
        return read(path.getFileName().toString(), Files.readAllBytes(path));
    }

    /** From bytes, so a mod can hand over a WAD it carries in its own jar. */
    public static WadFile read(String label, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12) throw new IOException(label + ": too short to be a WAD");
        String magic = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        Kind kind = switch (magic) {
            case "IWAD" -> Kind.IWAD;
            case "PWAD" -> Kind.PWAD;
            default -> throw new IOException(label + ": not a WAD (starts with '" + magic + "')");
        };
        int count = buf.getInt(4);
        int dirOffset = buf.getInt(8);
        if (count < 0 || dirOffset < 0 || (long) dirOffset + 16L * count > bytes.length) {
            throw new IOException(label + ": directory runs past the end of the file");
        }
        List<Lump> lumps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = dirOffset + i * 16;
            int offset = buf.getInt(at);
            int size = buf.getInt(at + 4);
            String name = name(bytes, at + 8);
            if (offset < 0 || size < 0 || (long) offset + size > bytes.length) {
                // Name the entry by number: in a damaged directory the name is garbage too.
                throw new IOException(label + ": directory entry " + i + " of " + count
                        + " points outside the file - the WAD is damaged or truncated");
            }
            lumps.add(new Lump(i, name, offset, size));
        }
        return new WadFile(label, kind, buf, lumps);
    }

    /** An 8-byte, NUL-padded name, upper-cased the way the engine compares them. */
    static String name(byte[] bytes, int at) {
        int len = 0;
        while (len < 8 && bytes[at + len] != 0) len++;
        return new String(bytes, at, len, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    public List<Lump> lumps() {
        return lumps;
    }

    /** The LAST lump with this name, as the engine does: later lumps override. */
    public Optional<Lump> find(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (int i = lumps.size() - 1; i >= 0; i--) {
            if (lumps.get(i).name().equals(upper)) return Optional.of(lumps.get(i));
        }
        return Optional.empty();
    }

    /** A little-endian view of one lump's bytes. */
    public ByteBuffer bytes(Lump lump) {
        ByteBuffer slice = data.duplicate().position(lump.offset()).limit(lump.offset() + lump.size()).slice();
        return slice.order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * The maps in this WAD, in directory order.
     *
     * <p>A map is a marker lump (its name, usually {@code E1M1} or {@code MAP01},
     * but any name is legal) followed by {@code THINGS} in the binary formats, or
     * by {@code TEXTMAP} in UDMF. Looking for the follower rather than matching
     * the marker's name is what finds maps in PWADs that call theirs something
     * else.</p>
     */
    public List<MapEntry> maps() {
        List<MapEntry> maps = new ArrayList<>();
        for (int i = 0; i + 1 < lumps.size(); i++) {
            String next = lumps.get(i + 1).name();
            if (next.equals("THINGS")) {
                boolean hexen = mapLump(i, "BEHAVIOR").isPresent();
                maps.add(new MapEntry(lumps.get(i).name(), i, hexen ? MapFormat.HEXEN : MapFormat.DOOM));
            } else if (next.equals("TEXTMAP")) {
                maps.add(new MapEntry(lumps.get(i).name(), i, MapFormat.UDMF));
            }
        }
        return maps;
    }

    /** The lumps a binary map is made of. The first name outside this set ends the map. */
    private static final java.util.Set<String> MAP_LUMPS = java.util.Set.of(
            "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES", "SEGS", "SSECTORS", "NODES",
            "SECTORS", "REJECT", "BLOCKMAP", "BEHAVIOR", "SCRIPTS");

    public Optional<MapEntry> map(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return maps().stream().filter(m -> m.name().equals(upper)).findFirst();
    }

    /** The lump called {@code name} belonging to the map whose marker is at {@code markerIndex}. */
    Optional<Lump> mapLump(int markerIndex, String name) {
        for (int j = markerIndex + 1; j < lumps.size(); j++) {
            Lump lump = lumps.get(j);
            if (!MAP_LUMPS.contains(lump.name())) break;
            if (lump.name().equals(name)) return Optional.of(lump);
        }
        return Optional.empty();
    }

    /** Doom's binary layout, Hexen's (bigger THINGS and LINEDEFS), or text UDMF. */
    public enum MapFormat { DOOM, HEXEN, UDMF }

    public record MapEntry(String name, int markerIndex, MapFormat format) {}
}
