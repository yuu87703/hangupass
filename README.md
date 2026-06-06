# Hangupass — 古代关隘道路模组

[![Build](https://github.com/YOUR_USERNAME/hangupass/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/hangupass/actions/workflows/build.yml)

一个 Fabric 1.21.1 模组，在 Minecraft **村庄之间生成古代中国风格的道路和关隘**。

---

## 功能

- 🔍 **自动扫描** — 启动后自动扫描世界中的所有村庄（平原/沙漠/热带草原/针叶林/雪地）
- 🛤️ **石板路** — 3 格宽古代风格道路连接相邻村庄，自适应地形坡度
- 🏰 **关隘** — 在道路中段生成城门楼，三种尺寸根据村庄距离自动选择
- 🌉 **桥梁** — 跨水体自动建桥墩
- 🎨 **5 种风格** — 根据生物群系选用石砖/砂岩/苔石/深板岩/雪地风格
- 🛡️ **方块保护** — 不覆盖箱子、工作台、作物、树木等玩家建筑
- 📦 **缓存** — 村庄位置缓存到文件，重启不用重扫
- ⚡ **分帧建造** — 大型工程分散到多个 tick 执行，不影响 TPS

---

## 安装

1. 安装 **Fabric Loader 0.19.2+** 和 **Fabric API 0.116.12+**
2. 从 [Releases](https://github.com/YOUR_USERNAME/hangupass/releases) 下载 `hangupass-<version>.jar`
3. 放入 `.minecraft/mods/` 目录
4. 启动游戏，模组会在 **5 秒后** 自动扫描建造

### 依赖

| 依赖 | 版本 | 必需 |
|------|------|:----:|
| Fabric Loader | ≥0.19.2 | ✅ |
| Fabric API | ≥0.116.12 | ✅ |
| Minecraft | 1.21.1 | ✅ |

---

## 命令

| 命令 | 权限 | 说明 |
|------|:----:|------|
| `/hangupass` | OP | 帮助 |
| `/hangupass status` | OP | 显示扫描状态和村庄列表 |
| `/hangupass rescan` | OP | 强制重新扫描并建造 |
| `/hangupass reload` | OP | 重载配置文件 |

---

## 配置

配置文件在 `./hangupass_config.json`（世界文件夹），首次启动自动生成：

```json
{
  "scanRadiusChunks": 200,
  "scanDelayTicks": 100,
  "enableCache": true,
  "detectModdedVillages": true,
  "extraVillageKeywords": ["village", "town", "settlement", "hamlet"],
  "placeLanterns": true,
  "protectBlocks": true,
  "buildSegmentsPerTick": 2,
  "buildBridges": true,
  "maxBridgeDepth": 12,
  "buildGates": true,
  "gateMinDistance": 128
}
```

| 配置项 | 默认 | 说明 |
|--------|:----:|------|
| `scanRadiusChunks` | 200 | 扫描半径（区块） |
| `scanDelayTicks` | 100 | 启动后延迟（tick） |
| `detectModdedVillages` | true | 自动发现模组添加的村庄 |
| `protectBlocks` | true | 保护已有建筑/作物/树木 |
| `buildSegmentsPerTick` | 2 | 每 tick 铺路段数（越大越快，越卡） |
| `gateMinDistance` | 128 | 最小村庄间隔才建关隘 |

---

## 关隘尺寸

| 名称 | 尺寸 | 触发距离 | 说明 |
|------|:----:|:--------:|------|
| 🏁 哨亭 | 5×3×4 | <256 | 简单拱门 + 顶垛口 |
| 🏰 关隘 | 9×5×7 | ≥256 | 门洞 + 双塔 + 烽火台 |
| 🏯 雄关 | 13×7×9 | ≥512 | 双层城楼 + 双烽火台 + 延伸城墙 |

---

## 道路风格

| 风格 | 主方块 | 适用村庄 |
|------|--------|----------|
| 石板路 🪨 | 石砖 | 平原 |
| 苔石路 🌿 | 苔石砖 | 丛林/沼泽 |
| 砂岩路 🏜️ | 切制砂岩 | 沙漠/热带草原 |
| 雪地路 ❄️ | 石砖 + 灵魂灯笼 | 雪地/针叶林 |
| 深板岩路 ⛰️ | 磨制深板岩 | 山地 |

---

## 自行编译

```bash
# 克隆
git clone https://github.com/YOUR_USERNAME/hangupass.git
cd hangupass

# 编译
./gradlew build

# 编译产物在 build/libs/hangupass-<version>.jar
```

### 使用 GitHub Actions

Fork 后 GitHub 会自动编译（`.github/workflows/build.yml`），每次推送生成构建产物。

---

## 项目结构

```
hangupass/
├── .github/workflows/build.yml     # 云端编译配置
├── build.gradle                     # Fabric Loom 构建
├── gradle.properties                # 版本配置
├── gradlew                          # Gradle Wrapper
└── src/main/java/com/hangupass/
    ├── Hangupass.java               # 主入口
    ├── command/
    │   └── HangupassCommand.java    # 调试命令
    ├── config/
    │   └── HangupassConfig.java     # JSON 配置
    └── world/
        ├── BuildScheduler.java      # 分帧调度器
        ├── GateStructure.java       # 关隘生成 (3种尺寸)
        ├── RoadBuilder.java         # 铺路引擎
        ├── RoadPathfinder.java      # 路径算法
        ├── RoadStyle.java           # 方块风格
        ├── VillagePairManager.java  # 配对管理
        └── VillageTracker.java      # 村庄扫描
```

---

## 许可

MIT
