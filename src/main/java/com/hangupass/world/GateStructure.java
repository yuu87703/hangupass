package com.hangupass.world;

import com.hangupass.Hangupass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * 古代关隘结构生成器。
 * 三种尺寸变体，根据村庄距离自动选择。
 *
 * 结构布局 (道路南北走向):
 *   x: 沿城墙方向 (宽)
 *   z: 沿道路方向 (深)
 *   y: 高
 */
public class GateStructure {
    private final ServerLevel level;
    private final Random random = new Random();

    public enum GateSize {
        /** 哨亭 — 5宽×3深×4高，村庄距 < 256 */
        SMALL(5, 3, 4),
        /** 关隘 — 9宽×5深×7高，默认 */
        MEDIUM(9, 5, 7),
        /** 雄关 — 13宽×7深×9高，村庄距 ≥ 512 */
        LARGE(13, 7, 9);

        public final int width, depth, height;

        GateSize(int w, int d, int h) {
            this.width = w; this.depth = d; this.height = h;
        }

        public static GateSize forDistance(double dist) {
            if (dist >= 512) return LARGE;
            if (dist >= 256) return MEDIUM;
            return SMALL;
        }
    }

    public GateStructure(ServerLevel level) {
        this.level = level;
    }

    /**
     * 在道路中段放置关隘，自动选尺寸。
     */
    public Optional<BlockPos> buildAtRoadMidpoint(
            List<BlockPos> roadPath, RoadStyle style, double villageDistance) {
        if (roadPath.size() < 3) return Optional.empty();

        int midIndex = roadPath.size() / 2;
        BlockPos midPos = roadPath.get(midIndex);
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                midPos.getX(), midPos.getZ());
        BlockPos gatePos = new BlockPos(midPos.getX(), surfaceY, midPos.getZ());
        Direction roadDir = getRoadDirection(roadPath, midIndex);
        GateSize size = GateSize.forDistance(villageDistance);

