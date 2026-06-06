package com.hangupass.command;

import com.hangupass.Hangupass;
import com.hangupass.config.HangupassConfig;
import com.hangupass.world.BuildScheduler;
import com.hangupass.world.VillagePairManager;
import com.hangupass.world.VillageTracker;
import com.mojang.brigadier.CommandDispatcher;

import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class HangupassCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hangupass")
                .requires(src -> src.hasPermission(2)) // OP only

                // /hangupass status
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))

                // /hangupass rescan
                .then(Commands.literal("rescan")
                        .executes(ctx -> rescan(ctx.getSource())))

                // /hangupass reload
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))

                // /hangupass
                .executes(ctx -> help(ctx.getSource()))
        );
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("§6=== Hangupass Commands ==="), false);
        src.sendSuccess(() -> Component.literal("§e/hangupass status §7- 当前状态"), false);
        src.sendSuccess(() -> Component.literal("§e/hangupass rescan §7- 重新扫描并建造"), false);
        src.sendSuccess(() -> Component.literal("§e/hangupass reload §7- 重载配置文件"), false);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        boolean scanned = VillageTracker.isScanned();
        int villages = VillageTracker.getDiscoveredVillages().size();
        boolean building = BuildScheduler.getInstance().isRunning();
        int remaining = BuildScheduler.getInstance().getRemainingTasks();

        src.sendSuccess(() -> Component.literal("§6=== Hangupass Status ==="), false);
        src.sendSuccess(() -> Component.literal("§7Scanned: §" + (scanned ? "a✔" : "c✘")), false);
        src.sendSuccess(() -> Component.literal("§7Villages found: §b" + villages), false);
        src.sendSuccess(() -> Component.literal("§7Building: §" + (building ? "e⏳" : "a✔") + " §7(" + remaining + " tasks left)"), false);

        if (villages > 0) {
            src.sendSuccess(() -> Component.literal("§7Village list:"), false);
            VillageTracker.getDiscoveredVillages().forEach(v ->
                    src.sendSuccess(() -> Component.literal("§8  - §f" + v.name()
                            + " §7@ " + v.pos().toShortString()), false)
            );
        }

        return 1;
    }

    private static int rescan(CommandSourceStack src) {
        if (BuildScheduler.getInstance().isRunning()) {
            src.sendFailure(Component.literal("§cBuild already in progress, wait for it to finish"));
            return 0;
        }

        ServerLevel overworld = src.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            src.sendFailure(Component.literal("§cOverworld not found"));
            return 0;
        }

        src.sendSuccess(() -> Component.literal("§eStarting village rescan..."), true);

        // 强制同步扫描
        VillageTracker.scanAllVillages(src.getServer());
        var villages = VillageTracker.getDiscoveredVillages();

        if (villages.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§cNo villages found. Explore more area."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("§aFound §b" + villages.size() + " §avillages, building roads..."), false);

        // 加载配置
        Path worldDir = src.getServer().getWorldPath(LevelResource.ROOT);
        HangupassConfig config = HangupassConfig.load(worldDir);

        // 配对 + 分帧建造
        var manager = new VillagePairManager(villages, config);
        var roads = manager.pairNearestNeighbor();
        BuildScheduler.scheduleRoadBuilding(overworld, roads, config.buildSegmentsPerTick);

        src.sendSuccess(() -> Component.literal("§a✅ Scheduled " + roads.size() + " road segments"), true);
        return 1;
    }

    private static int reload(CommandSourceStack src) {
        Path worldDir = src.getServer().getWorldPath(LevelResource.ROOT);
        HangupassConfig config = HangupassConfig.load(worldDir);

        // 更新 VillageTracker 关键词
        VillageTracker.setVillageKeywords(Set.copyOf(config.extraVillageKeywords));

        src.sendSuccess(() -> Component.literal("§a✅ Config reloaded"), true);
        return 1;
    }
}
