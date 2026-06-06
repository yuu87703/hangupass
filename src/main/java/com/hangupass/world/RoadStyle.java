package com.hangupass.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 道路风格调色板。
 * 每种生物群系对应不同方块组合。
 */
public record RoadStyle(
        String name,
        Block mainBlock,        // 主路面方块
        Block altBlock,         // 随机替换 (破损变体)
        float altChance,        // 替换概率
        Block edgeBlock,        // 路边沿 (台阶)
        Block foundationBlock,  // 路基填充
        Block lampBlock,        // 路灯
        Block wallBlock         // 城墙/护栏
) {
    // ======= 预设风格 =======

    /** 默认古代石板路 */
    public static final RoadStyle STONE_BRICK = new RoadStyle(
            "stone_brick",
            Blocks.STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS,
            0.15f,
            Blocks.STONE_BRICK_SLAB,
            Blocks.STONE_BRICKS,
            Blocks.LANTERN,
            Blocks.STONE_BRICK_WALL
    );

    /** 苔石路 (丛林/沼泽风格) */
    public static final RoadStyle MOSSY = new RoadStyle(
            "mossy",
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.STONE_BRICKS,
            0.15f,
            Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.SOUL_LANTERN,
            Blocks.MOSSY_STONE_BRICK_WALL
    );

    /** 砂岩路 (沙漠) */
    public static final RoadStyle SANDSTONE = new RoadStyle(
            "sandstone",
            Blocks.CUT_SANDSTONE,
            Blocks.CHISELED_SANDSTONE,
            0.1f,
            Blocks.SANDSTONE_SLAB,
            Blocks.SANDSTONE,
            Blocks.LANTERN,
            Blocks.SANDSTONE_WALL
    );

    /** 石砖路 (雪地/针叶林) */
    public static final RoadStyle SNOWY = new RoadStyle(
            "snowy",
            Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS,
            0.1f,
            Blocks.STONE_BRICK_SLAB,
            Blocks.STONE_BRICKS,
            Blocks.SOUL_LANTERN,
            Blocks.COBBLESTONE_WALL
    );

    /** 深板岩路 (山地) */
    public static final RoadStyle DEEPSLATE = new RoadStyle(
            "deepslate",
            Blocks.POLISHED_DEEPSLATE,
            Blocks.CHISELED_DEEPSLATE,
            0.12f,
            Blocks.POLISHED_DEEPSLATE_SLAB,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.LANTERN,
            Blocks.POLISHED_DEEPSLATE_WALL
    );

    /**
     * 根据村庄类型/生物群系选择合适风格。
     */
    public static RoadStyle forVillageType(String villageType) {
        return switch (villageType) {
            case "village_desert" -> SANDSTONE;
            case "village_savanna" -> SANDSTONE;
            case "village_taiga" -> SNOWY;
            case "village_snowy" -> SNOWY;
            case "village_plains" -> STONE_BRICK;
            default -> STONE_BRICK;
        };
    }

    /**
     * 两村庄间选风格：以终点村庄类型为准。
     * 如果两端类型不同，用高优先级类型。
     */
    public static RoadStyle forVillagePair(
            VillageTracker.VillageInfo from,
            VillageTracker.VillageInfo to) {
        // 优先级: desert > snowy > plains
        RoadStyle styleA = forVillageType(from.type());
        RoadStyle styleB = forVillageType(to.type());
        return priority(styleA) >= priority(styleB) ? styleA : styleB;
    }

    private static int priority(RoadStyle style) {
        if (style == SANDSTONE) return 3;
        if (style == MOSSY) return 2;
        if (style == DEEPSLATE) return 2;
        if (style == SNOWY) return 1;
        return 0;
    }
}
