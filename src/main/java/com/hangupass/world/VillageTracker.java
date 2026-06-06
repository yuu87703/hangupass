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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 村庄扫描器。
 * 使用种子搜索（/locate 同款算法）扫描世界中所有村庄。
 * 不依赖区块是否加载，新世界也能找到。
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
        if (keywords != null && !keywords.isEmpty()) {
            villageKeywords = new HashSet<>(keywords);
        }
    }

    /**
     * 主扫描方法。
     * 用种子搜索找村庄，不需要区块已加载。
     * 从世界原点向外逐层搜索，直到 radius 上限或连续 3 次没找到新村庄。
     */
    public static void scanAllVillages(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            Hangupass.LOGGER.warn("Overworld not found");
            return;
        }

        cacheFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("hangupass_villages.json");

        if (loadCache()) {
            Hangupass.LOGGER.info("Loaded {} villages from cache", discoveredVillages.size());
            scanned = true;
            return;
        }

        discoveredVillages.clear();
        scanned = false;

        // 收集所有目标结构 Holder
        Registry<Structure> registry = overworld.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> targetHolders = new ArrayList<>();

        // 原版村庄
        for (ResourceKey<Structure> key : VANILLA_VILLAGES) {
            try {
                targetHolders.add(registry.getHolderOrThrow(key));
            } catch (Exception e) {
                Hangupass.LOGGER.warn("Village structure not found: {}", key.location());
            }
        }

        // 模组村庄 (按关键词自动发现)
        for (Holder<Structure> holder : registry.holders().collect(Collectors.toList())) {
            String path = holder.unwrapKey().map(k -> k.location().getPath()).orElse("");
            boolean isVillage = villageKeywords.stream().anyMatch(path::contains);
            boolean isDuplicate = VANILLA_VILLAGES.contains(holder.unwrapKey().orElse(null));
            if (isVillage && !isDuplicate) {
                targetHolders.add(holder);
                Hangupass.LOGGER.info("Detected modded village: {}", path);
            }
        }

        if (targetHolders.isEmpty()) {
            Hangupass.LOGGER.warn("No village structures found in registry");
            return;
        }

        // 构建 HolderSet
        HolderSet<Structure> villageSet = HolderSet.direct(targetHolders);

        // 从原点向外搜索
        BlockPos searchCenter = BlockPos.ZERO;
        int maxRadius = 100; // 区块 = 1600 格
        int noNewVillageCount = 0;
        int totalFound = 0;

        Hangupass.LOGGER.info("Searching for villages (seed-based)...");

        while (searchCenter.getX() < maxRadius * 16 && noNewVillageCount < 3) {
            var result = overworld.getChunkSource().getGenerator()
                    .findNearestMapStructure(overworld, villageSet, searchCenter,
                            maxRadius, false);

            if (result == null) {
                noNewVillageCount++;
                searchCenter = searchCenter.offset(200, 0, 200);
                continue;
            }

            BlockPos villagePos = result.getFirst();
            Holder<Structure> holder = result.getSecond();
            String type = holder.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");

            VillageInfo info = new VillageInfo(villagePos, type, null);

            if (!discoveredVillages.contains(info)) {
                discoveredVillages.add(info);
                totalFound++;
                Hangupass.LOGGER.info("Found village #{}: [{}] at {}", totalFound, type, villagePos.toShortString());
                noNewVillageCount = 0;
            } else {
                noNewVillageCount++;
            }

            // 从刚找到的村庄偏移，继续搜索下一个
            searchCenter = villagePos.offset(200, 0, 200);
        }

        scanned = true;
        Hangupass.LOGGER.info("Village scan complete: {} villages found", totalFound);

        if (totalFound > 0) {
            saveCache();
            discoveredVillages.sort(Comparator.comparing(v -> v.pos().toShortString()));
            for (VillageInfo v : discoveredVillages) {
                Hangupass.LOGGER.info("  {} @ {}", v.name(), v.pos().toShortString());
            }
        } else {
            Hangupass.LOGGER.info("No villages found in range. Try /hangupass rescan after exploring.");
        }
    }

    public static List<VillageInfo> getDiscoveredVillages() {
        return Collections.unmodifiableList(discoveredVillages);
    }

    public static boolean isScanned() {
        return scanned;
    }

    // === 缓存 ===

    private static void saveCache() {
        if (cacheFile == null) return;
        try {
            List<CacheEntry> entries = discoveredVillages.stream()
                    .map(v -> new CacheEntry(v.pos().getX(), v.pos().getY(), v.pos().getZ(), v.type()))
                    .toList();
            Files.writeString(cacheFile, GSON.toJson(entries));
            Hangupass.LOGGER.info("Village cache saved");
        } catch (IOException e) {
            Hangupass.LOGGER.warn("Failed to save cache: {}", e.getMessage());
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
                discoveredVillages.add(new VillageInfo(new BlockPos(e.x, e.y, e.z), e.type, null));
            }
            return true;
        } catch (Exception e) {
            Hangupass.LOGGER.warn("Failed to load cache: {}", e.getMessage());
            return false;
        }
    }

    private record CacheEntry(int x, int y, int z, String type) {}

    // === 村庄信息 ===

    public record VillageInfo(BlockPos pos, String type, StructureStart start) {
        public String name() {
            return String.format("village_%s_%d_%d",
                    type.replace("village_", ""), pos.getX(), pos.getZ());
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
