# 第三方组件

LiteChunk 自身以 MIT 许可发布（见 `LICENSE`）。它包含或链接了下列第三方作品，各自的版权归原作者所有。

## 打包进 APK 的部分

### Chunker — MIT

`engine/src/main/java/com/hivemc/**`、`engine/src/main/java/org/iq80/**` 以及
`app/src/main/assets/engine-res/**` 来自 [Chunker](https://github.com/HiveGamesOSS/Chunker)，
Copyright (c) 2024 Hive Games，MIT 许可。裁剪与改动清单见 README 的「相对上游 Chunker 的改动」一节；
被改动过的文件里保留了 `ChunkBridge:` 前缀的注释（历史工程名）。

许可证全文：`engine/LICENSE-CHUNKER`。

### Fluent System Icons — MIT

`app/src/main/res/drawable/` 里的矢量图标取自
[Fluent System Icons](https://github.com/microsoft/fluentui-system-icons)（Copyright (c) Microsoft
Corporation，MIT 许可），按 24dp / viewBox 16 的规格重新导出为 Android VectorDrawable。

### Maven 依赖

| 制品 | 用途 |
| --- | --- |
| `com.google.guava:guava` | Chunker 的集合与工具 |
| `com.google.code.gson:gson` | Chunker 的 JSON 数据表 |
| `it.unimi.dsi:fastutil` | Chunker 的原始类型集合 |
| `net.jpountz.lz4:lz4` | 基岩版 NBT 的 LZ4 压缩 |
| `com.hivemc.leveldb:leveldb` / `leveldb-api` | 基岩版世界使用的 LevelDB |
| `org.jetbrains:annotations` | 空注解，编译期可见性提示 |
| `androidx.appcompat` / `material` / `documentfile` / `viewpager2` / `fragment` / `recyclerview` | 界面与 SAF 文件访问 |

上游制品的许可声明以下载到的 POM 与各自项目主页为准。

## 不包含在本仓库中的内容

- Minecraft 的任何资源或代码。本项目与 Mojang Studios、Microsoft 无关，Minecraft 是 Mojang Studios 的商标。
- 转换所需的存档样本。`app/src/androidTest/assets/` 里的测试存档已被移出仓库，见该目录的说明。

## 构建产物

`app/src/main/assets/chunker.jar` 不随仓库提供，它由 `:engine:engineDex` 任务在构建时生成
（R8 裁剪 + D8 dex，输入是本仓库的 `engine` 与 `api` 源码）。
