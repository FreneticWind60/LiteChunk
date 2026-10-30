# 1.21.0 build3 pr6

这一版做两件事：**把打包时间砍掉一半**（少了 46%），以及**删掉等待时的小游戏**——它和转换抢同一个 CPU，
和「让转换吃满 CPU」的方向是反的。

> **升级前请读这一条**：本版**移除了打地鼠小游戏**，进度卡下方的「等待时玩点游戏？」入口、苦力怕与
> LiteChunk 剪影两个图案一起删除，装上新版之后就没有了。除此之外**没有不兼容变化**：`versionCode`
> 从 `121_000_305` 提到 `121_000_306`，签名不变，存档、设置、已有输出都不受影响，直接覆盖安装即可。

## 本版亮点

### 打包快一半：已经压过的不要再压

转换完成后，`.zip` / `.mcworld` 里的 `db/*.ldb` 是 LevelDB 的表文件，而引擎写它们时已经按块做了
zlib 压缩（`CompressionType.ZLIB_RAW`）。打包阶段再 deflate 一遍是纯浪费：

```
db/*.ldb  66.1 MB → gzip -6 后 64.0 MB（96%），耗时 3.73 s   ← 已压缩，再压等于白烧 CPU
db/*.log 103.3 MB → gzip -6 后 11.0 MB（10%），耗时 3.00 s   ← WAL 未压缩，必须继续压
```

所以规则改成**按扩展名分**：`.ldb` / `.sst` 直接 STORED，`.log` 保持默认压缩，`.mca` 继续用最快的 1 级。
实测（同一个 162 MB 输出目录，跑的是 App 里真实的 `WorldFiles.zip`）：

| | 打包耗时 | 归档体积 | `.ldb` 条目 |
| --- | --- | --- | --- |
| 旧规则 | 6.998 s | 75,040,297 B（71.6 MiB） | 压到 96% |
| 新规则 | **3.770 s（−46%）** | 77,105,846 B（73.5 MiB，+2.75%） | 100%（STORED） |

代价是归档大 2.75%，换回来接近一半的打包时间——1.2 GB 的世界大约能省 15～25 秒。
`unzip -t` 全部通过，解压出来一个字节都不差。

顺带纠正了一条错注释：`WorldFiles` 里原来写着「80 MB 的 db 还能压到 38%」，那是在小世界上测的，
当时数据几乎都还在未压缩的 `.log` 里。**规则必须按扩展名分，不能按目录分。**

### 写区块少烧一份 CPU

JFR 采样里最刺眼的一项：`ByteArrayOutputStream.ensureCapacity` 占了 **23.4%** 的自采样。原因是每个
section 都 `new ByteArrayOutputStream()`（初始只有 32 字节）再套一个 `DataOutputStream`，于是
1024 个 word × 4 字节的写入全部走**逐字节**的 `synchronized write(int)`，缓冲区还要一路 32→64→…→8 KB
地翻倍拷贝。

现在改成预设 16 KB 的 `ByteArrayDataOutput`，那 23.4% 的采样基本消失。桌面墙钟没有变化（本来就不是
墙钟瓶颈），省下来的是**真实的 CPU**——手机上每方块 CPU 比桌面贵得多。

### 读 region 不再排队

`JavaWorldReader` 原来在每个 region 上 `synchronized (mcaReader)` 之后才读列，注释写的是「读必须同步」。
实际上 `MCAReader` 用的是 `RandomAccessFile` 的单文件指针，锁是为了保护 seek/read 这一对。

现在改成 `FileChannel.read(ByteBuffer, long)` 的**位置读**——偏移量跟着调用走，天然可以多线程并发，
那把把所有 worker 串在一片 region（1024 列）上的锁直接删掉。桌面测不出差别（读只占整体一小部分），
但低端设备 IO 慢时锁持有时间更长，这一步是纯赚。

### 小游戏删掉了

「等待时玩点游戏？」这个入口存在的逻辑是「等得无聊时打发时间」，但它和转换抢同一个 CPU 与内存带宽，
在本来就要吃满 CPU 的版本里是自相矛盾的。这一版整个移除：`MoleGrid`、苦力怕图标、LiteChunk 剪影、
入口文案与布局一起删，APK 小 15 KB。进度卡下方现在只有一言，其余不变。

### 一句实话：转换本身没变快

同一份 257 MB 真实存档（8 线程，桌面 JVM）：**24.5 s → 25.0 s，在噪声内**。

挖下去的原因写在 `docs/engine-perf-analysis.md`（工程内文档，没随仓库发布）：8 个 worker 有 89% 的时间
在等下一跳，每个 column 要走十来跳「读 → 解压 → 合并 → 建 palette → pretransform → 写 → 每 section
一个子任务」，每一跳都过一次 `PriorityBlockingQueue` 和一次完成回调。提高 `inFlightRegions` /
`inFlightColumns` 只是更吃内存（901 MB），并没有变快；加线程从 8 到 16 也完全没有收益。

所以这一版没有承诺提速，只削掉了两处确定性的浪费（打包时间、CPU 占用）。真正的提速要动调度层——
把任务图改成「一个任务处理一批 column」，那是下一版的事。

## 安装

- 包名 `com.frenetic.litechunk`，`minSdk 27`（Android 8.1），`targetSdk 37`
- 签名与 build1 / build2 / build3 pr1～pr5 一致，可以直接覆盖安装
- `versionCode 121_000_306`，高于 build3 pr5（`121_000_305`）
- 文件：`LiteChunk-1.21.0-build3-pr6.apk`，2.6 MB（2,747,634 B）
- SHA-256：`9b03bf2faf5946f00c8bee43ca25cd0d9bc5f10913e449f10e15bbfd9341eac1`

## 验证

桌面 JVM（JDK 25 / `:engine:engineCli`，257 MB 真实存档，`BEDROCK_1.26.60`，8 线程）：

| 场景 | 结果 |
| --- | --- |
| 输出正确性 | 自写 `DbCompare` 逐 key 比对 value 字节，与 pr5 基线一致（实体 `digp` 里的随机 ID 除外，那是既有行为） |
| 打包规则 | 同一个输出目录 6.998 s → 3.770 s，归档 +2.75%，`unzip -t` 全部通过 |
| palette 写入 | JFR 自采样里 `ByteArrayOutputStream.ensureCapacity` 从 23.4% 降到不可见 |
| region 位置读 | 与旧实现输出一致；`synchronized(mcaReader)` 已删除 |
| 引擎自检 | `:engine:smokeTest` 版本表 / 检测全部通过 |
| 构建 | `:app:assembleRelease` 通过；APK 内 `classes.dex` 已无 `MoleGrid` 残留 |

## 已知限制

- **转换速度没有提升**（桌面 24.5 s → 25.0 s）。瓶颈已定位到引擎的任务调度链，本版不动它。
- 归档比上一版**大 2.75%**，这是 `.ldb` 不二次压缩的直接代价，换来接近一半的打包时间。
- 小游戏没有了，等待时只剩一言。想让它最快跑完就别在这个界面上划来划去。
- 常亮 + 唤醒锁解决的是「灭屏后被挂起」，**不是厂商的强制冻结**，白名单引导只是引导。
- 空间检查是估算（源大小 × 2），只说「可能不足」，不代表一定失败或一定成功。
- 存档详情改的是 `level.dat`，不升级区块数据；改之前请先关掉游戏。
- **保存没有后悔药**：写盘链路能保证不写出半坏的包（`.tmp` → 读回逐字段校验 → rename），
  但覆盖成功之后旧内容就没了，想留原档只能用「另存为」。
