package com.hangupass.world;

import com.hangupass.Hangupass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * 铺路执行引擎。
 * 在村庄之间实际放置方块，自适应地形。
 */
public class RoadBuilder {
    // 道路半宽: 路面 3 格 (dz=-1,0,1) + 两边沿 各 1 格 (dz=-2,2)
    private static final int ROAD_HALF = 1;
    private static final int EDGE_OFFSET = 2;

    // 最大坡度 (每格高度差)
    private static final int MAX_STEP = 1;

    // 坡度平滑窗口
    private static final int SMOOTH_WINDOW = 3;

    // 路灯间隔 (格)
    private static final int LAMP_INTERVAL = 16;

    // 随机变体种子偏移
    private static final Random RANDOM = new Random(42);

    private final ServerLevel level;
    private int totalBlocksPlaced = 0;

    public RoadBuilder(ServerLevel level) {
        this.level = level;
    }

    /**
     * 建造一条道路。
     */
    public BuildResult buildRoad(
            VillageTracker.VillageInfo from,
            VillageTracker.VillageInfo to,
            List<BlockPos> roughPath,
            RoadStyle style) {

        Hangupass.LOGGER.info("Building road {} → {} ({} nodes, style={})",
                from.name(), to.name(), roughPath.size(), style.name());

        // 1. 采样地表高度
        List<BlockPos> terrainPath = sampleHeights(roughPath);

        // 2. 平滑高度剖面
        List<Integer> smoothedY = smoothElevation(terrainPath);

        // 3. 为每个路径节点生成横截面
        int placed = 0;
        int cut = 0;
        int fill = 0;

        for (int i = 0; i < terrainPath.size(); i++) {
            BlockPos center = terrainPath.get(i);
            int roadY = smoothedY.get(i);

            // 道路前进方向 (用于横截面轴向判断)
            Direction forward = getPathDirection(terrainPath, i);
            boolean roadIsEastWest = (forward == Direction.EAST || forward == Direction.WEST);

            // 横截面: 垂直道路方向铺 5 格 (3 路面 + 2 边沿)
            // 沿道路方向铺 1 格宽
            for (int dz = -EDGE_OFFSET; dz <= EDGE_OFFSET; dz++) {
                for (int dx = -ROAD_HALF; dx <= ROAD_HALF; dx++) {
                    int offsetX, offsetZ;
                    if (roadIsEastWest) {
                        // 路沿 X 方向延伸
                        offsetX = dx;
                        offsetZ = dz;
                    } else {
                        // 路沿 Z 方向延伸
                        offsetX = dz;
                        offsetZ = dx;
                    }

                    BlockPos placePos = center.offset(offsetX, 0, offsetZ);
                    placePos = new BlockPos(placePos.getX(), roadY, placePos.getZ());

                    // === 桥检测: 如果路面下有水体 → 建桥 ===
                    BlockPos belowPos = placePos.below();
                    boolean isWaterBelow = !level.getBlockState(belowPos).getFluidState().isEmpty()
                            || level.getFluidState(belowPos).is(net.minecraft.tags.FluidTags.WATER);

                    if (isWaterBelow && Math.abs(dz) <= ROAD_HALF) {
                        // 桥墩: 从水面下探到海底
                        buildBridgePillar(placePos, style);
                        placeRoadBlock(placePos, style, forward, i);
                        placed++;
                        continue;
                    }

                    // 跳过水体 (地面铺路)
                    if (!level.getBlockState(placePos).getFluidState().isEmpty()) continue;

                    int heightDiff = getSurfaceHeight(placePos.getX(), placePos.getZ()) - roadY;

                    // 主路面 (|dz| <= ROAD_HALF)
                    if (Math.abs(dz) <= ROAD_HALF) {
                        if (heightDiff > 0) {
                            cutBlock(placePos);
                            cut++;
                        } else if (heightDiff < 0) {
                            fillFoundation(placePos, -heightDiff, style);
                            fill++;
                        }
                        placeRoadBlock(placePos, style, forward, i);
                        placed++;
                    }
                    // 路边沿 (|dz| == EDGE_OFFSET)
                    else if (Math.abs(dz) == EDGE_OFFSET) {
                        placeEdgeBlock(placePos, style, forward, dz > 0);
                        placed++;
                    }
                }
            }

            // 路灯 (每隔 LAMP_INTERVAL 在路边放置)
            if (i > 0 && i % LAMP_INTERVAL == 0) {
                placeLamp(center, roadY, style, forward);
            }
        }

        totalBlocksPlaced += placed;
        Hangupass.LOGGER.info("Road complete: {} blocks placed (cut={}, fill={})", placed, cut, fill);
        return new BuildResult(placed, cut, fill);
    }

