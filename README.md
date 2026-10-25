# LiteChunk

Android 上的 Minecraft 存档互转工具（Java 版 ⇄ 基岩版）。转换引擎来自
[Chunker](https://github.com/HiveGamesOSS/Chunker) 的裁剪版，通过 `DexClassLoader` 在独立进程中加载。

- 包名 `com.frenetic.litechunk`，版本 `1.21.0 build1`（与引擎 Chunker 的版本同步）
- 最低系统 Android 8.1（API 27）—— `java.nio.file` 与基岩版 1.26 的共同下限；目标 API 37
- 只做一件事：选存档 → 选版本 → 转换 → 保存 / 分享 / 导入
- MIT 许可，非官方项目，与 Mojang Studios、Microsoft 无关

## 工程结构

```
api/      com.frenetic.litechunk.api    App 与引擎之间的稳定契约（打进 APK，不混入 dex）
engine/   Chunker 裁剪源码 + EngineImpl 门面 + R8 打包任务
app/      UI（转换页 / 文件页）、文件流、:chunker 隔离进程服务、DexClassLoader 加载器
website/  官网单页（单文件 HTML，零外部请求，可直接丢到 GitHub Pages）
```

关键点：`api` 模块只被 App 的 ClassLoader 加载，引擎 dex 里不包含它的副本。`DexClassLoader` 是
parent-first 的，所以 `EngineImpl` 实现接口后可以直接强转，不需要反射 Chunker 的任何类型。

## 构建

需要 JDK 17 或更高、Android SDK Platform 37 与 Build-Tools 37.0.0。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease     # 有 keystore.properties 才签名，没有就产出未签名包
./gradlew :engine:engineDex        # 单独重新生成引擎 dex（改引擎源码后用）
```

`app/src/main/assets/chunker.jar`（引擎 dex）不在仓库里，构建时由 `:engine:engineDex` 生成：
R8 裁剪 + D8 dex，输入是本仓库的 `engine` 与 `api` 源码，所以改引擎代码不需要额外步骤。

`assembleDebug` 也不需要手动先跑 `engineDex`：`merge*Assets` 与 lint 任务都显式
`dependsOn(":engine:engineDex")`，而 `engineDex` 声明了 inputs/outputs，未改动时是 `UP-TO-DATE`。
这层依赖本来是没有的：`chunker.jar` 只是 `assets` 里的普通文件，Gradle 不保证它先于 `mergeAssets` 写出，
单独跑 `:app:assembleDebug` 会静默把**上一份 dex** 打进 APK。新增读 assets 的任务时要把依赖一起加上。

### 发布签名

自己发布要准备 `keystore.properties` + 一个 keystore，放在项目根（两者都已在 `.gitignore` 里）：

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

**这两个文件要离线备份。** 丢了以后任何新版本都无法覆盖安装到已装设备上（签名不一致，只能卸载重装）。
调试包与正式包签名不同，在装过 debug 的设备上安装正式版必须先卸载。

### 版本号规则

```
versionCode = major*10^8 + minor*10^6 + patch*10^4 + build*100 + stage
  stage = 1..49  预览版
  stage = 50     正式发布
```

`1.21.0 build1` → `121_000_150`。抬到 `10^8` 而不是 `10^7`，是因为最早的两次预览用过旧编码
（`12_100_501` / `12_100_502`），正式版必须能在任何还留在设备上的预览版之上安装。

### 发布

GitHub Release 的 tag 用连字符写法：`1.21.0-build1`（或 `v1.21.0-build1`）。
关于页的「检查更新」把 tag 与 `BuildConfig.VERSION_NAME` 去掉所有非字母数字字符后比对，
所以这两种写法都认作同一个版本 —— tag 里不能有空格，git 也不允许。
仓库地址写死在 `AboutActivity.REPO`，fork 之后要改那一行。

### 桌面端验证

```bash
./gradlew :engine:smokeTest    # 版本表是否还能加载、检测是否安全
./gradlew :engine:roundTrip    # 官方集成样例 Java 1.20.5 -> 基岩 1.26.60 -> Java
./gradlew :engine:engineCli -PheapSize=512m \
  --args="<世界目录或zip> <工作目录> BEDROCK_1.26.60 nores 2"
./gradlew :engine:actorDump --args="<基岩世界目录>"   # 统计 digp / actorprefix / definitions
```

`engineCli` 跑的是与 APK 内完全相同的引擎源码，只是 GC 不同，用来回答「这段世界要跑多久、多少内存」——
比在模拟器上试快 10~40 倍。`actorDump` 是验证实体链路的唯一手段：基岩 1.16.100+ 要求每个
`actorprefix` 记录都带 `definitions`，缺了游戏会**静默丢弃**该实体。

## 数据目录

全部集中在 `/sdcard/Android/data/com.frenetic.litechunk/`，不需要任何存储权限：

| 路径 | 用途 |
| --- | --- |
| `output/` | 转换成品。基岩版 `.mcworld`，Java 版 `.zip` |
| `input.json` | 转化列表的快照（只存路径与格式，不复制存档） |
| 内部 `cacheDir/flush/job-*` | 转换期的暂存目录，任务结束即删 |

## 界面

### 转换页

- **转化列表**：可批量添加；未开始时左右滑动即可移除，也可以点行尾按钮移除。转换进行中禁止改动列表。
- **输出格式**：Java 版 / 基岩版，目标版本从引擎枚举出的 124 个版本里选。
  - `转出后自动删除原存档`（默认不勾）—— 只在**打包成功之后**才删，失败不会丢原档。
  - `打包为 .mcworld`（仅基岩版可见，默认勾选）—— 取消后输出 `.zip`。
- **本轮输出**：本轮产出的清单，每行可「放入转化列表 / 导出为 zip / 导出为 mcworld / 分享 / 导入游戏 / 删除」。

### 文件页

管理 `output/`：

- **左滑整行**展开六个操作：装填 / zip / mcworld / 分享 / 导入 / 删除。
  展开是 `translationX` 位移而不是重新布局，配合 `PathInterpolator(0.2, 0, 0, 1)`、按剩余距离计算的时长、
  甩动速度判定，以及操作条的淡入，拖动跟手且松手后能「顺着力道」吸附。
- **右滑整行**直接进入多选并选中该行；右上角按钮是另一个入口，再次点击退出。
  多选时底部出现「已选 N 项 + 删除/导出/分享/取消」，顶部提示同步变成「点行选择，再点右上角退出多选」。
- 删除一律弹 `AlertDialog` 二次确认；「导出」是另存一份副本，`output/` 里的原件不动。

> 代价：因为整行都归手势所有，在**行上**左右滑动不能再翻页，请用顶部页签或在列表空白区域滑动。

### 关于页

软件介绍（基于 Chunker 的轻量版）、检查更新（GitHub releases API，取不到就明说取不到，不会假装「已是最新」）、
开发者入口、MIT 与「与 Mojang / Microsoft 无关」的声明。

### 其它

- 两页用 `ViewPager2` 左右滑动切换（`返回` 键在文件页先回转换页）。
- 明暗模式：右上角按钮循环「跟随系统 → 浅色 → 深色」，存 `SharedPreferences`，`LiteChunkApp` 在 Activity 创建前应用。
- 状态栏 / 导航栏内边距统一打在根布局上，内容不再被状态栏压住。
- **图标统一**：整套界面用 Fluent UI Regular 16/20 的填充路径（MIT）重绘，不混用 Material 的描边图标。
  映射：删除 `Delete`、装填 `TextBulletList`、zip（含压缩包入口）`FolderZip`、Java 版存档 `DrinkCoffee`、
  基岩版存档与 mcworld `Cube`、分享 `ShareAndroid`、导入游戏 `Games`、主题 `DarkTheme`、多选 `LineHorizontal3`；
  `Add / MoreVertical / ChevronDown / Folder / Info` 按同一套几何手写。全部 `viewBox 16`、`24dp`、
  `?attr/colorControlNormal` 着色，布局里的 `app:tint` 可覆盖。

## 官网页面

`website/index.html` 是单文件落地的介绍页：纯 HTML/CSS + 少量原生 JS，不请求任何外部资源
（图标是内联 sprite，favicon 是 data URI），所以可以离线打开，也直接扔到 GitHub Pages。
下载按钮指向本仓库的 `releases/latest`，页面里的包体大小与耗时数字都来自上面的实测。

## 相对上游 Chunker 的改动

裁剪：

| 项 | 处理 |
| --- | --- |
| `com.hivemc.chunker.cli`（CLI + Messenger + picocli） | 整包删除，`CLI.main` 里有 `System.exit`，绝不能调 |
| `caffeine` 依赖 | `Resolver.cached()` 改用 `ConcurrentHashMap`（Caffeine 3.x 需要 API 33+ 的 `VarHandle`） |
| `encoding/preview`、`encoding/settings` | 依赖 `javax.imageio` / `java.awt.image`，且 `internal=true`，对手机端无意义 |
| 其他依赖 | 保留 guava / gson / fastutil / lz4 / `com.hivemc.leveldb` |

补丁（源码里留有 `ChunkBridge:` 注释，历史工程名未改）：

1. 资源改为通过 `Res.url(...)` 读取 —— dex 里没有资源，App 把 `assets/engine-res` 复制到私有目录再传给引擎。
2. `Tag.java` —— LZ4/XXHash 固定用 `safeInstance()` 的纯 Java 实现，避开 JNI 与 `sun.misc.Unsafe`。
3. `org/iq80/leveldb/fileenv/ByteBufferSupport` —— 上游静态初始化依赖 `Unsafe#invokeCleaner` 与
   `DirectByteBuffer`，ART 上都没有，会在每次基岩版转换结束时抛 `AssertionError`。本仓库提供同名替代类，
   `engineDex` 把 leveldb jar 里的原始 class 剔除。
4. `java.awt.Color` —— Android 没有 AWT。用 `com.frenetic.litechunk.compat.Color`（不可变 ARGB 值对象）替换，
   10 个文件各改一行 import。
5. `TypeToken` 匿名子类 —— **最容易漏的一个**。R8 会剥掉匿名类的 `Signature` 属性，
   `-keepattributes Signature` 和 `-keep class * extends TypeToken` 都拦不住，真机一构造就抛
   `class ... isn't parameterized`，而因为发生在静态初始化里，整个引擎都不可用。改用
   `com.frenetic.litechunk.compat.Types` 显式构造 `ParameterizedType`。桌面 JVM 永远复现不了。

内存相关的改动（让大世界能在手机上跑起来的关键）：

6. `WorldConverter` 的并发从硬编码 8 改成可配置，并派生三个预算：`inFlightRegions`（2~4）、
   `inFlightColumns`（`threads*32`，8~256）、`levelDBWriteBufferSize`（`threads*16MB`，16~64MB）。
7. `JavaWorldReader#readRegionFiles` / `BedrockWorldReader#readRegion` —— 上游为每个 region、乃至每列立即提交任务，
   一个 region 1024 列，每列一个完整高度的 `ChunkerColumn`，队列几秒内吃光堆。改成按 `inFlightColumns` 分批 +
   `then()` 串联，剩余列数的权重计入进度。
8. `BedrockLevelWriter#openDatabase` —— 上游写死 `writeBufferSize(400MB)`，纯堆内分配，手机 512MB 堆里占 78%。
   改为读转换器上的预算。
9. 进度改为**按已完成 region 数**上报（映射到 0–90%），compaction 阶段显示 0.97 +「整理数据库」。
   仅用 `TrackedTask#getProgress()` 时，分批后会在 87% 停 55~80 秒，看起来像卡死。
10. 实体链路两处修复：
    - `BedrockEntityResolver` 写入 `definitions: ["+minecraft:xxx"]`，否则基岩版丢弃实体；
    - `applyProfile` 的 lean 模式**不再**关实体与方块实体（原先 OOM 降级会整列丢实体，
      这是「JE→BE 后生物和画全消失」的第二条成因）。

## 运行时设计

- **进程隔离**：`ConversionService` 跑在 `:chunker` 进程，独立堆预算；转换崩溃不影响 UI；取消 = 杀该进程
  （Chunker 没有中断 API）。
- **内存自适应**：`MemoryPlan` 按 `Runtime.maxMemory()` 决定档位（每 200MB 堆 1 线程，上限 2）。
  上限是 2 而不是 8：同一世界 8 线程实测比 2 线程更慢，线程越多 GC 压力越大。
  首次失败若是 OOM，自动降 1 线程 + 精简模式重试一次。
- **进度**：200ms 轮询 + `ResultReceiver` 回传，前台服务通知同步。
- **文件**：世界先解压 / 复制到 `cacheDir/flush/job-*`（LevelDB 需要真实文件 mmap，不能直接读写 SAF 路径），
  输出打包成 `.zip` / `.mcworld` 放进 `output/`，再由 SAF `CreateDocument` 导出副本。
- **dex 加载**：`assets/chunker.jar` → `code_cache/engine/chunker-<size>.jar` → `setReadOnly()`
  （Android 14 起动态加载的代码必须来自只读文件）→ `DexClassLoader`。

## 验证状态

下面是一次发布前跑过的实测，用的是玩家真实存档而不是构造样例。

| 场景 | 环境 | 结果 |
| --- | --- | --- |
| 世界 A（Java 1.20.1，278MB / 64 region）→ 基岩 1.26.60 | 桌面 `engineCli`，512MB 堆 / 2 线程 | **77.9 s**，输出 47MB |
| 上一条输出的实体统计 | `actorDump` | `actorprefix` 35 条、7 类，`without definitions: 0` |
| 世界 B（Java 1.20.1，58MB，含 5 幅画）→ 基岩 1.26.60 | 模拟器完整 App 路径 | **176.5 s**，flush 目录已清理 |
| 上一条输出的实体统计 | `actorDump` | 5 幅画全部保留，`Motif` 完好，`without definitions: 0` |
| 世界 C（Java 1.21.8，257MB，实体密集）→ 基岩 | 桌面 `engineCli` | `actorprefix` 1392 条、28 种生物，`without definitions: 0` |
| 世界 D（Java 1.21.8，25MB）→ 基岩 | 模拟器，**release 包（R8）** | **19.3 s**，产物 4.0MB |

UI 侧在模拟器（API 35，堆按真机对齐 512m / 256m）逐项点过：添加与识别、自动切换目标平台、批量转换、
文件页六个操作（导出落到 `/sdcard/Download` 且字节一致、分享走 `FileProvider`、导入无启动器时回退分享、
删除二次确认）、右滑多选、转化列表滑删、明暗三态、关于页明暗两套。

仍需人工确认：

1. 把输出的 `.mcworld` 导入真机基岩版，确认生物与画真的出现在世界里 —— `actorDump` 只能证明数据写对了，
   不能证明游戏认。
2. 低版本设备（Android 8.1 / 9）：`ByteBufferSupport` 的反射 unmap 与 LevelDB mmap 行为未验证。
3. 取消转换（杀 `:chunker` 进程后的清理与通知收尾）只做过代码审查。

## 已知风险

- 最低系统标称 API 27，实测只在 API 35 / 36 上跑过。
- 目标版本列表来自引擎枚举，最新 Java 版本用 `dimensions/<namespace>/<dimension>/region` 新目录结构，
  旧客户端打不开，属预期。
- 转换在 `:chunker` 进程中进行，App 被划掉后任务会继续；重启 App 不恢复进度（成品仍在 `output/`）。
- 关掉 LevelDB 尾部 compaction 后，中间 `.ldb` 是未压缩的，`db/` 目录会比上游大；打包成 `.mcworld` 后回落。
- 卸载重装后系统不一定清理 `/sdcard/Android/data/com.frenetic.litechunk/`。如果它留着且属主还是旧 uid，
  `output/` 里会写不进去（`EACCES`）。启动时会把不可写的 `output/` 改名成 `output.old-<时间戳>` 再新建；
  这层存储不一定允许改名，失败时错误提示会引导用户清除应用数据（不会动原存档）。

## 许可

MIT，见 `LICENSE`。第三方组件（Chunker、Fluent System Icons、Maven 依赖）见 `THIRD-PARTY.md`。
本项目与 Mojang Studios、Microsoft 无关，Minecraft 是 Mojang Studios 的商标。
