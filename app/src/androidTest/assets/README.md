# 测试存档不随仓库提供

`EngineDeviceTest` 会从 `app/src/androidTest/assets/` 读两个世界存档：

| 文件 | 内容 |
| --- | --- |
| `test_world.zip` | 任意一个小的 Java 版世界，用于冒烟跑通转换 |
| `real_world.zip` | 一个真实玩过的世界，用于验证实体、容器等边界情况 |

仓库里没有它们：这两个文件来自玩家的私人存档，体积也在几 MB，不适合放进公开仓库。
要跑 `connectedAndroidTest`，自行放入同名文件即可：

```bash
# 世界文件夹或已打包的 zip 都可以，注意 zip 内要能定位到 level.dat
zip -r app/src/androidTest/assets/test_world.zip <your-world-folder>
```

没有这两个文件时 `assembleDebug` / `assembleRelease` 不受影响，只是无法运行仪器测试。
