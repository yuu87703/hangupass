package com.hangupass.world;

import com.hangupass.Hangupass;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Consumer;

/**
 * 分帧建造调度器。
 * 将大型任务分批执行，每 tick 处理一小部分，避免卡顿。
 */
public class BuildScheduler {
    private final Queue<Runnable> pendingTasks = new LinkedList<>();
    private boolean running = false;
    private int tasksPerTick = 16;
    private int currentBatchItems = 0;

    /**
     * 把一个操作拆成多个子任务，每 tick 执行一部分。
     */
    public void scheduleBatch(String name, List<Runnable> tasks, int perTick) {
        this.tasksPerTick = perTick;
        pendingTasks.addAll(tasks);
        if (!running) {
            running = true;
            Hangupass.LOGGER.info("Scheduled '{}': {} tasks, {} per tick", name, tasks.size(), perTick);
        }
    }

    /**
     * 注册到 ServerTick 每帧执行。
     */
    public void tick() {
        if (!running || pendingTasks.isEmpty()) {
            running = false;
            return;
        }

        currentBatchItems = 0;
        while (!pendingTasks.isEmpty() && currentBatchItems < tasksPerTick) {
            Runnable task = pendingTasks.poll();
            try {
                task.run();
            } catch (Exception e) {
                Hangupass.LOGGER.error("BuildScheduler task failed: {}", e.getMessage());
            }
            currentBatchItems++;
        }

        if (pendingTasks.isEmpty()) {
            running = false;
            Hangupass.LOGGER.info("BuildScheduler: all tasks complete");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getRemainingTasks() {
        return pendingTasks.size();
    }

    // === 全局单例 ===
    private static final BuildScheduler INSTANCE = new BuildScheduler();

    public static BuildScheduler getInstance() {
        return INSTANCE;
    }

    /**
     * 将村庄扫描拆成多个区块批次。
     */
    public static void scheduleVillageScan(MinecraftServer server,
                                            int radiusChunks, int chunksPerTick) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        List<Runnable> tasks = VillageTracker.createScanTasks(overworld, radiusChunks);
        getInstance().scheduleBatch("village-scan", tasks, chunksPerTick);
    }

    /**
     * 将道路建造拆成多个路段批次。
     */
    public static void scheduleRoadBuilding(ServerLevel level,
                                             List<VillagePairManager.RoadSegment> roads,
                                             int segmentsPerTick) {
        List<Runnable> tasks = new ArrayList<>();
        GateStructure gateBuilder = new GateStructure(level);

        for (VillagePairManager.RoadSegment seg : roads) {
            RoadStyle style = RoadStyle.forVillagePair(seg.from(), seg.to());
            double dist = Math.sqrt(seg.from().pos().distSqr(seg.to().pos()));

            tasks.add(() -> {
                RoadBuilder builder = new RoadBuilder(level);
                builder.buildRoad(seg.from(), seg.to(), seg.path(), style);

                // 关隘 (自动选尺寸)
                if (dist >= 128) {
                    gateBuilder.buildAtRoadMidpoint(seg.path(), style, dist);
                }
            });
        }

        getInstance().scheduleBatch("road-build", tasks, segmentsPerTick);
    }
}
