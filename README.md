# LiteChunk

Android 上的 Minecraft 存档互转工具（Java 版 ⇄ 基岩版）。转换引擎来自
[Chunker](https://github.com/HiveGamesOSS/Chunker) 的裁剪版，通过 `DexClassLoader` 在独立进程中加载。

- 包名 `com.frenetic.litechunk`，版本 `1.21.0 build3 pr6`（与引擎 Chunker 的版本同步）
- 最低系统 Android 8.1（API 27）—— `java.nio.file` 与基岩版 1.26 的共同下限；目标 API 37
- 只做一件事：选存档 → 选版本 → 转换 → 保存 / 分享 / 导入
- MIT 许可，非官方项目，与 Mojang Studios、Microsoft 无关

## 工程结构

```
api/      com.frenetic.litechunk.api    App 与引擎之间的稳定契约（打进 APK，不混入 dex）
engine/   Chunker 裁剪源码 + EngineImpl 门面 + level.dat 读写 + R8 打包任务
app/      UI（转换页 / 文件页 / 存档详情）、文件流、外部导入、:chunker 隔离进程服务、DexClassLoader 加载器
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
Release 正文直接取仓库根目录的 `RELEASE_NOTES.md`（每次发版前重写，只放当前版本的说明）。
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
./gradlew :engine:levelTest --args="<世界目录>"       # level.dat 读写自检（JE 与 BE 都支持）
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
| 内部 `cacheDir/flush/stage-<id>` | 解压后的世界，按队列项复用（详见「暂存与复用」） |
| 内部 `cacheDir/flush/job-*` | 转换期的临时工作目录，任务结束即删 |
| 内部 `cacheDir/detail/<hash>` | 存档详情页抽出的 `level.dat`，离开页面即删 |

## 从别的应用导入

`ImportActivity`（指向 `MainActivity` 的 `activity-alias`）接两种入口：

| 入口 | action | 典型来源 |
| --- | --- | --- |
| 分享 | `SEND` / `SEND_MULTIPLE` | 文件管理器、网盘、微信 / QQ 的「分享」 |
| 打开方式 | `VIEW` + `content://` / `file://` | 微信、QQ、浏览器点开 `.mcworld` |

流程：收到 URI → `Inbox.accept()` 复制到 `inbox/` → 入队并切到转换页 → 自动识别版本。
**必须在入口就复制**：队列只存引用、转换时才读字节，而 `SEND` / `VIEW` 的读授权活不过当前 Activity。

过滤器的 mime 要写宽（含 `*/*`）。微信、QQ 与多数文件管理器对未知扩展名一律报
`application/octet-stream` 或 `*/*`，收窄了它就根本不出现在「打开方式」里；代价是它也会出现在别的文件上，
所以真正的把关在 `Inbox.isArchive()`：不是 `.zip` / `.mcworld` / `.mctemplate` 就一个字节都不复制，只提示忽略。

同名同大小的再次分享复用 `inbox/` 里已有的副本；转换成功后该副本会被删除（那是应用的拷贝，不是用户保存的文件）。

## 界面

### 转换页

- **转化列表**：可批量添加；未开始时左右滑动即可移除，也可以点行尾按钮移除。转换进行中禁止改动列表。
- **输出格式**：Java 版 / 基岩版，目标版本从引擎枚举出的 124 个版本里选。
  - `转出后自动删除原存档`（默认不勾）—— 只在**打包成功之后**才删，失败不会丢原档。
- **输出命名**：由「存档原名 / 版本标记（BE/JE）/ 时间戳」三块拼成，可开关、**按住右侧手柄拖动排序**、
  分隔符可选 `-` `_` 无，默认 `原名-版本-时间`（`草稿-BE-20261027-1652.mcworld`）。点开是带实时预览的
  BottomSheet，设置存 `SharedPreferences`，转换时随 Intent 传给 `:chunker` 进程（跨进程读 prefs 不保证可见）。
  同名不覆盖：`WorldFiles.unique` 自动加 `(2)`。
  - 分隔符下面还有一行 `打包为 .mcworld`（仅目标格式为基岩版时可见，默认勾选）—— 取消后输出 `.zip`。
    它和命名是同一件事（决定最终文件名），所以从格式卡搬到了这里，转换页上不再有勾选框。
- **本轮输出**：本轮产出的清单，每行可「放入转化列表 / 导出为 zip / 导出为 mcworld / 分享 / 导入游戏 / 删除」。
- **转换进行中**：进度卡显示阶段、百分比、已用时，下面一行是一言（每 7 秒换一句，60 条内置短句）。
  开始时页面自动滚到底，卡片底部留 48dp，避免按钮落进系统手势区。

### 为什么转换期间要占住屏幕

`ConversionService` 是前台服务，只能保证进程不被优先杀掉，**不阻止灭屏后 CPU 挂起**——用户看到的现象就是
「切到后台或灭屏，进度就停了，回来才继续」。厂商的电池策略只会让这件事更早发生。三层处理：

- **常亮**：`isRunning()` 时给 MainActivity 的窗口加 `FLAG_KEEP_SCREEN_ON`，结束或 `onPause` 清掉。
  用窗口标志而不是 `WakeLock`，因为它的生命周期跟窗口一致，不会忘记释放。
- **唤醒锁**：`:chunker` 进程在转换期间持 `PARTIAL_WAKE_LOCK("LiteChunk:convert")`，`finally` 里释放。
  只锁 CPU 不锁屏幕，灭屏也能跑完。
- **白名单引导**：首次转换前弹一次「让转换安静地跑完 / Background limits」，按
  `isIgnoringBatteryOptimizations` 判断后跳系统设置；两个按钮都会开始转换，看过一次就不再弹。

### 等待时的一言

短句是 `res/values/quotes.xml` 里的 60 条静态文本，**零依赖、不走网络**——断网超时比没有更烦，且全应用
只该有「检查更新」一个网络请求。

索引是**无状态**的 `quotes[elapsedSeconds / 7 % n]`。`renderInternal` 由 180ms 一次的进度回调驱动，
若用计数器递增，一轮 60 条会在 12 秒内放完。所以 t=3 是第 1 条、t=9 第 2 条、t=54 第 8 条。

pr6 起卡片里**不再有打地鼠小游戏**（`MoleGrid` 连同苦力怕、LiteChunk 剪影两个图案一起移除）：它和转换
抢同一个 CPU 与内存带宽，而这一版的方向恰恰是让转换吃满 CPU，留一个会拖慢自己的入口没有意义。

### 文件页

管理 `output/`：

- **左滑整行**展开六个操作：装填 / 详情 / 导出 / 分享 / 导入 / 删除。
  展开是 `translationX` 位移而不是重新布局，配合 `PathInterpolator(0.2, 0, 0, 1)`、按剩余距离计算的时长、
  甩动速度判定，以及操作条的淡入，拖动跟手且松手后能「顺着力道」吸附。
  - 「导出」：`zip` 与 `mcworld` 字节完全相同、只差扩展名，作为两个相邻的 44dp 按钮是最容易被点错的一对。
    基岩版存档点一下弹自绘 `PopupWindow`（16dp 圆角、两行带图标、向上弹出），选格式后再走 SAF；
    **Java 版存档没有第二种格式可选，直接进保存面板，不弹菜单**。省出来的位置给「详情」。
  - 「详情」：直接进存档详情页，不必再走操作面板。
- **右滑整行**直接进入多选并选中该行；右上角按钮是另一个入口，再次点击退出。
  多选时底部出现「已选 N 项 + 删除/导出/分享/取消」，顶部提示同步变成「点行选择，再点右上角退出多选」。
- 删除一律弹 `AlertDialog` 二次确认；「导出」是另存一份副本，`output/` 里的原件不动。

> 代价：因为整行都归手势所有，在**行上**左右滑动不能再翻页，请用顶部页签或在列表空白区域滑动。

### 存档详情页（`LevelActivity`）

点存档弹出的操作面板里有「存档详情」，文件页左滑的第二格也是同一个入口。读的是 `level.dat`，不是区块。

存在的理由：**很多「转换没解决问题」的疑难其实不是转换**——存档只是需要改名字、改游戏模式，或者声明错了版本。
这类需求走完整转换要解码再编码每个区块（大存档几分钟），还会把引擎不认识的方块换成占位块；
只改头部是毫秒级，而且不动区块。

- **只读诊断**：版本（由 `DataVersion` / `NetworkVersion` 反查）、字节数，以及几条会真正影响游玩的检查
  —— 存档名为空、缺 `DataVersion`、缺出生点、基岩版开了哪些实验性玩法（新方块变空气的最常见原因）。
- **白名单修补**：中文名 + 英文键名 + 当前值，点一下改一项，不做树形编辑器。
  Java 侧 `LevelName / GameType / Difficulty / allowCommands / hardcore / RandomSeed / SpawnXYZ / DayTime`；
  基岩侧 `LevelName / GameType / Difficulty / commandsEnabled / Generator / RandomSeed / SpawnXYZ / Time`。
  `DataVersion`、`StorageVersion`、`NetworkVersion` 一律只读并在下方说明原因：改这些数字只改声明、
  不升级区块数据，反而可能让游戏判定存档损坏。
- **类型安全**：枚举字段只能从固定选项里选，不许手输；难度在 Java 里是 `byte`、基岩里是 `int`，写入时按版本选宽度。
  读取走原始 tag 再走 `Number`——同一个键在不同版本里宽度不同，直接 `getInt()` 遇到 `ByteTag` 会抛
  `ClassCastException`（这个 bug 是 `:engine:levelTest` 抓到的）。
- **写入安全**：写 `.tmp` → **读回校验每个被改字段** → rename 才替换原文件；存档本身是 zip，
  重写走同目录 `.tmp` 再 rename，中途失败原包不动。所以不会写出半坏的包。
- **不自动备份**：`level.dat.bak-*` 和 `output/backup/` 都没有了。以前每次保存都在世界目录里堆一个
  看起来一模一样的 `.bak`，没人找得到、也没人分得清哪个是当前的，最后只会被当成垃圾文件删掉。
  安全兜底改成由用户显式选择——顶栏保存图标 + 汉堡菜单（保存 / 另存为 / 恢复默认值）：
  - **保存**：弹确认框，明确写「原文件不会保留副本，覆盖后不可找回」，并引导用「另存为」。
  - **另存为**：不弹确认，用**新时间戳**生成 `原名-yyyyMMdd-HHmmss.zip|mcworld`，原包不动，完成后回到文件页。
  - **恢复默认值**：丢弃待写改动，回到打开页面时的值，不写盘。
  - 没有改动时退出直接放行；改了东西退出才弹「另存为 / 直接退出 / 保存」。
- 另存为与保存共用一次遍历：`Archive.rewrite(source, target, ...)` 读原包、直接写到目标路径。
  1 GB 的存档先 `cp` 再重写会白读写两遍，这条路径不能那么干。
- 引擎接口 `inspectLevel` / `applyLevelChanges` 同样在 `:chunker` 进程执行——UI 进程不加载 Chunker，
  结果按平行数组跨进程传（`api` 是纯 Java 库，用不了 `Parcelable`）。`applyLevelChanges` 返回 `void`：
  没有备份时间戳可报了。

### 关于页

软件介绍（基于 Chunker 的轻量版）、检查更新（GitHub releases API，取不到就明说取不到，不会假装「已是最新」）、
开发者入口、MIT 与「与 Mojang / Microsoft 无关」的声明。

### 其它

- 两页用 `ViewPager2` 左右滑动切换（`返回` 键在文件页先回转换页）。
- 明暗模式：右上角按钮循环「跟随系统 → 浅色 → 深色」，存 `SharedPreferences`，`LiteChunkApp` 在 Activity 创建前应用。
- 状态栏 / 导航栏内边距统一打在根布局上，内容不再被状态栏压住。
- **图标统一**：整套界面用 Fluent UI Regular 16/20 的填充路径（MIT）重绘，不混用 Material 的描边图标。
  映射：删除 `Delete`、装填 `TextBulletList`、详情 `Info`、导出 `OpenFolder`、Java 版存档 `DrinkCoffee`、
  基岩版存档与 mcworld `Cube`、分享 `ShareAndroid`、导入游戏 `Games`、主题 `DarkTheme`、多选与汉堡
  `LineHorizontal3`、保存 `Save`；
  `Add / MoreVertical / ChevronDown / Folder / Info` 按同一套几何手写。全部 `viewBox 16`、`24dp`、
  `?attr/colorControlNormal` 着色，布局里的 `app:tint` 可覆盖。保存图标例外：禁用态必须走
  `res/color/save_tint.xml` 的 selector，否则灰掉的图标看着仍然可以点。
- **例外**：`ic_logo` 与 `ic_creeper` 来自项目自己的 SVG（不是 Fluent），`ic_drag`（六点拖拽手柄）是手写的
  六个圆。三者也都按 `viewBox 16` 缩放，只是形状不走 Fluent 那套 24dp 网格。
- **对话框标题**统一用 `res/layout/dialog_header.xml`（中文 19sp 大号 + 英文 11sp 小号）配 `setCustomTitle`，
  只用 `setTitle` 会退回单行样式。强调色就是主题的绿色（`@color/accent`）。

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
11. `BedrockLevelWriter` 的 `Generator` 兜底从 `2`（平坦）改成 `1`（无限）。只有引擎不认识的地形类型
    （数据包世界，或 `generator` 节点缺失）才走到这里；声明成平坦会让游戏把一片正常地形说成「超平坦」，
    而未生成的区块按 y=-60 的基岩层生成。改成「无限」至少和玩家看到的相符。
    —— 注：pr3 曾把「产物 Generator=2」当成 bug 写进已知限制，那其实是**测试存档本身都是超平坦**，
    转换正确复现了源存档的平坦地形。用 `minecraft:noise` 世界复测，输出就是 `generatorType=NORMAL`。
12. 写入侧合批：`BedrockColumnWriter#writeColumn` 一列开一个 `WriteBatch`，五个 section 一次 `database.write`，
    不再每条约 28 次单键 `put`；同一条链路上的 5 个写任务 + 每区块子任务合并成 1 个任务在当前线程顺序做完。
13. 进度改 `LongAdder` 计数：上游（以及本仓库早期）是在根任务上递归整棵活着的任务树、每进一个节点拿一次锁；
    现在 `start()`/`free()` 各加一次权重，`getProgress()` 只是两个数相除。
14. 光照数组扁平化：`byte[16][16][16]` → `byte[4096]`（`ChunkerChunk.lightIndex`，`(y<<8)|(z<<4)|x`）。
    每个 section 的光照原来要 273 个数组装 4096 字节，对象头和数据一样大；顺带把读取循环、Java 写出的
    nibble 打包、Bedrock 高度图的三处逐方块访问都变成一次数组索引。同批把
    `ShortBasedPalette.containsValue` 的临时 `ShortOpenHashSet` 换成两个 `long` 位掩码。

## 运行时设计

- **进程隔离**：`ConversionService` 跑在 `:chunker` 进程，独立堆预算；转换崩溃不影响 UI；取消 = 杀该进程
  （Chunker 没有中断 API）。
- **内存自适应**：`MemoryPlan` 按 `Runtime.maxMemory()` 决定档位（每 200MB 堆 1 线程，上限 2）。
  上限是 2 而不是 8：同一世界 8 线程实测比 2 线程更慢，线程越多 GC 压力越大。
  首次失败若是 OOM，自动降 1 线程 + 精简模式重试一次。
- **进度**：200ms 轮询 + `ResultReceiver` 回传，前台服务通知同步。
- **文件**：世界先解压 / 复制成 `cacheDir/flush/` 下的真实文件（LevelDB 需要 mmap，不能直接读写 SAF 路径），
  输出打包成 `.zip` / `.mcworld` 放进 `output/`，再由 SAF `CreateDocument` 导出副本。
- **暂存与复用**：识别与转换都需要解压后的世界，所以暂存目录按队列项命名（`stage-<id>`）而不是匿名 `job-*`。
  识别完不再删除，转换时直接复用——同一个世界以前要解压两遍，现在第二遍是 0 秒（日志里 `read=0.0s(staged)`）。
  完整写入后会落 `.ready` 标记，否则「添加后立刻点转换」可能读到写了一半的世界。
  清理只在转换线程里做（`pruneStagesExcept`），删掉不属于本批次条目的暂存目录——放在 UI 线程会与刚启动的转换打架。
- **两条写入路径**：完整转换走引擎管线（解码区块 → 重新编码），改 `level.dat` 走 `LevelEditor` 直写。
  后者不碰区块，因此没有「引擎不认识的方块变占位块」的代价，也不受堆预算约束；
  前者仍是唯一能跨版本改地形的手段。两者共用同一个 `:chunker` 进程。
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
| 世界 E（Java，压缩前 1.29GB）→ 基岩 | 真机，512MB 堆（未 root） | **290 s**，≈4.5 MB/s |
| 同一次转换的分阶段耗时 | 真机，release 包 | `read=0.2s convert=3.0s pack=0.5s`（25MB 世界） |

世界 E 的数字值得单独说明：日志里进度在 60s/120s/180s/240s 时分别是 14%/32%/57%/73%，而 CPU 平均占用
不到 50%、并且越到后期越低。这是 IO 与 LevelDB compaction 受限，不是 CPU 饱和——所以并发参数（线程数、
写缓冲、在飞 region 数）没有调整的余地。大世界要快，只有换更大的堆（桌面 CLI）或减少工作量。

UI 侧在模拟器（API 35，堆按真机对齐 512m / 256m）逐项点过：添加与识别、自动切换目标平台、批量转换、
文件页六个操作（导出落到 `/sdcard/Download` 且字节一致、分享走 `FileProvider`、导入无启动器时回退分享、
删除二次确认）、右滑多选、转化列表滑删、明暗三态、关于页明暗两套。

`1.21.0 build3 pr2` 新增能力的实测（同一台模拟器，debug 与 release 双包）：

- **导入**：`VIEW` 与 `SEND` 两个入口都能把 `.mcworld` 送进队列并识别出版本；`notes.txt` 被
  `Inbox.isArchive()` 拒绝，`inbox/` 里没有留下任何文件。
- **命名模板**：三块全开 / 只留原名 / 换分隔符，产物名与预览一致；同名第二次转换自动加 `(2)`。
- **暂存复用**：先识别再转换，第二次日志出现 `read=0.0s(staged)`；转换结束后 `inbox/` 与 `stage-*` 都已清理。
- **存档详情**：真实 Java 1.21.8 存档与基岩 1.26.60 产物双向读写通过——改值生效、**未改字段一个都没变**、
  只读字段写入被拒；模拟器上改完拉起 zip 里的 `level.dat` 复核，
  `Difficulty=2`、`GameType=0`，zip 的 7 个条目完整。

`1.21.0 build3 pr3` 的保存模型实测（同一台模拟器，debug 与 release 双包都过）：

| 场景 | 结果 |
| --- | --- |
| 另存为 | 产出 `test-nbt-20261028-051703.zip`（4393840 B），原包 4392799 B **一个字节都没动**；副本 `GameType=0`、原包仍为 `1` |
| 覆盖保存 | 原包变成 4393840 B，没有多出文件，也没有 `.bak` / `.tmp` 残留；zip 65 个条目与原包一致，`testzip()` 无坏项 |
| 未修改退出 | 直接放行，不弹框 |
| 已修改退出 | 弹「另存为 / 直接退出 / 保存」；选「直接退出」确认不写盘 |
| 恢复默认值 | `Difficulty` 从「普通」回到「和平」，脏标记消失、保存图标转灰 |
| 导出二级选择 | Java 存档只出现「导出为 .zip」，点下去进系统 `documentsui` 保存面板 |
| 引擎自检 | `:engine:levelTest` 输出 `no backup files, as intended` / `ALL CHECKS PASSED` |
| R8 release 包 | 详情页与覆盖保存均通过，说明没有依赖反射被裁掉的部分 |

`1.21.0 build3 pr4` 的熬夜修复实测（同一台模拟器 API 35，release 包在 R8 下逐条过）：

| 场景 | 结果 |
| --- | --- |
| 唤醒锁 | 转换中 `dumpsys power` 出现 `PARTIAL_WAKE_LOCK 'LiteChunk:convert'`，结束后只剩无关的 `fiid-sync` |
| 常亮 | `dumpsys power` 出现 `SCREEN_BRIGHT_WAKE_LOCK ... WorkSource{...com.frenetic.litechunk}`；结束后 `dumpsys window \| grep -c KEEP_SCREEN_ON` = 0 |
| 电池白名单引导 | 首次转换前弹一次，两行标题 + 「直接开始 / 去设置」；第二次转换直接开始，不再弹 |
| 一言轮换 | t=3 → 第 1 条，9 → 第 2，17 → 第 3，31 → 第 5，54 → 第 8，与 `elapsed/7 % n` 完全吻合 |
| 自动滚到底 | 开始转换后进度卡整体可见，「取消」按钮能点到 |

（pr4 当时的「俄罗斯方块」一栏已随 pr5 删除该控件而移除，见下表。）

`1.21.0 build3 pr5` 的四项体验修复实测（同一台模拟器 API 35，release 包在 R8 下逐条过）：

| 场景 | 结果 |
| --- | --- |
| mcworld 勾选搬家 | 格式卡的勾选框消失；命名 BottomSheet 里出现在分隔符之上、仅基岩版可见，切换目标格式即时显隐 |
| 勾选状态持久化 | 取消勾选后重开 App 仍是未勾选（`Naming.mcworld()` 存 prefs，pr4 只存在内存里，重启会丢） |
| 拖动排序 | 按住「时间戳」右侧手柄拖到第一位 → 预览变成 `20261029-1258-BE.mcworld`，行不闪、不跳、不从手指下消失 |
| 命名即时生效 | 同一份设置在转换完成后的「本轮输出」里产出同名文件 |
| 导出菜单 | 基岩版存档弹出 16dp 圆角 PopupWindow（`.zip` / `.mcworld`），存成 `.mcworld` |
| Java 存档导出 | 不弹菜单，直接进 `documentsui`，存成 `test-java.zip` |

`1.21.0 build3 pr6` 的性能实测（桌面 JDK 25 跑 `:engine:engineCli`，8 线程）：

| 场景 | 结果 |
| --- | --- |
| 打包时 `.ldb` 直接 STORED | 同一个 162 MB 输出目录：6.998 s → 3.770 s（**−46%**），归档 75.0 MB → 77.1 MB（+2.75%），`unzip -t` 全部通过 |
| palette 写入换 `ByteArrayDataOutput` | JFR 里 `ByteArrayOutputStream.ensureCapacity` 从 23.4% 采样到基本消失；墙钟不变，省的是纯 CPU |
| region 改位置读、去掉 `synchronized(mcaReader)` | 墙钟在噪声内，但删掉了一个把整片 region（1024 列）串行化的锁 |
| 转换总耗时（257 MB 存档） | 24.5 s → 25.0 s（噪声内，**桌面这一版没有提速**） |

上面四项之外，pr6 还有一轮「少分配」的改动（palette 扁平化、region 读零拷贝、`Inflater` 线程本地复用、
热路径去 `Optional`）。A/B 用的是 2.2 GB 真实存档 `存档 A`（`region/` 1.3 GB、546 个 region 文件），
**同一天真回退源码跑**：

| 场景 | 结果 |
| --- | --- |
| 堆峰值（上限 1 GB） | 改动前 1020 / 1023 MB → 改动后 944 / 914 / 894 MB，**稳定低约 100 MB** |
| 墙钟 | 改动前 570.99 / 575.23 s；改动后 542.55 / 562.64 / 654.47 s —— 改后三次自己就差 20%，**噪声大于改动，无法判定** |
| CPU 总时间 | 1618 s → 1666 s（噪声内）。削的是分配不是计算量 |
| 区块输出 | `DbCompare` 逐 key 比字节，**区块记录 0 差异** |
| 实体输出 | 89～155 条差异；同二进制两次运行之间也有 89 条（`actorprefix` / `digp`），属既有非确定性 |

验收判据因此改了：**区块记录必须 0 差异**，实体记录只能看数量级——这套代码的输出本来就不是逐字节确定的。

再往后一轮动的是**写入与调度的骨架**（一列一次 `WriteBatch`、每列 5 个写任务合并成 1 个、进度改原子计数）。
另外两项（执行器 work-stealing、批量屏障改滑动窗口）实现后实测堆峰值 +290 MB，已回退，源码里不留半成品。
同日同机**交替跑**的结果（主指标是进程 CPU 总时间，墙钟只作参考）：

| 存档 | 变体 | 墙钟 | CPU 总时间 | 堆峰值 |
| --- | --- | --- | --- | --- |
| 2.2 GB `存档 A` | 基线 | 1045.3 / 1110.4 s | 3177.0 / 3250.2 s | 907 / 917 MB |
| 2.2 GB 同上 | 本版 | **813.4 s** | **2595.0 s** | 903 MB |
| 257 MB `存档 B` | 基线（5 次） | 51.98 s | 247.6 s | 694 MB |
| 257 MB 同上 | 本版（6 次） | **46.4 s** | **232.1 s** | 671 MB |

**跨时间对比在这台机器上无效**：同一天里同一条基线从 642 s 漂到 1110 s（CPU 1585 s → 3250 s），
因为白天有人共用这 32 核。按「早上基线 / 下午改后」比较会把 −20% 读成 +47%。

光照扁平化那一轮（7 对交替跑，`BASE→NEW` / `NEW→BASE` 轮换以消掉「第二棒总是慢」的顺序偏差）：

| 存档 | 变体 | 墙钟（均值） | CPU 总时间 | 堆峰值 |
| --- | --- | --- | --- | --- |
| 257 MB `存档 B` | 上一状态 | 63.77 s | 290.6 s | 668 MB |
| 257 MB 同上 | 加光照扁平化 + 位掩码 | **61.99 s** | **267.0 s** | **611 MB** |

「每列 3 个任务合成 1 个」也做了、也测了，但墙钟中位 +1.8%、CPU +4.1%，已回退（读与写的重叠被拿掉）。

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
- 常亮 + 唤醒锁解决的是「灭屏后被挂起」，**不是厂商的强制冻结**。部分 ROM 在极低电量或长时间无交互时
  仍会冻结后台进程，白名单引导只是一个引导，能不能免疫取决于系统。
- 关掉 LevelDB 尾部 compaction 后，中间 `.ldb` 是未压缩的，`db/` 目录会比上游大；打包成 `.mcworld` 后回落。
- 存档详情改的是 `level.dat`，不会升级区块数据。改完的存档若正被游戏打开着，游戏退出时会用内存里的旧数据
  覆盖回去——改之前先关掉游戏。
- 存档详情保存要重写整个压缩包（同目录 `.tmp` 再 rename），所以需要**与包体等量**的剩余空间；
  空间不足时写 `.tmp` 会失败，原包保持不动。
- **保存没有后悔药**：写入链路能保证不写出半坏的包，但覆盖成功之后旧内容就没有了（不再有 `.bak`）。
  想留原档只能用「另存为」。
- 卸载重装后系统不一定清理 `/sdcard/Android/data/com.frenetic.litechunk/`。如果它留着且属主还是旧 uid，
  `output/` 里会写不进去（`EACCES`）。启动时会把不可写的 `output/` 改名成 `output.old-<时间戳>` 再新建；
  这层存储不一定允许改名，失败时错误提示会引导用户清除应用数据（不会动原存档）。

## 许可

MIT，见 `LICENSE`。第三方组件（Chunker、Fluent System Icons、Maven 依赖）见 `THIRD-PARTY.md`。
本项目与 Mojang Studios、Microsoft 无关，Minecraft 是 Mojang Studios 的商标。
