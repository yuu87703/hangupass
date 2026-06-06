package com.hangupass;

import com.hangupass.command.HangupassCommand;
import com.hangupass.config.HangupassConfig;
import com.hangupass.world.BuildScheduler;
import com.hangupass.world.VillagePairManager;
import com.hangupass.world.VillageTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Hangupass implements ModInitializer {
    public static final String MOD_ID = "hangupass";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static HangupassConfig config;
    private static int tickCounter = 0;
    private static boolean scheduled = false;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);

        // 调试命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                HangupassCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path worldDir = server.getWorldPath(LevelResource.ROOT);
            config = HangupassConfig.load(worldDir);

            LOGGER.info("Server started. Build in {} ticks ({}s)...",
                    config.scanDelayTicks, config.scanDelayTicks / 20);
            scheduled = true;
            tickCounter = 0;
        });

        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        // 调度器 tick (分帧建造)
        BuildScheduler.getInstance().tick();

        if (!scheduled) return;
        tickCounter++;
        if (tickCounter >= config.scanDelayTicks) {
            scheduled = false;
            runBuild(server);
        }
    }

    private void runBuild(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.error("Overworld not found, aborting");
            return;
        }

        LOGGER.info("========== Hangupass Build ==========");

        // Phase 1: 扫描村庄 (同步)
        VillageTracker.scanAllVillages(server);
        var villages = VillageTracker.getDiscoveredVillages();

        if (villages.isEmpty()) {
            LOGGER.warn("No villages found. Explore more area and restart.");
            return;
        }

        // Phase 2: 配对
        VillagePairManager manager = new VillagePairManager(villages, config);
        var roads = manager.pairNearestNeighbor();

        // Phase 3: 分帧铺路 + 关隘
        int perTick = Math.max(1, config.buildSegmentsPerTick);
        BuildScheduler.scheduleRoadBuilding(overworld, roads, perTick);

        LOGGER.info("========== Hangupass: scheduled ==========");
    }
}
