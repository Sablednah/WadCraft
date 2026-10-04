package com.sablednah.wadcraft.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.sablednah.wadcraft.WadCraft;
import com.sablednah.wadcraft.wad.TextureColours;
import com.sablednah.wadcraft.wad.WadFile;

import net.neoforged.fml.loading.FMLPaths;

/**
 * The server's {@code wads/} folder, and what has been read from it.
 *
 * <p>Only files directly in that folder can be named — a command never takes a
 * path — so nothing outside it can be read by typing a clever name.</p>
 */
public final class WadLibrary {

    private static final String README = """
            Put Doom WAD files here, then build a map in-game with
              /wadcraft build <file> <map>

            Freedoom (https://freedoom.github.io) is free to use and share.
            The commercial Doom WADs work too, but they are not yours to give away:
            keep them on your own server.

            A PWAD (a single custom map) that brings no textures of its own takes
            its colours from an IWAD in this folder, so keep one here as well.
            """;

    private record Cached(long size, long modified, WadFile wad) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();
    private static volatile BlockPalette palette;

    public static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve("wads");
    }

    public static Path configFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(WadCraft.MODID);
    }

    public static void onServerStarted() {
        try {
            Files.createDirectories(folder());
            Path readme = folder().resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, README);
        } catch (IOException e) {
            WadCraft.LOGGER.warn("Could not create {}: {}", folder(), e.toString());
        }
        palette = BlockPalette.load(configFolder());
        WadCraft.LOGGER.info("WadCraft: {} WAD file(s) in {}", list().size(), folder());
    }

    public static BlockPalette palette() {
        BlockPalette p = palette;
        if (p == null) palette = p = BlockPalette.load(configFolder());
        return p;
    }

    /** File names of the WADs in the folder, sorted. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(folder())) return names;
        try (Stream<Path> s = Files.list(folder())) {
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".wad"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        } catch (IOException e) {
            WadCraft.LOGGER.warn("Could not list {}: {}", folder(), e.toString());
        }
        return names;
    }

    /** A file in the folder by name, ignoring case. Never a path. */
    public static Optional<Path> resolve(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return Optional.empty();
        for (String n : list()) {
            if (n.equalsIgnoreCase(name) || n.equalsIgnoreCase(name + ".wad")) return Optional.of(folder().resolve(n));
        }
        return Optional.empty();
    }

    public static WadFile load(Path path) throws IOException {
        long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
        Cached c = CACHE.get(path);
        if (c != null && c.size() == size && c.modified() == modified) return c.wad();
        WadFile wad = WadFile.read(path);
        CACHE.put(path, new Cached(size, modified, wad));
        return wad;
    }

    /**
     * Where a WAD's colours come from. Its own palette and textures when it has
     * them; otherwise an IWAD from the folder of the same game (one with ExMy
     * maps for an ExMy map, MAPxx for MAPxx), with the WAD's own entries on top.
     */
    public static TextureColours colours(WadFile wad, String mapName) {
        boolean selfContained = wad.find("PLAYPAL").isPresent()
                && (wad.find("TEXTURE1").isPresent() || wad.find("TEXTURE2").isPresent());
        if (selfContained) return TextureColours.read(List.of(wad));
        boolean doom2Style = mapName.toUpperCase(Locale.ROOT).startsWith("MAP");
        WadFile base = null;
        for (String name : list()) {
            try {
                WadFile candidate = load(folder().resolve(name));
                if (candidate.kind() != WadFile.Kind.IWAD || candidate == wad) continue;
                boolean matches = candidate.map(doom2Style ? "MAP01" : "E1M1").isPresent();
                if (matches || base == null) base = candidate;
                if (matches) break;
            } catch (IOException ignored) {
                // A damaged file in the folder is not this build's problem.
            }
        }
        return TextureColours.read(base == null ? List.of(wad) : List.of(base, wad));
    }

    private WadLibrary() {}
}
