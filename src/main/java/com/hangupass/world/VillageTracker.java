package com.hangupass.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hangupass.Hangupass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 村庄扫描器 — v3.
 *
 * ## 搜索策略
 * 1. 主方案: 从多个原点向外螺旋搜索 (覆盖 4 个象限)
 * 2. 备方案: 扫描 region 文件直接读取结构数据
 *
 * ## 修复历史
 * - v1: 逐区块扫描，只扫已加载区块 → 找不到村
 * - v2: findNearestMapStructure 只搜 +x,+z 半径小 → 找不到村
 * - v3: 多原点螺旋搜索 + region 文件扫描 + 防空缓存
 */
public class VillageTracker {
    // 原版村庄结构 ID
    public static final List<ResourceKey<Structure>> VANILLA_VILLAGES = List.of(
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village_plains")),
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village_desert")),
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village_savanna")),
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village_taiga")),
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village_snowy"))
    );

    private static Set<String> villageKeywords = new HashSet<>(Set.of("village", "town", "settlement", "hamlet"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<VillageInfo> discoveredVillages = new ArrayList<>();
    private static boolean scanned = false;
    private static Path cacheFile = null;

    public static void setVillageKeywords(Set<String> keywords) {
        if (keywords != null && !keywords.isEmpty())
            villageKeywords = new HashSet<>(keywords);
    }

    // ============ 主入口 ============

    public static void scanAllVillages(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) { Hangupass.LOGGER.warn("Overworld not found"); return; }

        cacheFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("hangupass_villages.json");

        // 尝试加载缓存（跳过空缓存）
        if (loadCache() && discoveredVillages.size() >= 2) {
            Hangupass.LOGGER.info("Loaded {} villages from cache", discoveredVillages.size());
            scanned = true;
            return;
        }
        discoveredVillages.clear();

        // 收集目标结构
        Registry<Structure> registry = overworld.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> targets = collectTargets(registry);
        if (targets.isEmpty()) {
            Hangupass.LOGGER.warn("No village structures found"); return;
        }

        // === 方案 A: findNearestMapStructure 多原点螺旋搜索 ===
        Hangupass.LOGGER.info("=== Village scan (seed-based) ===");
        searchViaStructureLocator(overworld, targets);

        // === 方案 B: 如果方案 A 找到太少，备选 region 文件扫描 ===
        if (discoveredVillages.size() < 2) {
            Hangupass.LOGGER.info("Only {} villages found via seed search, trying region scan...",
                    discoveredVillages.size());
            searchViaRegionFiles(overworld, targets);
        }

        scanned = true;
        int total = discoveredVillages.size();
        Hangupass.LOGGER.info("=== Village scan complete: {} found ===", total);
        discoveredVillages.sort(Comparator.comparing(v -> v.pos().toShortString()));
        for (VillageInfo v : discoveredVillages)
            Hangupass.LOGGER.info("  {} @ {}", v.name(), v.pos().toShortString());

        // 只有找到 ≥2 个才缓存（避免空缓存阻塞下次扫描）
        if (total >= 2) saveCache();
        else Hangupass.LOGGER.info("Need ≥2 villages for roads. Explore more then /hangupass rescan");
    }

    // ============ 方案 A: 种子搜索 ============

    private static void searchViaStructureLocator(ServerLevel level, List<Holder<Structure>> targets) {
        HolderSet<Structure> villageSet = HolderSet.direct(targets);
        var generator = level.getChunkSource().getGenerator();

        // 从多个原点出发, 每个原点用大半径搜索
        // 原点网格: 每 2000 格一个搜索起点, 覆盖 ±6000 格范围
        int gridStep = 2000;
        int gridRange = 6000;
        int searchRadiusChunks = 150;  // 2400 格

        for (int gx = -gridRange; gx <= gridRange; gx += gridStep) {
            for (int gz = -gridRange; gz <= gridRange; gz += gridStep) {
                BlockPos origin = new BlockPos(gx, 64, gz);
                int attempts = 0;

                while (attempts < 5) {  // 每个原点最多搜 5 次
                    try {
                        var result = generator.findNearestMapStructure(
                                level, villageSet, origin, searchRadiusChunks, false);
                        if (result == null) break;

                        BlockPos pos = result.getFirst();
                        String type = result.getSecond().unwrapKey()
                                .map(k -> k.location().getPath()).orElse("unknown");
                        VillageInfo info = new VillageInfo(pos, type, null);

                        synchronized (discoveredVillages) {
                            if (!discoveredVillages.contains(info)) {
                                discoveredVillages.add(info);
                                Hangupass.LOGGER.info("  Found: [{}] at {}", type, pos.toShortString());
                            }
                        }
                        // 从找到的位置偏移, 搜下一个
                        origin = pos.offset(100, 0, 100);
                        attempts++;
                    } catch (Exception e) {
                        Hangupass.LOGGER.warn("Search error at {}: {}", origin, e.getMessage());
                        break;
                    }
                }
            }
        }
    }

    // ============ 方案 B: Region 文件扫描 ============

    private static void searchViaRegionFiles(ServerLevel level, List<Holder<Structure>> targets) {
        Path regionDir = level.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT).resolve("region");
        if (!Files.isDirectory(regionDir)) {
            Hangupass.LOGGER.warn("Region directory not found: {}", regionDir);
            return;
        }

        // 收集 targets 的 Structure 对象用于对比
        Set<Structure> targetStructures = targets.stream()
                .map(Holder::value)
                .collect(Collectors.toSet());

        try {
            Files.list(regionDir)
                    .filter(f -> f.toString().endsWith(".mca"))
                    .limit(200)  // 最多扫 200 个 region 文件
                    .forEach(regionFile -> scanRegionFile(level, regionFile, targetStructures));
        } catch (IOException e) {
            Hangupass.LOGGER.warn("Region scan error: {}", e.getMessage());
        }
    }