        Hangupass.LOGGER.info("Building {} gate at {} (dist={})",
                size.name().toLowerCase(), gatePos.toShortString(), (int)villageDistance);
        build(gatePos, roadDir, style, size);
        return Optional.of(gatePos);
    }

    /**
     * 建造指定尺寸的关隘。
     */
    public void build(BlockPos center, Direction roadDirection, RoadStyle style, GateSize size) {
        int[][][] bp = generateBlueprint(size);
        boolean roadIsNorthSouth = (roadDirection == Direction.NORTH || roadDirection == Direction.SOUTH);

        int blocksPlaced = 0;
        for (int z = 0; z < size.depth; z++) {
            for (int y = 0; y < size.height; y++) {
                for (int x = 0; x < size.width; x++) {
                    int blockId = bp[z][y][x];
                    if (blockId == AIR) continue;

                    int worldX, worldZ;
                    if (roadIsNorthSouth) {
                        worldX = center.getX() + (x - size.width / 2);
                        worldZ = center.getZ() + (z - size.depth / 2);
                    } else {
                        worldX = center.getX() + (z - size.depth / 2);
                        worldZ = center.getZ() + (x - size.width / 2);
                    }
                    BlockPos pos = new BlockPos(worldX, center.getY() + y, worldZ);

                    BlockState state = resolveBlock(blockId, style, pos);
                    if (state != null) {
                        // 不覆盖已有建筑
                        if (isProtected(pos)) continue;
                        level.setBlock(pos, state, 3);
                        blocksPlaced++;
                    }
                }
            }
        }
        Hangupass.LOGGER.info("Gate {} placed: {} blocks at {}", size.name().toLowerCase(),
                blocksPlaced, center.toShortString());
    }

    // ============ 蓝图生成 ============

    private static final int AIR = 0;
    private static final int WALL = 1;
    private static final int WALL_CRACKED = 2;
    private static final int CHISELED = 3;
    private static final int SLAB_TOP = 4;
    private static final int SLAB_BOTTOM = 5;
    private static final int LANTERN = 6;
    private static final int ROAD = 7;
    private static final int CAMPFIRE = 8;
    private static final int FENCE = 9;

    private int[][][] generateBlueprint(GateSize size) {
        return switch (size) {
            case SMALL -> generateSmall();
            case MEDIUM -> generateMedium();
            case LARGE -> generateLarge();
        };
    }

    /** 哨亭: 5宽×3深×4高 */
    private int[][][] generateSmall() {
        int W = 5, D = 3, H = 4;
        int[][][] bp = new int[D][H][W];
        int gateL = 1, gateR = 3; // x=1,2,3 开口

        // y=0 地基
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][0][x] = (x >= gateL && x <= gateR && z == 1) ? ROAD : WALL;

        // y=1 门洞
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][1][x] = (x >= gateL && x <= gateR && z == 1) ? AIR : WALL;

        // y=2 门洞顶
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][2][x] = (x < gateL || x > gateR) ? WALL : AIR;
        bp[1][2][gateL-1] = LANTERN;
        bp[1][2][gateR+1] = LANTERN;

        // y=3 垛口
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][3][x] = (x == 0 || x == W-1 || z == 0 || z == D-1) ? SLAB_TOP : AIR;

        return bp;
    }

    /** 关隘: 9宽×5深×7高 (现有) */
    private int[][][] generateMedium() {
        int W = 9, D = 5, H = 7;
        int[][][] bp = new int[D][H][W];
        int gateL = 2, gateR = 6;

        // y=0 地基
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][0][x] = (x >= gateL && x <= gateR && z >= 1 && z <= 3) ? ROAD : WALL;

        // y=1 门洞层
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][1][x] = (x >= gateL && x <= gateR && z >= 1 && z <= 3) ? AIR
                        : (z == 0 || z == 4 ? CHISELED : WALL);

        // y=2 门洞层
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][2][x] = (x >= gateL && x <= gateR && z >= 1 && z <= 3) ? AIR : WALL;

        // y=3 封顶
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][3][x] = (z == 0 || z == 4) ? WALL_CRACKED : WALL;

        // y=4 城楼基座
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][4][x] = (x >= 1 && x <= 7 && z >= 1 && z <= 3) ? AIR : WALL;

        // y=5 城楼墙+垛
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                if (x >= 2 && x <= 6 && z >= 1 && z <= 3) bp[z][5][x] = AIR;
                else if ((x == 0 || x == W-1 || z == 0 || z == D-1) && (x%2==0 || z%2==0)) bp[z][5][x] = SLAB_TOP;
                else if (x == 1 || x == W-2) bp[z][5][x] = FENCE;

        // y=6 城楼顶
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                if (x >= 2 && x <= 6 && z >= 1 && z <= 3) bp[z][6][x] = AIR;
                else if (z == 2 && x == 0) bp[z][6][x] = CAMPFIRE;
                else if ((x == 0 || x == W-1 || z == 0 || z == D-1) && (x%2==0)) bp[z][6][x] = SLAB_TOP;

        // 灯笼
        bp[1][2][gateL-1] = LANTERN; bp[2][2][gateL-1] = LANTERN;
        bp[1][2][gateR+1] = LANTERN; bp[2][2][gateR+1] = LANTERN;

        return bp;
    }

    /** 雄关: 13宽×7深×9高 */
    private int[][][] generateLarge() {
        int W = 13, D = 7, H = 9;
        int[][][] bp = new int[D][H][W];
        int gateL = 4, gateR = 8; // 5格开口 (x=4..8)

        // y=0 地基 + 路面
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][0][x] = (x >= gateL && x <= gateR && z >= 1 && z <= 5) ? ROAD : WALL;

        // y=1~2 门洞 (双层高)
        for (int y = 1; y <= 2; y++)
            for (int z = 0; z < D; z++)
                for (int x = 0; x < W; x++)
                    bp[z][y][x] = (x >= gateL && x <= gateR && z >= 1 && z <= 5) ? AIR
                            : (z == 0 || z == D-1 ? CHISELED : WALL);

        // y=3 拱顶
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][3][x] = WALL;

        // y=4 下层城楼
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][4][x] = (x >= 2 && x <= W-3 && z >= 1 && z <= D-2) ? AIR : WALL;

        // y=5 下层垛口
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                if (x >= 3 && x <= W-4 && z >= 2 && z <= D-3) bp[z][5][x] = AIR;
                else if ((x == 0 || x == W-1 || z == 0 || z == D-1) && (x%2==0)) bp[z][5][x] = SLAB_TOP;

        // y=6 上层城楼 (缩进)
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                bp[z][6][x] = (x >= 3 && x <= W-4 && z >= 2 && z <= D-3) ? AIR : WALL;

        // y=7 上层墙
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                if (x >= 4 && x <= W-5 && z >= 2 && z <= D-3) bp[z][7][x] = AIR;
                else bp[z][7][x] = WALL_CRACKED;

        // y=8 顶层: 双烽火台
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++) {
                if (x >= 4 && x <= W-5 && z >= 2 && z <= D-3) bp[z][8][x] = AIR;
                else if ((x == 0 || x == W-1) && (z == 0 || z == D-1)) bp[z][8][x] = CAMPFIRE;
                else if (x == 0 || x == W-1 || z == 0 || z == D-1) bp[z][8][x] = SLAB_TOP;
            }

        // 灯笼: 门洞两侧 x3
        for (int z = 1; z <= 3; z++) {
            bp[z][2][gateL-1] = LANTERN;
            bp[z][2][gateR+1] = LANTERN;
        }

        return bp;
    }

    // ============ 方块解析 ============

    private BlockState resolveBlock(int blockId, RoadStyle style, BlockPos pos) {
        return switch (blockId) {
            case WALL -> style.mainBlock().defaultBlockState();
            case WALL_CRACKED -> altOrMain(style, 0.5f);
            case CHISELED -> chiseledOrMain(style);
            case SLAB_TOP -> style.edgeBlock().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);
            case SLAB_BOTTOM -> style.edgeBlock().defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
            case FENCE -> style.wallBlock().defaultBlockState();
            case LANTERN -> Blocks.LANTERN.defaultBlockState()
                    .setValue(LanternBlock.HANGING, true);
            case ROAD -> style.mainBlock().defaultBlockState();
            case CAMPFIRE -> random.nextBoolean()
                    ? Blocks.CAMPFIRE.defaultBlockState()
                    : Blocks.SOUL_CAMPFIRE.defaultBlockState();
            default -> null;
        };
    }

    private BlockState altOrMain(RoadStyle style, float altChance) {
        if (random.nextFloat() < altChance) return style.altBlock().defaultBlockState();
        return style.mainBlock().defaultBlockState();
    }

    private BlockState chiseledOrMain(RoadStyle style) {
        Block main = style.mainBlock();
        if (main == Blocks.STONE_BRICKS) return Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        if (main == Blocks.CUT_SANDSTONE) return Blocks.CHISELED_SANDSTONE.defaultBlockState();
        if (main == Blocks.POLISHED_DEEPSLATE) return Blocks.CHISELED_DEEPSLATE.defaultBlockState();
        return main.defaultBlockState();
    }

    // ============ 辅助 ============

    private Direction getRoadDirection(List<BlockPos> path, int midIndex) {
        if (path.size() < 2) return Direction.NORTH;
        int idx = Math.min(midIndex + 1, path.size() - 1);
        BlockPos a = path.get(Math.max(0, midIndex - 1));
        BlockPos b = path.get(idx);
        int dx = Math.abs(b.getX() - a.getX());
        int dz = Math.abs(b.getZ() - a.getZ());
        return dx > dz ? Direction.EAST : Direction.NORTH;
    }

    /** 方块保护 — 不覆盖已有建筑 */
    private boolean isProtected(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        Block block = state.getBlock();
        return block == Blocks.CHEST || block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE || block == Blocks.BED
                || block == Blocks.OAK_DOOR || block == Blocks.IRON_DOOR
                || state.hasBlockEntity();
    }
}
