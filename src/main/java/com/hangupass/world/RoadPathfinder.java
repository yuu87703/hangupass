package com.hangupass.world;

import com.hangupass.Hangupass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径计算 — 在村庄之间找出合适的铺路路径。
 * 
 * 策略:
 * 1. 用 Bresenham 直线投影获取水平路径节点
 * 2. 对每个节点采样地表高度 (world surface)
 * 3. 平滑处理适应地形 (避免陡坡 > 45°)
 * 
 * TODO: Phase 2 实现完整地形自适应 (绕水/爬坡/桥梁)
 */
public class RoadPathfinder {

    /**
     * 计算从 start 到 end 的路径节点列表。
     * 目前返回直线路径的地表投影点。
     */
    public static List<BlockPos> calculatePath(BlockPos start, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();

        int dx = Math.abs(end.getX() - start.getX());
        int dz = Math.abs(end.getZ() - start.getZ());
        int sx = start.getX() < end.getX() ? 1 : -1;
        int sz = start.getZ() < end.getZ() ? 1 : -1;
        int err = dx - dz;

        int x = start.getX();
        int z = start.getZ();

        // Bresenham 直线 + 每隔 3 格取一个节点
        int step = 0;
        while (true) {
            if (step % 3 == 0) {
                // Y 坐标待后续地形采样确定
                path.add(new BlockPos(x, 0, z));
            }

            if (x == end.getX() && z == end.getZ()) break;

            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
            step++;
        }

        // 确保终点在路径中
        if (!path.contains(end)) {
            path.add(end);
        }

        return path;
    }

    /**
     * 对路径节点采样地表高度。
     * 需要在世界线程中执行。
     */
    public static List<BlockPos> sampleHeights(Level level, List<BlockPos> path) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : path) {
            // 获取地表最高非空气方块
            int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
            BlockPos surfacePos = new BlockPos(pos.getX(), surfaceY, pos.getZ());
            result.add(surfacePos);
        }
        return result;
    }

    /**
     * 平滑路径 — 限制相邻节点间的坡度。
     */
    public static List<BlockPos> smoothPath(List<BlockPos> path, int maxStepUp, int maxStepDown) {
        if (path.size() <= 2) return new ArrayList<>(path);

        List<BlockPos> result = new ArrayList<>();
        result.add(path.getFirst());

        for (int i = 1; i < path.size(); i++) {
            BlockPos prev = result.getLast();
            BlockPos current = path.get(i);

            int dy = current.getY() - prev.getY();

            if (dy > maxStepUp) {
                // 太陡→截断上升
                result.add(new BlockPos(current.getX(), prev.getY() + maxStepUp, current.getZ()));
            } else if (dy < -maxStepDown) {
                // 太陡→截断下降
                result.add(new BlockPos(current.getX(), prev.getY() - maxStepDown, current.getZ()));
            } else {
                result.add(current);
            }
        }

        return result;
    }
}
