package com.hangupass.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hangupass.Hangupass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * 村庄扫描器。
 * 扫描世界中已生成的村庄，支持原版 + 模组村庄，支持缓存。
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

    // 额外模组村庄前缀 (自动发现) — 启动时从 config 覆盖
    private static Set<String> villageKeywords = new HashSet<>(Set.of("village", "town", "settlement", "hamlet"));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 结果缓存
    private static final List<VillageInfo> discoveredVillages = new ArrayList<>();
    private static boolean scanned = false;
    private static Path cacheFile = null;

    /**
     * 设置关键词 (从配置加载)。
     */
    public static void setVillageKeywords(Set<String> keywords) {
        if (keywords != null && !keywords.isEmpty()) {
            villageKeywords = new HashSet<>(keywords);
        }
    }

    /**
     * 创建分块扫描任务列表 (每任务 = 1 个区块)。
     *
     * @param detectModded 是否自动发现模组村庄
     */
    public static List<Runnable> createScanTasks(ServerLevel level, int radiusChunks,
                                                  boolean detectModded) {
        List<Runnable> tasks = new ArrayList<>();
        Registry<Structure> structureRegistry = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE);

        // 原版村庄
        List<Holder<Structure>> vanillaHolders = VANILLA_VILLAGES.stream()
                .map(key -> (Holder<Structure>) structureRegistry.getHolderOrThrow(key))
                .toList();

        // 自动发现模组村庄结构 (按关键词)
        List<Holder<Structure>> moddedVillageHolders = new ArrayList<>();
        if (detectModded) {
            for (Holder<Structure> holder : (Iterable<Holder<Structure>>) (Object) structureRegistry.holders()) {
                String path = holder.unwrapKey()
                        .map(k -> k.location().getPath())
                        .orElse("");
                boolean isVillage = villageKeywords.stream().anyMatch(path::contains);
                if (isVillage && !VANILLA_VILLAGES.contains(holder.unwrapKey().orElse(null))) {
                    moddedVillageHolders.add(holder);
                    Hangupass.LOGGER.info("Detected modded village structure: {}", path);
                }
            }
        }

        List<Holder<Structure>> allTargets = new ArrayList<>();
        allTargets.addAll(vanillaHolders);
        allTargets.addAll(moddedVillageHolders);

        // 创建标记数组避免重复
        Set<ChunkPos> processedChunks = new HashSet<>();

        for (int cx = -radiusChunks; cx <= radiusChunks; cx++) {
            for (int cz = -radiusChunks; cz <= radiusChunks; cz++) {
                ChunkPos chunkPos = new ChunkPos(cx, cz);

                tasks.add(() -> {
                    // 只扫描已生成的区块
                    if (!level.hasChunk(cx, cz)) return;

                    synchronized (processedChunks) {
                        if (!processedChunks.add(chunkPos)) return;
                    }

                    var chunk = level.getChunk(cx, cz, ChunkStatus.EMPTY, false);
                    if (chunk == null) return;

                    SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSection());
                    for (Holder<Structure> holder : allTargets) {
                        var structureStarts = level.structureManager().startsForStructure(sectionPos, holder);
                        for (StructureStart start : structureStarts) {
                            if (start == null || !start.isValid()) continue;

                            BlockPos center = start.getBoundingBox().getCenter();
                            String type = holder.unwrapKey()
                                    .map(k -> k.location().getPath())
                                    .orElse("unknown");
                            VillageInfo info = new VillageInfo(center, type, start);

                            synchronized (discoveredVillages) {
                            if (!discoveredVillages.contains(info)) {
                                discoveredVillages.add(info);
                                Hangupass.LOGGER.info("Found village: [{}] at {}",
                                        type, center.toShortString());
                            }
                        }
                    }
                    }
                });
            }
        }

        return tasks;
    }

    /**
     * 执行完整扫描 (同步，旧版)。
     */
    public static void scanAllVillages(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            Hangupass.LOGGER.warn("Overworld not found");
            return;
        }

        // 设置缓存路径
        cacheFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("hangupass_villages.json");

        // 尝试从缓存加载
        if (loadCache()) {
            Hangupass.LOGGER.info("Loaded {} villages from cache", discoveredVillages.size());
            scanned = true;
            return;
        }

        discoveredVillages.clear();
        scanned = false;

        List<Runnable> tasks = createScanTasks(overworld, 200, true);
        Hangupass.LOGGER.info("Village scan: {} chunks to check", tasks.size());

        int count = 0;
        for (Runnable task : tasks) {
            task.run();
            count++;
            if (count % 5000 == 0) {
                Hangupass.LOGGER.info("Scan progress: {}/{} chunks, {} villages found",
                        count, tasks.size(), discoveredVillages.size());
            }
        }

        scanned = true;
        Hangupass.LOGGER.info("Village scan complete: {} villages found", discoveredVillages.size());

        // 保存缓存
        saveCache();

        // 排序输出
        discoveredVillages.sort(Comparator.comparing(v -> v.pos().toShortString()));
        for (VillageInfo v : discoveredVillages) {
            Hangupass.LOGGER.info("  {}: {} type={}", v.name(), v.pos().toShortString(), v.type());
        }
    }

    public static List<VillageInfo> getDiscoveredVillages() {
        return Collections.unmodifiableList(discoveredVillages);
    }

    public static boolean isScanned() {
        return scanned;
    }

    // === 缓存 ===

    /**
     * 村庄列表缓存到 JSON (保存/重启后不用重扫)。
     */
    private static void saveCache() {
        if (cacheFile == null) return;
        List<CacheEntry> entries = discoveredVillages.stream()
                .map(v -> new CacheEntry(v.pos().getX(), v.pos().getY(), v.pos().getZ(), v.type()))
                .toList();
        try {
            Files.writeString(cacheFile, GSON.toJson(entries));
        } catch (IOException e) {
            Hangupass.LOGGER.warn("Failed to save village cache: {}", e.getMessage());
        }
    }

    private static boolean loadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) return false;
        try {
            String json = Files.readString(cacheFile);
            List<CacheEntry> entries = GSON.fromJson(json, new TypeToken<List<CacheEntry>>() {}.getType());
            if (entries == null || entries.isEmpty()) return false;

            discoveredVillages.clear();
            for (CacheEntry e : entries) {
                BlockPos pos = new BlockPos(e.x, e.y, e.z);
                discoveredVillages.add(new VillageInfo(pos, e.type, null));
            }
            return true;
        } catch (Exception e) {
            Hangupass.LOGGER.warn("Failed to load village cache: {}", e.getMessage());
            return false;
        }
    }

    private record CacheEntry(int x, int y, int z, String type) {}

    // === 村庄信息 ===

    public record VillageInfo(BlockPos pos, String type, StructureStart start) {
        public String name() {
            return String.format("village_%s_%d_%d", type.replace("village_", ""), pos.getX(), pos.getZ());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VillageInfo that = (VillageInfo) o;
            return pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }
}
