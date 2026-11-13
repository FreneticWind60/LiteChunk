# LiteChunk

Android 上的 Minecraft 存档互转工具（Java 版 ⇄ 基岩版）。转换引擎来自
[Chunker](https://github.com/HiveGamesOSS/Chunker) 的裁剪版，通过 `DexClassLoader` 在独立进程中加载。

- 包名 `com.frenetic.litechunk`，版本 `1.21.0 build4 pr1`（build4 线的第一个预览，与引擎 Chunker 的版本同步）
- 每个版本的新特性与已知局限见 [`VERSION.md`](VERSION.md)
- 最低系统 Android 8.1（API 27）—— `java.nio.file` 与基岩版 1.26 的共同下限；目标 API 37
- 只做一件事：选存档 → 选版本 → 转换 → 保存 / 分享 / 导入
- MIT 许可，非官方项目，与 Mojang Studios、Microsoft 无关

## 工程结构

```
api/      com.frenetic.litechunk.api    App 与引擎之间的稳定契约（打进 APK，不混入 dex）
engine/   Chunker 裁剪源码 + EngineImpl 门面 + level.dat 读写 + R8 打包任务
app/      UI（转换页 / 文件页 / 存档详情）、文件流、外部导入、:chunker 隔离进程服务、DexClassLoader 加载器
website/  官网（多页静态站：首页 / 文档 / 更新日志 / 关于，零构建、零外部依赖）
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
Release 正文直接在 GitHub 的 Release 表单里写，**不放进仓库**（仓库里曾经有一个 `RELEASE_NOTES.md`，因为会把测试存档的路径写进公开历史，已弃用）。
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

pr5 起卡片里**不再有打地鼠小游戏**（`MoleGrid` 连同苦力怕、LiteChunk 剪影两个图案一起移除）：它和转换
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
  文案跟随界面语言：引擎只交双语字面量（`labelZh` + `labelEn`），日语界面会显示中文标题，所以每个字段带一个
  稳定的 `hintCode`（就是 NBT 键名，和进度阶段码一个思路），App 侧 `LevelText` 拿它查 `R.string`，
  查不到的才回落到引擎给的文字；选项与提示同理。第二行仍显示原始键，那是给查过资料的人看的文档，不翻译。
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

`website/` 是直接打开就能看的多页静态站：`index.html`（首页）、`doc.html`（使用文档与常见问题）、
`log.html`（更新日志）、`about.html`（作者、社区、友链、许可），样式与脚本分别是 `common.css` / `common.js`。

没有框架、没有构建步骤，图标全部内联 SVG，字体走系统栈，所以除「更新日志」会读取仓库里的
[`VERSION.md`](VERSION.md) 之外不产生任何外部请求。下载按钮指向本仓库的 `releases/latest`，
页面里的包体大小与耗时数字都来自下面的实测。

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
15. 调色板位打包去重算：`bedrock/PaletteUtil` 的打包/解包用一张 4096 项的置换表替掉逐格的
    `(x<<8)|(z<<4)|y ⇄ (y<<8)|(z<<4)|x` 转置，`shift` 改增量，打包直接写 `byte[]` 小端，不再先填 `int[]`
    再复制一遍。`java/PaletteUtil` 的 1.16 编解码把「魔数乘法模拟 `i / valuesPerLong`」换成按 long 外层、
    long 内递增移位，随之失效的 `MAGIC` 表一并删除。
16. 导入时先认容器：`WorldFiles.sniff` 读文件头判断 zip / RAR / 7z / gzip，`tar` 按扩展名。
    此前 `ZipInputStream` 对非 zip 只返回「没有条目」而不报错，于是用户看到的是「缺少 level.dat」，
    指向了完全错误的方向；解压出空目录与「有内容但没有 level.dat」现在分开报错。
17. 内置解压器（Arcana，Apache-2.0，纯 Java 无 JNI）：`Archives` 把 RAR 4/5、7z、TAR、TAR+GZIP
    接到同一条导入链路上。Zip 仍走 `java.util.zip`（更快、零成本）。RAR/7z 需要随机读取，
    所以从分享流进来时先落一次盘再解；TAR/TAR.GZIP 直接流式解。并行度压到 2，RAR 解码器的
    控制台进度条关掉。包体代价 +95 KB。

18. 多语言：界面文案原来全部写死在布局与代码里（布局 83 处、Java 约 200 条），现在抽成资源，
    提供 `values`（简体中文）/ `values-en` / `values-ja` 三套，外加 `values-*/quotes.xml` 的英日一言。
    代码里没有 `Context` 的地方（文件处理、压缩包识别、错误映射）走 `Text.get()`，它用保存的语言偏好
    构造 `Resources`；这一步是必须的，因为转换跑在 `:chunker` 进程，AppCompat 的 per-app locale 在那种进程里
    没有 Activity 可以重建。语言入口在「关于 → 语言」，默认跟随系统，切换用
    `AppCompatDelegate.setApplicationLocales()`，Android 13+ 同时注册进系统的应用语言设置。

19. 实体转换（可选，独立第二遍）：方块与方块实体转换完成后，再从 Java 的
    `entities/r.X.Z.mca` 单独跑一遍实体（26.1 起路径在 `dimensions/<namespace>/<dimension>/`，
    旧布局仍兼容），写 `actorprefix` 与 `digp`。转换页有「同时转换实体」开关，默认开；
    关掉时第二遍完全不跑，输出里没有任何 actor 记录。
    - **组件组与变体**：`definitions` 从行为包里生成（129 个实体），变体的整数也从包里的组件组读取
      （`cat_tabby` 声明 `minecraft:variant: 8`），不猜任何基岩枚举。
    - **载荷实体**：掉落物（`Item`/`Health`/`Age`）、经验球（`experience value`）、落沙（`FallingBlock`）、
      TNT（`Fuse`）、末影水晶（`ShowBottom`）。
    - **装备**：`Mainhand` / `Offhand` / `Armor`（固定 5 槽）；兼容 1.21.5 起的 `equipment` 复合标签
      与更早的 `ArmorItems` / `HandItems`。
    - **模板**：`CustomName` / `CustomNameVisible` / `Persistent` / `Attributes`（血量，上限优先取
      Java 的 `max_health`，其次行为包默认值）。
    - **骑乘装备**：1.21.5 起 Java 把鞍、马铠、狼铠、羊驼地毯都放进 `equipment.body`，基岩侧拆成
      `Saddled` + `<mob>_saddled` 组与 `Armor[4]`；鞍不占护甲槽。更早的 `SaddleItem` / `ArmorItem`
      / `DecorItem` 有补落分支。
    - **姿态**：`CollarColor` → `Color`，`Sitting` → `Sitting`（骆驼用 `camel_sitting` 组）。
    - **村民**：`VillagerData` 的职业/等级/生物群系映射成 `+<profession>`、`+<biome>_villager` 与
      `TradeTier` / `TradeExperience`。
    - **交易**：写基岩的 `Offers.Recipes`（`buyA` / `buyB` / `sell` / `maxUses` / `uses` / `traderExp` /
      `rewardExp` / `priceMultiplierA` / `tier`），物品走引擎的物品解析，附魔与 components 一起带过去。
      因为基岩拿到 `Offers` 就**整体替换**自己生成的那一套，所以只对**玩家交易过**（任一配方 `uses > 0`）
      的村民整份搬运；一个都没交易过的村民不写，继续让游戏按职业等级生成。`demand` / `specialPrice`
      基岩没有对应字段，丢弃。
    - **热带鱼**：`Variant` 打包整数拆成 `Color` / `Color2` / `MarkVariant`。
    - **拴绳与乘客**：`leash` → `LeasherID`（持有者的 `UniqueID`）；Java 嵌在载具 `Passengers` 里的骑手会被
      拆成独立 actor，两端各带一条 `LinksTag`——载具写 `{entityID, linkID, RideID:0, VehicleID:<骑手>}`，骑手写
      `{RideID:<载具>, VehicleID:0}`。这个形状不是推导出来的：拿一台真机把转换后的世界进游戏、退出保存，
      游戏自己写回来的就是这两对字段（`RideID` = 我骑谁，`VehicleID` = 谁骑我），所以两套拼写都写上。
      `linkID` 是座位号，按 Java `Passengers` 的顺序编号——两个乘客写成同一个号，进游戏就会叠在一个座位上。
      鞍与驯服状态只按 Java 的实际情况写：**基岩的 `minecraft:rideable` 在马上写的是 `family_types: ["player"]`**
      （猪、骆驼、驴、骡同样），所以「猪骑野马」这类 Java 才有的组合在基岩没有座位，补鞍补驯服也换不来，
      骑手会站到旁边——这不是转换丢了，是对面不支持。两端能各自算出对方的 id，
      是因为 actor 的 `UniqueID` 由实体 UUID 派生（splitmix64），载具与骑手在不同 region、并行转换时也对得上号。
      拴在栅栏上的绳子另说：1.21 起 Java 把栅栏的**方块坐标直接写在动物身上**
      （`leash:[I;x,y,z]` 加 `home_pos`/`home_radius`），存档里根本没有绳结实体，所以直接翻成基岩的
      `hasBoundOrigin` + `boundX/Y/Z`；更早的版本是指向绳结实体的 UUID，那种世界才会先扫一遍全世界定位绳结。
      但实测发现基岩那边**不认这三个键**：它要求栅栏处有一个独立的绳结 actor（`identifier: minecraft:leash_knot`，
      `definitions` 为空，`Pos` 在方块中心上方 0.25 格），动物用 `LeasherID` 指向它的 `UniqueID`。所以转换时会按
      方块坐标哈希出一个绳结 id，并在栅栏所在列补写这个 actor（那一列常常没有别的实体，digp 要单独合并）。
      `NoAI` 不转换：试过原样写进 actor，进游戏无效，基岩没有等价开关。
    - **容器**：矿车的物品写进 `ChestItems`（游戏自己的存档就是这么存的，写成 `Items` 会被整份丢掉，箱子矿车
      过去就是空的）；带箱的驴、骡、羊驼用 `Items`，并补 `Chested`。矿车的 `Pos` 还要抬高 0.35 格：Java 记的是
      脚底，基岩把矿车定在 0.7 高碰撞盒的中心，原样写进去第一 tick 就会掉穿脚下的方块，箱子矿车埋进草方块里
      既看不见也打不开。
    - **画与展示框**交给引擎自己的处理器（需要 Motif、朝向、挂靠方块与半格偏移，通用形状表达不了）。
      展示框在基岩是**方块 + 方块实体**，不是 actor：实体那一遍会跳过它们，改由列写入前的
      `shouldRemoveBeforeWrite` 放下方块并附上方块实体。为此 `Converter` 加了
      `shouldReadEntitiesForBlocks()`——第二遍接管实体时仍然让列读取实体，否则展示框进不了列。
    - 引擎侧的改动只有五处（编排、实体写入门控、api 默认方法、两个 resolver 访问器）。

20. 反向实体转换（基岩 → Java）：这条方向不走第二遍，而是借引擎自己的实体链路。基岩侧只给画、展示框、
    垫子注册了 handler，其它生物一律以 `UnknownEntity` 读入，而通用写法会把整段基岩 NBT 原样抄进 Java
    （字段名不对、坐标是 float、没有 UUID），游戏要么忽略要么直接丢弃。所以读取时把 actor 原始 NBT 和读取
    用的 resolver 一起挂在实体上，Java 写出前由 `BedrockToJava` 翻译成真正的 Java 实体标签。
    - **骨架**：`id` 走引擎的类型表（`zombie_pigman`、`tropicalfish`、`ender_crystal` 会换回 Java 名）、
      坐标/速度/朝向（float → double）、`UUID`（由基岩 `UniqueID` 哈希成 v4，同一存档两次转换结果一致）。
    - **状态**：`Saddled` → `equipment.body`（1.21.5+）或 `SaddleItem`；`Color` → `CollarColor`；
      `Sitting`；村民的组件组与 `TradeTier` → `VillagerData{profession, level, type}` 与 `Xp`。
    - **装备**：`Mainhand` / `Offhand` / `Armor`（头胸腿脚 body）→ `equipment`（1.21.5+）或
      `HandItems` / `ArmorItems` / `ArmorItem`；物品走引擎的物品表，`Name:""` 的空槽跳过。
    - **载荷**：掉落物的 `Item` 转成 Java 物品栈，经验球的 `experience value` → `Value`；血量统一写 float，
      避免把基岩偶尔写成 short 的 `Health` 带进 Java。
    - **变体**：基岩的整数先查行为包组表还原成语义，再转成 Java 的表示。羊和马的编号两边一致，直接搬；
      猫、狼是注册名（`minecraft:tabby`）；兔子、鹦鹉、羊驼、蝾螈是索引，其中**蝾螈的顺序两边不同**；
      潜影贝是**反着**的染料序（基岩 15 = 白）；热带鱼把 `Color` / `Color2` / `MarkVariant` 重新打包成
      一个整数。狐狸、蘑菇牛、青蛙、熊猫的变体只存在于组件组里，从 `definitions` 读。
    - **容器**：驴、骡、羊驼的 `Chested` → `ChestedHorse`，`Items` 逐项换成 Java 物品栈并保留 `Slot`；
      箱子矿车与漏斗矿车同理。
    - **拴绳**：`LeasherID` 哈希成 UUID 写进 `leash`（1.20.5+）或 `Leash`，和那条实体自己写出的 UUID 相同，
      所以绳子挂得上；玩家拴的挂不上，因为玩家 UUID 推不出来。
    - **乘客**：读取时在同一列里按 `LinksTag` 把骑手配给载具并从列里移走，写出时嵌进 `Passengers`
      （栈式骑乘会递归，最多 8 层）。方向从字段读，不靠猜：`VehicleID` 非零表示「我载着它」，`RideID` 非零表示
      「我骑着它」，两个都没有才按旧的 `entityID` 当作骑手。两端都带链接的存档必须这么判，否则会把载具认成乘客、
      连成一圈。**只有被载具点名的骑手才会被移出列**：单边链接、跨区块边界的骑手都保留为普通实体——丢掉挂接
      可以重来，丢掉实体不行。
    - **还没做的**：拴绳结、跨区块边界的骑手（会以普通实体出现，位置对但没骑上），以及只存在于基岩、没有
      任何方块数据的列——引擎遍历列时会跳过它们，这类列里的实体会在转回 Java 时消失。
    - 引擎侧的改动只有三处：`Entity` 加 `sourceResolvers` 与 `passengers` 两个字段、
      `BedrockColumnReader`（保留原始 NBT 并在列内配对乘客）、`JavaEntityResolver.from` 增加一条分支；
      其余全在新的 `entities/Java*.java` 里。

21. 粒子命令（`/particle`）在写出时重写：两边的粒子名是**完全不同的词表**（Java 的 `flame` 是基岩的
    `basic_flame_particle`，`totem_of_undying` 是 `totem_particle`），命令语法也不一样——Java 是
    `particle <name> <pos> <delta> <speed> <count> [force|normal]`，基岩只有 `particle <id> [x y z]`。原样搬运的
    结果是基岩把整条命令判为非法，命令方块照常存在但不再产出任何东西，看起来就是「粒子丢了」。
    - **只动 `particle` 这一条命令**：命令方块写出时按目标版本重写 `Command` 字段，名字查映射表，Java 那边
      多出来的 `delta` / `speed` / `count` / `force` / `viewers` 全部丢掉（基岩没有这些参数）。
    - **`execute ... run particle` 也处理**：前缀原样保留，只重写 `run` 之后的部分。两边的 `execute` 语法本身
      也有差异，改它只会把失败点挪到更难看见的地方；转换不了就整条不动。
    - **映射表只收语义相同的粒子**。基岩没有对应物的（`ash`、`crimson_spore` 这类跟着生物群系渲的颗粒，
      以及 `item_slime`、`item_cobweb` 这种只有 Java 有的）故意不映射，命令保持原样——变成「看起来不对的
      粒子」比「什么都不发生」更难排查。表在 `commands/ParticleIds`，约 110 对。
    - **1.20.5 前后两种选项写法都认**：新的 `dust{color:[1,0,0],scale:1}` 和旧的 `dust 1 0 0 1`，前者选项粘在
      名字上，后者是独立参数，跳过数量不同。
    - 验证：`gradlew :engine:particleSmoke` 覆盖双向 12 个用例；再用一个真的命令方块世界走完整链路
      （`gradlew :engine:blockEntityDump` 查基岩库里的 `Command` 字段），
      `particle minecraft:flame ~ ~1 ~ 0.1 0.1 0.1 0.05 20` → `particle minecraft:basic_flame_particle ~ ~1 ~`。

## 运行时设计

- **进程隔离**：`ConversionService` 跑在 `:chunker` 进程，独立堆预算；转换崩溃不影响 UI；取消 = 杀该进程
  （Chunker 没有中断 API）。
- **多语言**：`values` / `values-en` / `values-ja` 三套资源；没有 `Context` 的代码用 `Text.get()`，
  它在 `Application.onCreate` 里按保存的语言偏好建立 `Resources`，因此 `:chunker` 进程里的报错也跟 UI 一致。
- **内存自适应**：`MemoryPlan` 按 `Runtime.maxMemory()` 决定档位（每 200MB 堆 1 线程，上限 2）。
  上限是 2 而不是 8：同一世界 8 线程实测比 2 线程更慢，线程越多 GC 压力越大。
  首次失败若是 OOM，自动降 1 线程 + 精简模式重试一次。
- **进度**：200ms 轮询 + `ResultReceiver` 回传，前台服务通知同步。
- **LevelDB 在 Android 上不用 mmap**：这个库的 mmap 实现是照着桌面 JDK 写的——`MMRandomInputFile` 调
  `MappedByteBuffer.duplicate()`，`MMWritableFile` 调 `force()` 和 `position()`，而这些方法的协变返回类型
  （返回 `MappedByteBuffer` 而不是 `Buffer`）是 **JDK 13** 才加的，Android 到 13（API 33）才有。之前的版本在
  Android 12 及以下会直接抛 `NoSuchMethodError: duplicate()Ljava/nio/MappedByteBuffer;`，一个区块都读不到，
  转换在打开存档库的那一刻就失败。`EngineImpl` 的静态块在 Android 上设 `leveldb.mmap=false`，`LiteChunkApp`
  再设一次兜底（`:chunker` 进程同样会走 `Application.onCreate`），LevelDB 改走 `FileChannel`。
  桌面保持 mmap，下面所有耗时数字都是在 mmap 下测的。
- **引擎不能调用 Android 13 之后才有的 `java.*` 方法**。R8 会把 `List.of` / `Set.of` / `Map.of` 这类 Java 9 的
  工厂方法降级成 Guava 或 `Arrays.asList`，但**不会**处理 `List.toArray(IntFunction)`（Java 11）、
  `Stream.toList()`（Java 16）、`String.formatted()`（Java 15）、`StringBuilder.isEmpty()`（Java 15）和
  `Inflater.setInput(ByteBuffer)`（Java 11）——这些在 API 33 之前都不存在，一调到就 `NoSuchMethodError`，
  而且异常会被任务调度器盖成一句 `Unable to free task which has not started`，完全看不出原因。
  现在这些调用全部换成了 Java 8 的写法；`org.iq80.leveldb.util.ZLib` 是 leveldb jar 里唯一走
  `Inflater`/`Deflater` 的 ByteBuffer 重载的类，所以和 `ByteBufferSupport` 一样在打包时被剔除，换成
  `engine/src/main/java` 下的同包实现（见 `engineDex` 里的 `repackJar`）。
  校验方式是拿 dex 与装好的 API 27 平台库逐条比对方法引用，当前只剩两条：Guava 里被 try/catch 兜住的
  `findVarHandle`，以及 mmap 关闭后不会执行的 `MappedByteBuffer.duplicate()`。
- **文件**：世界先解压 / 复制成 `cacheDir/flush/` 下的真实文件（LevelDB 只能按路径打开，不能直接读写 SAF 路径），
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

## 已知风险

- **压缩包支持 zip / `.mcworld` / RAR 4·5 / 7z / tar / tar.gz**（内置 Arcana 裁剪版，纯 Java）。
  仍不支持 bz2、xz、lzh、cab、iso，也**不支持分卷压缩包**（`.part1.rar`、`.r01`、`.7z.001` 这类只认单个文件，
  选其中一卷会报错），**加密压缩包不会弹密码框**，同样会报错后退出。这些情况都提示「先解压成文件夹再导入」。
- 最低系统 API 27，主流程在 API 27 与 API 35 / 36 上验证过；RAR / 7z 导入与分享入口在真机上走过一遍。
- 目标版本列表来自引擎枚举，最新 Java 版本用 `dimensions/<namespace>/<dimension>/region` 新目录结构，
  旧客户端打不开，属预期。
- 转换在 `:chunker` 进程中进行，App 被划掉后任务会继续；重启 App 不恢复进度（成品仍在 `output/`）。
- **实体转换仍是「尽量还原」而非逐字节等价**。Java → 基岩：村民只还原职业、等级与生物群系，交易清单交给
  游戏重新生成；骑乘的 `LinksTag` 现已按**游戏自己保存出来的格式**写（两端各一条，`RideID` / `VehicleID` 与
  `entityID` / `linkID` 并存）；矿车的箱内物品写进 `ChestItems`（游戏自己的存档就是这么存的，写在 `Items` 会被
  整份丢掉），带箱的驴与羊驼仍是 `Items`。**生物骑生物在基岩只能还原一半**：船、矿车、鸡、蜘蛛这类
  `rideable` 放开了 `family_types` 的组合能保住，而马、猪、骆驼、驴的座位只认玩家，Java 里「猪骑野马」过去后
  骑手会站到旁边。基岩 → Java：装备、坐骑装备、项圈颜色、坐下、村民职业等级、变体、
  容器内容、拴绳与乘客都会跟着走，但**跨区块边界的骑手不会配对**（会变成同一位置的两个实体），
  玩家拴的绳子挂不上；J→B 时写进「没有任何方块的列」的实体，转回 Java 时会消失
  （真实存档不会出现这种列）。速度或坐标超出浮点范围的实体（作弊、损坏的存档）会被归零，否则基岩会把 actor
  当成无效数据丢掉，或让它以天文数字的速度撞碎在地上。两个方向的实体记录本身都不是逐字节确定的，两次转换的
  输出在实体顺序上就会有差异（区块数据是一致的）。
- **粒子只在命令的载体里被重写**。存档本身不存粒子（Java 的粒子是客户端代码，基岩的是资源包定义），能被转换的
  只有存着 `/particle` 的那条命令——普通命令方块与命令方块矿车（后者的命令写在 actor 上，以前整条丢掉了）。
  数据包或函数文件里的粒子不随存档转换；没有映射的粒子（`ash`、`crimson_spore`、`item_slime` 等）命令保持原样，
  进游戏会报参数错误，至少能看出是哪一条没转。
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