    /**
     * 获取路径方向。
     */
    private Direction getPathDirection(List<BlockPos> path, int index) {
        if (index <= 0 || path.size() < 2) return Direction.NORTH;
        BlockPos prev = path.get(index - 1);
        BlockPos cur = path.get(index);
        int dx = cur.getX() - prev.getX();
        int dz = cur.getZ() - prev.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    /**
     * 采样路径各节点的地表高度。
     */
    private List<BlockPos> sampleHeights(List<BlockPos> path) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : path) {
            int y = getSurfaceHeight(pos.getX(), pos.getZ());
            result.add(new BlockPos(pos.getX(), y, pos.getZ()));
        }
        return result;
    }

    /**
     * 获取地表最高方块 Y。
     */
    private int getSurfaceHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    /**
     * 平滑高程剖面 — 限制坡度防止过陡。
     */
    private List<Integer> smoothElevation(List<BlockPos> terrainPath) {
        if (terrainPath.isEmpty()) return List.of();

        int n = terrainPath.size();
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = terrainPath.get(i).getY();
        }

        // 多次平滑: 限制相邻高度差 <= MAX_STEP
        boolean changed = true;
        int iterations = 0;
        while (changed && iterations < 10) {
            changed = false;
            iterations++;
            for (int i = 1; i < n; i++) {
                int diff = heights[i] - heights[i - 1];
                if (diff > MAX_STEP) {
                    heights[i] = heights[i - 1] + MAX_STEP;
                    changed = true;
                } else if (diff < -MAX_STEP) {
                    heights[i] = heights[i - 1] - MAX_STEP;
                    changed = true;
                }
            }
            for (int i = n - 2; i >= 0; i--) {
                int diff = heights[i + 1] - heights[i];
                if (diff > MAX_STEP) {
                    heights[i] = heights[i + 1] - MAX_STEP;
                    changed = true;
                } else if (diff < -MAX_STEP) {
                    heights[i] = heights[i + 1] + MAX_STEP;
                    changed = true;
                }
            }
        }

        // 移动平均滤波
        int[] smoothed = new int[n];
        for (int i = 0; i < n; i++) {
            int sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - SMOOTH_WINDOW); j <= Math.min(n - 1, i + SMOOTH_WINDOW); j++) {
                sum += heights[j];
                count++;
            }
            smoothed[i] = Math.round((float) sum / count);
        }

        return Arrays.stream(smoothed).boxed().toList();
    }

    /**
     * 放置路面方块。
     */
    private void placeRoadBlock(BlockPos pos, RoadStyle style, Direction forward, int nodeIndex) {
        BlockState state;
        boolean useAlt = RANDOM.nextFloat() < style.altChance();

        if (useAlt) {
            state = style.altBlock().defaultBlockState();
        } else {
            state = style.mainBlock().defaultBlockState();
        }

        setBlock(pos, state);
    }

    /**
     * 放置路边台阶。
     */
    private void placeEdgeBlock(BlockPos pos, RoadStyle style, Direction forward, boolean isRight) {
        // 用台阶作为路边沿
        BlockState slabState = style.edgeBlock().defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP);

        // 检查下方是否有方块
        BlockPos below = pos.below();
        if (level.getBlockState(below).isAir()) {
            // 悬空→跳过 (道路边缘在斜坡处悬空就不放)
            return;
        }

        setBlock(pos, slabState);
    }

    /**
     * 放置路灯。
     */
    private void placeLamp(BlockPos center, int roadY, RoadStyle style, Direction forward) {
        // 在道路两侧放灯
        for (int side : new int[]{-1, 1}) {
            BlockPos lampPos = new BlockPos(
                    center.getX() + side * (ROAD_HALF + 1),
                    roadY,
                    center.getZ()
            );

            // 确认灯位不是水里
            if (!level.getBlockState(lampPos).getFluidState().isEmpty()) continue;

            // 下方支柱
            for (int h = 0; h < 1; h++) {
                setBlock(lampPos.below(h), style.wallBlock().defaultBlockState());
            }

            // 灯笼
            BlockState lampState = style.lampBlock().defaultBlockState()
                    .setValue(LanternBlock.HANGING, true);
            setBlock(lampPos, lampState);
        }
    }

    /**
     * 建桥墩: 从路面下探到海底或岩石，放置支柱。
     */
    private void buildBridgePillar(BlockPos roadPos, RoadStyle style) {
        // 从路面往下扫，直到找到固体地面或超过 12 格
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos checkPos = roadPos.below(dy);
            BlockState state = level.getBlockState(checkPos);
            boolean isSolid = !state.isAir() && state.getFluidState().isEmpty();
            if (isSolid) break; // 找到地面
            // 放支柱
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                setBlock(checkPos, style.foundationBlock().defaultBlockState());
            }
        }
    }

    /**
     * 切掉地形方块。
     */
    private void cutBlock(BlockPos pos) {
        int surfaceY = getSurfaceHeight(pos.getX(), pos.getZ());
        BlockPos target = new BlockPos(pos.getX(), surfaceY, pos.getZ());

        // 只切非空气、非流体、非基岩的方块
        BlockState state = level.getBlockState(target);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.is(Blocks.BEDROCK)) return;

        // 从地表向上扫 5 格，移除覆盖物 (草、土等)
        for (int dy = 0; dy < 5; dy++) {
            BlockPos p = target.above(dy);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) break;
            setBlock(p, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 填路基。
     */
    private void fillFoundation(BlockPos pos, int layers, RoadStyle style) {
        for (int dy = 1; dy <= layers && dy <= 4; dy++) {
            BlockPos fillPos = pos.below(dy);
            BlockState existing = level.getBlockState(fillPos);
            // 已有固体方块则不填
            if (!existing.isAir() && existing.getFluidState().isEmpty() && !existing.is(Blocks.BEDROCK)) continue;
            setBlock(fillPos, style.foundationBlock().defaultBlockState());
        }
    }

    // 保护方块集合 — 不覆盖
    private static final Set<Block> PROTECTED_BLOCKS = Set.of(
            Blocks.CHEST, Blocks.BARREL, Blocks.FURNACE, Blocks.BLAST_FURNACE,
            Blocks.SMOKER, Blocks.CRAFTING_TABLE, Blocks.ANVIL,
            Blocks.STONECUTTER, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE,
            Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.CAULDRON,
            Blocks.BREWING_STAND, Blocks.ENCHANTING_TABLE,
            Blocks.BOOKSHELF, Blocks.JUKEBOX,
            Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.DARK_OAK_DOOR,
            Blocks.BIRCH_DOOR, Blocks.JUNGLE_DOOR, Blocks.ACACIA_DOOR,
            Blocks.CHERRY_DOOR, Blocks.IRON_DOOR,
            Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR,
            Blocks.RED_BED, Blocks.BLUE_BED, Blocks.WHITE_BED, Blocks.BLACK_BED,
            Blocks.GREEN_BED, Blocks.YELLOW_BED, Blocks.BROWN_BED,
            Blocks.OAK_FENCE_GATE, Blocks.SPRUCE_FENCE_GATE,
            Blocks.FARMLAND, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES,
            Blocks.BEETROOTS, Blocks.MELON, Blocks.PUMPKIN,
            Blocks.CAKE, Blocks.COMPOSTER
    );

    // 保护标签 - 需要保留的方块标签
    private static final Set<Class<?>> PROTECTED_TILE_ENTITIES = Set.of(
            // 所有有容器/数据的方块自动保护
    );

    /**
     * 在世界上设置方块，兼容性检查后在放置。
     */
    private void setBlock(BlockPos pos, BlockState state) {
        if (isProtected(pos)) {
            return;
        }
        level.setBlock(pos, state, 3); // UPDATE_ALL | UPDATE_CLIENTS
        totalBlocksPlaced++;
    }

    /**
     * 检查是否应该保护该位置 (不覆盖)。
     */
    private boolean isProtected(BlockPos pos) {
        BlockState existing = level.getBlockState(pos);
        Block block = existing.getBlock();

        // 1. 保护方块集合
        if (PROTECTED_BLOCKS.contains(block)) return true;

        // 2. 保护所有原版功能方块 (有 BlockEntity 的)
        if (existing.hasBlockEntity()) return true;

        // 3. 保护树 (原木、树叶)
        if (block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)
                || block.defaultBlockState().is(net.minecraft.tags.BlockTags.LEAVES)) return true;

        // 4. 保护花、草 (保持自然)
        if (block instanceof FlowerBlock || block instanceof TallFlowerBlock
                || block instanceof net.minecraft.world.level.block.GrassBlock) return true;

        // 5. 保护水/熔岩源
        if (!existing.getFluidState().isEmpty() && existing.getFluidState().isSource()) return true;

        // 6. 保护铁轨
        if (block instanceof BaseRailBlock) return true;

        return false;
    }

    public int getTotalBlocksPlaced() {
        return totalBlocksPlaced;
    }

    /**
     * 建造结果。
     */
    public record BuildResult(int blocksPlaced, int blocksCut, int blocksFilled) {}
}
