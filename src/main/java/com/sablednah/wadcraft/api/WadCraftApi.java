package com.sablednah.wadcraft.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import com.sablednah.wadcraft.build.BuildOptions;
import com.sablednah.wadcraft.build.VoxelModel;
import com.sablednah.wadcraft.build.Voxelizer;
import com.sablednah.wadcraft.neoforge.WadLibrary;
import com.sablednah.wadcraft.neoforge.Builds;
import com.sablednah.wadcraft.wad.DoomMap;
import com.sablednah.wadcraft.wad.TextureColours;
import com.sablednah.wadcraft.wad.WadFile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The whole of WadCraft for another mod, in one class.
 *
 * <pre>{@code
 * WadFile wad = WadCraftApi.read(WadCraftApi.wadFolder().resolve("freedoom1.wad"));
 * WadCraftApi.build(level, wad, "E1M1", pos, WadCraftApi.turnsToFace(wad, "E1M1", player.getYRot()),
 *         BuildOptions.defaults())
 *     .thenAccept(result -> ...);
 * }</pre>
 *
 * <p>Call it from the server thread. Reading the map and working out the blocks
 * happen in the background; the blocks are then placed a slice per tick, after
 * any build already queued. The future completes on the server thread, and
 * completes exceptionally if the map cannot be read or the build is cancelled.</p>
 *
 * <p>The anchor is where Doom's player 1 start lands, standing on its floor. A
 * map with no player start is anchored at its centre, on its lowest floor.</p>
 */
public final class WadCraftApi {

    /** The server's {@code wads/} folder. */
    public static Path wadFolder() {
        return WadLibrary.folder();
    }

    /** Read a WAD from anywhere. Cached by path, size and modification time. */
    public static WadFile read(Path path) throws IOException {
        return WadLibrary.load(path);
    }

    /** Read a WAD carried in memory, for example one shipped inside a mod's jar. */
    public static WadFile read(String label, byte[] bytes) throws IOException {
        return WadFile.read(label, bytes);
    }

    /** The block layout without placing anything: size, block count, height range. */
    public static VoxelModel model(WadFile wad, String map, BuildOptions options) throws IOException {
        return Voxelizer.build(DoomMap.read(wad, map), options);
    }

    /**
     * Quarter turns that make the map's start face {@code yaw} (a Minecraft yaw,
     * as {@code Entity.getYRot()} gives), snapped to the nearest of the four
     * directions.
     */
    public static int turnsToFace(int doomStartAngle, float yaw) {
        // Doom 0 = east and 90 = north; Minecraft yaw 0 = south, 90 = west, -90 = east.
        double startYaw = -90.0 - doomStartAngle;
        return Math.floorMod((int) Math.round((yaw - startYaw) / 90.0), 4);
    }

    public static int turnsToFace(WadFile wad, String map, float yaw) throws IOException {
        int angle = DoomMap.read(wad, map).playerStart().map(DoomMap.Thing::angle).orElse(90);
        return turnsToFace(angle, yaw);
    }

    /** Build, taking colours from the WAD (or an IWAD in the wads folder, for a bare PWAD). */
    public static CompletableFuture<BuildResult> build(ServerLevel level, WadFile wad, String map,
            BlockPos anchor, int quarterTurns, BuildOptions options) {
        return build(level, wad, map, anchor, quarterTurns, options, null, null);
    }

    /** Build with colours read from WADs the caller chose, earliest first. */
    public static CompletableFuture<BuildResult> build(ServerLevel level, WadFile wad, String map,
            BlockPos anchor, int quarterTurns, BuildOptions options, TextureColours colours) {
        return Builds.start(level, wad, map, anchor, quarterTurns, options, colours, null, null);
    }

    /**
     * The full form: {@code owner} (may be null) gets this as their undoable
     * build, and {@code progress} (may be null) hears 25, 50 and 75 percent.
     */
    public static CompletableFuture<BuildResult> build(ServerLevel level, WadFile wad, String map,
            BlockPos anchor, int quarterTurns, BuildOptions options, UUID owner, IntConsumer progress) {
        return Builds.start(level, wad, map, anchor, quarterTurns, options, null, owner, progress);
    }

    private WadCraftApi() {}
}
