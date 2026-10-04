package com.sablednah.wadcraft.wad;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The average colour of every wall texture and flat a WAD defines.
 *
 * <p>This is what lets a map be built from any WAD without a hand-written table:
 * each texture's average colour is matched to the nearest Minecraft block. The
 * formats are id's ({@code r_data.c}):</p>
 * <ul>
 *   <li>{@code PLAYPAL}: 14 palettes of 256 RGB triples; the first is the normal one.</li>
 *   <li>Flats: raw 64×64 palette indexes between {@code F_START}/{@code F_END}
 *       (PWADs use {@code FF_START}/{@code FF_END}).</li>
 *   <li>Wall textures: {@code TEXTURE1}/{@code TEXTURE2} list each texture as
 *       patches from {@code PNAMES}; a patch is a column-post picture.</li>
 * </ul>
 *
 * <p>An average over every opaque patch pixel, ignoring where each patch sits
 * and what overlaps it, is not the texture's true average — and does not need
 * to be. It only has to pick a block that reads as the same material.</p>
 */
public final class TextureColours {

    private final Map<String, Integer> walls = new HashMap<>();
    private final Map<String, Integer> flats = new HashMap<>();

    /** Wall texture average as 0xRRGGBB. */
    public Optional<Integer> wall(String name) {
        return Optional.ofNullable(walls.get(name.toUpperCase(Locale.ROOT)));
    }

    public Optional<Integer> flat(String name) {
        return Optional.ofNullable(flats.get(name.toUpperCase(Locale.ROOT)));
    }

    public int wallCount() {
        return walls.size();
    }

    public int flatCount() {
        return flats.size();
    }

    /**
     * Read from one or more WADs, earliest first. A later WAD's entries win,
     * which is how a PWAD's own textures override its IWAD's — and how a PWAD
     * with no palette at all borrows the IWAD's.
     */
    public static TextureColours read(List<WadFile> wads) {
        TextureColours colours = new TextureColours();
        int[] palette = null;
        for (WadFile wad : wads) {
            int[] own = palette(wad);
            if (own != null) palette = own;
        }
        if (palette == null) return colours; // nothing to colour with
        for (WadFile wad : wads) {
            colours.readFlats(wad, palette);
            colours.readWalls(wad, palette);
        }
        return colours;
    }

    private static int[] palette(WadFile wad) {
        Optional<WadFile.Lump> lump = wad.find("PLAYPAL");
        if (lump.isEmpty() || lump.get().size() < 768) return null;
        ByteBuffer b = wad.bytes(lump.get());
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            palette[i] = ((b.get() & 0xFF) << 16) | ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
        }
        return palette;
    }

    private void readFlats(WadFile wad, int[] palette) {
        boolean inFlats = false;
        for (WadFile.Lump lump : wad.lumps()) {
            String n = lump.name();
            if (n.equals("F_START") || n.equals("FF_START")) {
                inFlats = true;
            } else if (n.equals("F_END") || n.equals("FF_END")) {
                inFlats = false;
            } else if (inFlats && lump.size() >= 4096) {
                Avg avg = new Avg();
                ByteBuffer b = wad.bytes(lump);
                for (int i = 0; i < 4096; i++) avg.add(palette[b.get(i) & 0xFF]);
                if (avg.count > 0) flats.put(n, avg.rgb());
            }
        }
    }

    private void readWalls(WadFile wad, int[] palette) {
        Optional<WadFile.Lump> pnamesLump = wad.find("PNAMES");
        if (pnamesLump.isEmpty()) return;
        ByteBuffer pn = wad.bytes(pnamesLump.get());
        int count = pn.getInt();
        String[] pnames = new String[Math.max(0, count)];
        byte[] raw = new byte[8];
        for (int i = 0; i < pnames.length && pn.remaining() >= 8; i++) {
            pn.get(raw);
            pnames[i] = WadFile.name(raw, 0);
        }
        Map<String, Avg> patchCache = new HashMap<>();
        for (String table : new String[] {"TEXTURE1", "TEXTURE2"}) {
            Optional<WadFile.Lump> lump = wad.find(table);
            if (lump.isEmpty()) continue;
            ByteBuffer t = wad.bytes(lump.get());
            int numTextures = t.getInt(0);
            for (int i = 0; i < numTextures; i++) {
                int at = t.getInt(4 + i * 4);
                if (at < 0 || at + 22 > t.limit()) continue;
                byte[] nameBytes = new byte[8];
                t.get(at, nameBytes);
                String name = WadFile.name(nameBytes, 0);
                int patchCount = t.getShort(at + 20) & 0xFFFF;
                Avg avg = new Avg();
                for (int p = 0; p < patchCount; p++) {
                    int pAt = at + 22 + p * 10;
                    if (pAt + 10 > t.limit()) break;
                    int patchIndex = t.getShort(pAt + 4) & 0xFFFF;
                    if (patchIndex >= pnames.length || pnames[patchIndex] == null) continue;
                    Avg patch = patchCache.computeIfAbsent(pnames[patchIndex],
                            pname -> patchAverage(wad, pname, palette));
                    avg.merge(patch);
                }
                if (avg.count > 0) walls.put(name, avg.rgb());
            }
        }
    }

    /** Every opaque pixel of a picture-format patch. */
    private static Avg patchAverage(WadFile wad, String name, int[] palette) {
        Avg avg = new Avg();
        Optional<WadFile.Lump> lump = wad.find(name);
        if (lump.isEmpty() || lump.get().size() < 8) return avg;
        ByteBuffer b = wad.bytes(lump.get());
        int width = b.getShort(0) & 0xFFFF;
        if (8 + width * 4 > b.limit()) return avg;
        for (int col = 0; col < width; col++) {
            int at = b.getInt(8 + col * 4);
            // A post: topdelta, length, padding, pixels, padding; 0xFF ends the column.
            while (at >= 0 && at < b.limit()) {
                int top = b.get(at) & 0xFF;
                if (top == 0xFF) break;
                if (at + 1 >= b.limit()) break;
                int len = b.get(at + 1) & 0xFF;
                int px = at + 3;
                if (px + len > b.limit()) break;
                for (int i = 0; i < len; i++) avg.add(palette[b.get(px + i) & 0xFF]);
                at = px + len + 1;
            }
        }
        return avg;
    }

    private static final class Avg {
        long r, g, b, count;

        void add(int rgb) {
            r += (rgb >> 16) & 0xFF;
            g += (rgb >> 8) & 0xFF;
            b += rgb & 0xFF;
            count++;
        }

        void merge(Avg o) {
            r += o.r;
            g += o.g;
            b += o.b;
            count += o.count;
        }

        int rgb() {
            return (int) ((r / count) << 16 | (g / count) << 8 | (b / count));
        }
    }
}
