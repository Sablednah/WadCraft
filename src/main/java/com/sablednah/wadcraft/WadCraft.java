package com.sablednah.wadcraft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sablednah.wadcraft.neoforge.BuildQueue;
import com.sablednah.wadcraft.neoforge.WadCommands;
import com.sablednah.wadcraft.neoforge.WadLibrary;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * WadCraft: Doom maps as Minecraft structures.
 *
 * <p>Server-side only. Everything it makes is ordinary blocks, so a vanilla
 * client sees the result and needs nothing installed. Other mods use it through
 * {@link com.sablednah.wadcraft.api.WadCraftApi}.</p>
 */
@Mod(WadCraft.MODID)
public final class WadCraft {

    public static final String MODID = "wadcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WadCraft(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> WadCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> WadLibrary.onServerStarted());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> BuildQueue.tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> BuildQueue.clear());
    }
}
