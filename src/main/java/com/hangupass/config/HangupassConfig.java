package com.hangupass.config;

import com.hangupass.Hangupass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Hangupass 配置文件。
 * 存放在 <world>/hangupass_config.json
 * 修改后重启服务器生效。
 */
public class HangupassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // === 扫描 ===
    public int scanRadiusChunks = 200;
    public int scanDelayTicks = 400; // 20 秒
    public boolean enableCache = true;                  // 缓存村庄位置到文件
    public boolean detectModdedVillages = true;          // 自动发现模组村庄
    public List<String> extraVillageKeywords = List.of(  // 额外村庄关键词
            "village", "town", "settlement", "hamlet"
    );

    // === 道路 ===
    public int roadHalfWidth = 1;
    public int edgeOffset = 2;
    public int maxStep = 1;
    public int lampInterval = 16;
    public boolean placeLanterns = true;
    public boolean protectBlocks = true;     // 保护已有建筑/作物/树木
    public int buildSegmentsPerTick = 2;     // 每 tick 铺路段数

    // === 桥梁 ===
    public boolean buildBridges = true;
    public int maxBridgeDepth = 12;

    // === 关隘 ===
    public boolean buildGates = true;
    public int gateMinDistance = 128;
    public List<String> gateExcludeBiomes = List.of(     // 不建关隘的生物群系
            "ocean", "river", "swamp"
    );

    // === 性能 ===
    public boolean verboseLogging = false;

    public static HangupassConfig load(Path worldDir) {
        Path configFile = worldDir.resolve("hangupass_config.json");
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                HangupassConfig config = GSON.fromJson(json, HangupassConfig.class);
                Hangupass.LOGGER.info("Loaded config from {}", configFile);
                return config;
            } catch (Exception e) {
                Hangupass.LOGGER.warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }
        HangupassConfig config = new HangupassConfig();
        save(configFile, config);
        return config;
    }

    public void save(Path worldDir) {
        save(worldDir.resolve("hangupass_config.json"), this);
    }

    private static void save(Path path, HangupassConfig config) {
        try {
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            Hangupass.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }
}