    private static void scanRegionFile(ServerLevel level, Path regionFile, Set<Structure> targets) {
        // 从文件名解析 region 坐标: r.<x>.<z>.mca
        String name = regionFile.getFileName().toString();
        String[] parts = name.replace(".mca", "").split("\\.");
        if (parts.length < 3) return;
        try {
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);

            // 遍历该 region 的 32x32 区块
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    int chunkX = rx * 32 + cx;
                    int chunkZ = rz * 32 + cz;

                    if (!level.hasChunk(chunkX, chunkZ)) continue;

                    try {
                        var chunk = level.getChunk(chunkX, chunkZ,
                                net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, false);
                        if (chunk == null) continue;

                        var allStarts = chunk.getAllStarts();
                        for (var entry : allStarts.entrySet()) {
                            StructureStart start = entry.getValue();
                            if (start == null || !start.isValid()) continue;
                            if (!targets.contains(entry.getKey())) continue;

                            BlockPos center = start.getBoundingBox().getCenter();
                            String type = targets.stream()
                                    .filter(s -> s == entry.getKey())
                                    .findFirst()
                                    .map(s -> targets.stream()
                                            .filter(h -> h.value() == entry.getKey())
                                            .findFirst()
                                            .flatMap(h -> h.unwrapKey())
                                            .map(k -> k.location().getPath())
                                            .orElse("unknown"))
                                    .orElse("unknown");

                            VillageInfo info = new VillageInfo(center, type, start);
                            synchronized (discoveredVillages) {
                                if (!discoveredVillages.contains(info)) {
                                    discoveredVillages.add(info);
                                    Hangupass.LOGGER.info("  (region) [{}] at {}", type, center.toShortString());
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    // ============ 收集目标结构 ============

    private static List<Holder<Structure>> collectTargets(Registry<Structure> registry) {
        List<Holder<Structure>> list = new ArrayList<>();
        for (ResourceKey<Structure> key : VANILLA_VILLAGES) {
            try { list.add(registry.getHolderOrThrow(key)); }
            catch (Exception e) { Hangupass.LOGGER.warn("Structure not found: {}", key.location()); }
        }
        for (Holder<Structure> h : registry.holders().collect(Collectors.toList())) {
            String path = h.unwrapKey().map(k -> k.location().getPath()).orElse("");
            boolean hasKeyword = villageKeywords.stream().anyMatch(path::contains);
            boolean isDuplicate = VANILLA_VILLAGES.contains(h.unwrapKey().orElse(null));
            if (hasKeyword && !isDuplicate) {
                list.add(h);
                Hangupass.LOGGER.info("Detected modded village: {}", path);
            }
        }
        return list;
    }

    // ============ 公开接口 ============

    public static List<VillageInfo> getDiscoveredVillages() {
        synchronized (discoveredVillages) {
            return List.copyOf(discoveredVillages);
        }
    }

    public static boolean isScanned() { return scanned; }

    // ============ 缓存 ============

    private static void saveCache() {
        if (cacheFile == null || discoveredVillages.size() < 2) return;
        try {
            List<CacheEntry> entries = discoveredVillages.stream()
                    .map(v -> new CacheEntry(v.pos().getX(), v.pos().getY(), v.pos().getZ(), v.type()))
                    .toList();
            Files.writeString(cacheFile, GSON.toJson(entries));
            Hangupass.LOGGER.info("Cache saved: {} villages", entries.size());
        } catch (IOException e) {
            Hangupass.LOGGER.warn("Cache save failed: {}", e.getMessage());
        }
    }

    private static boolean loadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) return false;
        try {
            String json = Files.readString(cacheFile);
            List<CacheEntry> entries = GSON.fromJson(json, new TypeToken<List<CacheEntry>>() {}.getType());
            if (entries == null || entries.size() < 2) {
                Files.deleteIfExists(cacheFile);  // 空缓存直接删
                return false;
            }
            discoveredVillages.clear();
            for (CacheEntry e : entries)
                discoveredVillages.add(new VillageInfo(new BlockPos(e.x, e.y, e.z), e.type, null));
            return true;
        } catch (Exception e) {
            Hangupass.LOGGER.warn("Cache load failed: {}", e.getMessage());
            return false;
        }
    }

    private record CacheEntry(int x, int y, int z, String type) {}

    // ============ 村庄信息 ============

    public record VillageInfo(BlockPos pos, String type, StructureStart start) {
        public String name() {
            return String.format("village_%s_%d_%d",
                    type.replace("village_", ""), pos.getX(), pos.getZ());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VillageInfo that = (VillageInfo) o;
            return pos.equals(that.pos);
        }
        @Override public int hashCode() { return pos.hashCode(); }
    }
}
