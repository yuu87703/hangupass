package com.hangupass.world;

import com.hangupass.Hangupass;
import com.hangupass.config.HangupassConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * 村庄配对 + 路径管理 + 道路 & 关隘建造。
 * 最近邻贪心连接村庄，然后铺路并在中段建关隘。
 */
public class VillagePairManager {
    private final List<VillageTracker.VillageInfo> villages;
    private final List<RoadSegment> roads = new ArrayList<>();
    private final HangupassConfig config;

    public VillagePairManager(List<VillageTracker.VillageInfo> villages, HangupassConfig config) {
        this.villages = new ArrayList<>(villages);
        this.config = config;
    }

    /**
     * 最近邻贪心配对。
     */
    public List<RoadSegment> pairNearestNeighbor() {
        roads.clear();
        if (villages.size() < 2) {
            Hangupass.LOGGER.info("Need at least 2 villages to pair, found {}", villages.size());
            return roads;
        }

        Set<Integer> visited = new HashSet<>();
        List<Integer> ordered = new ArrayList<>();

        ordered.add(0);
        visited.add(0);

        while (visited.size() < villages.size()) {
            int last = ordered.getLast();
            BlockPos lastPos = villages.get(last).pos();

            int nearest = -1;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < villages.size(); i++) {
                if (visited.contains(i)) continue;
                double dist = lastPos.distSqr(villages.get(i).pos());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = i;
                }
            }

            if (nearest >= 0) {
                ordered.add(nearest);
                visited.add(nearest);
            }
        }

        for (int i = 0; i < ordered.size() - 1; i++) {
            int a = ordered.get(i);
            int b = ordered.get(i + 1);
            List<BlockPos> path = RoadPathfinder.calculatePath(
                    villages.get(a).pos(), villages.get(b).pos());
            roads.add(new RoadSegment(villages.get(a), villages.get(b), path));
        }

        return roads;
    }

    /**
     * 建造所有道路 + 关隘。
     */
    public void buildAll(ServerLevel level) {
        if (roads.isEmpty()) {
            Hangupass.LOGGER.warn("No road segments to build");
            return;
        }

        RoadBuilder builder = new RoadBuilder(level);
        GateStructure gateBuilder = new GateStructure(level);

        for (RoadSegment seg : roads) {
            RoadStyle style = RoadStyle.forVillagePair(seg.from(), seg.to());

            // 1. 铺路
            builder.buildRoad(seg.from(), seg.to(), seg.path(), style);

            // 2. 建关隘 (距离够远且配置开启)
            if (config.buildGates) {
                double distance = Math.sqrt(seg.from().pos().distSqr(seg.to().pos()));
                if (distance >= config.gateMinDistance) {
                    gateBuilder.buildAtRoadMidpoint(seg.path(), style);
                }
            }
        }

        Hangupass.LOGGER.info("=== Hangupass build complete ===");
        Hangupass.LOGGER.info("Total blocks placed: {}", builder.getTotalBlocksPlaced());
    }

    public List<RoadSegment> getRoads() {
        return Collections.unmodifiableList(roads);
    }

    /**
     * 主入口: 从已知村庄列表执行完整建造流程。
     */
    public static void buildFromVillages(ServerLevel level,
                                          List<VillageTracker.VillageInfo> villages,
                                          HangupassConfig config) {
        if (villages.size() < 2) {
            Hangupass.LOGGER.info("Need ≥2 villages for roads, found {}", villages.size());
            return;
        }

        Hangupass.LOGGER.info("========== Hangupass: build start ==========");
        VillagePairManager manager = new VillagePairManager(villages, config);
        manager.pairNearestNeighbor();
        manager.buildAll(level);
    }

    public record RoadSegment(
            VillageTracker.VillageInfo from,
            VillageTracker.VillageInfo to,
            List<BlockPos> path
    ) {}
}
