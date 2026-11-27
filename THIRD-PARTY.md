# 第三方组件

LiteChunk 自身以 MIT 许可发布（见 `LICENSE`）。它包含或链接了下列第三方作品，各自的版权归原作者所有。

## 随 ChunkBridge SDK 进入 APK 的部分

build5 pr1 起，转换引擎、解压器与它们的全部依赖都不再放在本仓库，而是由
[ChunkBridge SDK](https://github.com/FreneticWind60/ChunkBridge) 以 AAR 提供（引擎 dex 内嵌在 AAR 的
`assets/chunkbridge/` 里，运行时在 `:chunkbridge` 进程加载）。这些组件的版权与许可全文以下游仓库为准：

| 组件 | 许可 | 说明 |
| --- | --- | --- |
| [Chunker](https://github.com/HiveGamesOSS/Chunker) | MIT，Copyright (c) 2024 Hive Games | 引擎主体（`com.hivemc` / `org.iq80` 与方块、格式映射表），本仓库只通过 AAR 间接使用 |
| [Arcana](https://github.com/RealBurst/arcana) | Apache-2.0，Copyright 2025 Stephane Bury | RAR 4/5、7z、TAR、TAR+GZIP 的解压侧 |
| guava / gson / fastutil / lz4 / leveldb / jsr305 | Apache-2.0 等 | 引擎侧的 Maven 依赖，打包进引擎 dex |

逐个组件的裁剪说明、版本钉死原因（例如 guava 必须停在 `33.4.0-jre`，更高版本用 `VarHandle` 会在 Android 13
以下崩）与许可全文，见 SDK 仓库的
[THIRD-PARTY.md](https://github.com/FreneticWind60/ChunkBridge/blob/main/THIRD-PARTY.md)、
`engine/LICENSE-CHUNKER` 与 `LICENSE-APACHE-2.0`。

## 本仓库直接使用的部分

### Fluent System Icons — MIT

`app/src/main/res/drawable/` 里的矢量图标取自
[Fluent System Icons](https://github.com/microsoft/fluentui-system-icons)（Copyright (c) Microsoft
Corporation，MIT 许可），按 24dp / viewBox 16 的规格重新导出为 Android VectorDrawable。

### Maven 依赖

| 制品 | 用途 |
| --- | --- |
| `androidx.appcompat` / `material` | 界面基础组件 |
| `androidx.documentfile` | SAF 文件访问 |
| `androidx.fragment` / `viewpager2` / `recyclerview` | 页面与列表 |

以上均为 Apache-2.0。上游制品的许可声明以下载到的 POM 与各自项目主页为准。

## 不包含在本仓库中的内容

- Minecraft 的任何资源或代码。本项目与 Mojang Studios、Microsoft 无关，Minecraft 是 Mojang Studios 的商标。
- 转换所需的存档样本。测试存档不随仓库提供：SDK 的仪器测试用 `library/src/androidTest/assets/test_world.zip`（`.gitignore` 忽略），
  本机另有 `sdk/local-worlds/real_world.zip` 供真机验证，均不进 git。

## 构建产物

`assets/chunkbridge/engine.jar`、`engine-res/` 与 `formats.json` 都不随仓库提供：它们由 SDK 的
`:engine:engineDex` 与 `:engine:dumpFormats` 在构建时生成并打进 AAR（R8 裁剪 + D8 dex，输入是 SDK 仓库的
`engine` 与 `api` 源码）。本仓库没有 `engineDex` 任务，也没有 `app/src/main/assets/chunker.jar`。
