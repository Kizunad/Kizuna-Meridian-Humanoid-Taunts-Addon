# 构建与验证

Java 17、Gradle 8.14.6 wrapper、Loom 1.13.6，版本取自 Framework 的版本目录。Framework、Threatening 与 Damage 在 `settings.gradle` 中按
Framework 统一装配清单以源码组合，编译与开发运行使用其当前输出；客户端测试所需的 Human 客户端库按清单读已构建的 JAR。
`fabric/build.gradle` 的 `clearNestedJarCache` 在每次 `remapJar` 前删除 `fabric/build/loom-cache/temp/modprocessing`：
Loom 只按坐标复用这里的嵌套 JAR，SNAPSHOT 坐标不变时会把旧的 server JAR 打进发布 JAR。

```bash
./gradlew test build :fabric:runGametest
xvfb-run -a -s "-screen 0 1280x960x24" ./gradlew :fabric:runClientTest
python3 scripts/check_docs.py
git diff --check
```

`runClientTest` 复用 GameTest 生成的平坦世界设置：每次只把 `build/gametest/world/level.dat` 同步为单人存档
`taunts-bubble-test`，区块重新生成，GameTest 遗留的结构（如信标光束）与上一局的玩家位置、村民都不会带进截图；
截图与聊天文本写到 `fabric/build/bubble-screens/`，结论写到 `fabric/build/clienttest-results.txt`，首行不是 `success=true` 时任务失败。

## JUnit（`server/`）

- `TauntCatalogTest`：随 JAR 发布的 `zh_cn` 恰好覆盖 Threatening 的七个内置物种，每种「对生物」不少于 10 条、
  「对玩家」不少于 50 条，每条恰含一个 `{attacker}`、去掉占位符后不为空；占位符切分；
  缺占位符、多个占位符、只剩空白的台词被拒绝；非对象、版本不对、物种表为空、实体 ID 非法、条数不足、非字符串台词被拒绝。
- `TauntMemoryTest`：500 轮抽取中每一句都不在前 5 句里；恰好 6 句时按周期轮换；冷却在边界 tick 解除。
- `TauntSettingsTest`：默认配置是 `zh_cn`、120 tick、两个开关都开；整数秒被接受；
  `../` 与大写语言代码、负数与 `nan` 冷却、非布尔开关、缺少 `[display]` 被拒绝。

## JUnit（`fabric/`）

- `TauntTextsTest`：生物攻击者在台词里是同一个可翻译组件（键 `entity.minecraft.vindicator`），不是拼好的字符串；
  玩家名保持字面文本；聊天行用 `chat.type.text`，参数依次是说话者与台词，整体浅灰色。

## GameTest（`fabric/src/gametest`）

| 用例 | 断言 |
| --- | --- |
| `playerAttackerHearsOwnName` | 玩家打村民，攻击者收到恰好一条浅灰色聊天：说话者是村民自己的可翻译名字（无业村民的键是 `entity.minecraft.villager.none`），台词里是攻击者的字面名字，台词来自「对玩家」；17 格外的玩家什么都收不到 |
| `vindicatorAttackerIsNamedByTranslationKey` | 卫道士打村民，正好 16 格处的玩家收到一条聊天，台词里是 `entity.minecraft.vindicator` 可翻译组件，台词来自「对生物」 |
| `cooldownSilencesRepeatedHitsForSixSeconds` | 说过一句后立刻再打不说；120 tick 后再打又说，且与上一句不同 |
| `lethalHitStaysSilent` | 致死一击后村民死亡，附近玩家没有收到聊天 |
| `sayCommandSpeaksNowAndCompletes` | `say <uuid> entity` 用「对生物」台词；玩家执行 `say <uuid>` 用「对玩家」台词且台词里是执行者名字；对牛执行失败；`player`、`entity` 可补全 |

## Xvfb 客户端测试（`fabric/src/clientTest`）

单人世界中由服务端线程在玩家视线正前方 5 格生成关掉 AI 的村民，并以玩家攻击打它一下；
聊天与气泡都经正式网络路径到达客户端。任务启动前在运行目录写入 `options.txt`（`lang:zh_cn`），客户端以简体中文启动：

- 收到以「<村民> 」开头的聊天，整行浅灰色，含玩家名，没有残留 `{attacker}` 或 `entity.minecraft.` 翻译键，文本另存为 `chat.txt`；
- 聊天到达 20 tick 后截图 `bubble-visible.png`，3.5 秒时截图 `bubble-late.png`，4 秒加 250 毫秒后截图 `bubble-hidden.png`；
- 画面中上部（宽 30%–70%、高 10%–50%）与消失后相比亮度变化不小于 48 的像素，前两张都不少于 2000 个，
  证明气泡确实画出、3.5 秒时仍在、4 秒后消失。计数写到 `probe.txt`。
- 气泡形状：只认「原画面与白色按底色不透明度混合」的像素（每通道容差 10，亮度至少升 24），
  这样村民轻微移动、天空漂移都不算。它们的外接框上沿两角不能是底色（圆角），
  外接框最下一行（尾巴尖）宽度不超过气泡宽度的 1/4，中心与气泡中心相差不超过气泡宽度的 1/20。
  外接框与尾巴尖写到 `probe.txt`。

## 最近一次结果（2026-10-30）

- `./gradlew test build :fabric:runGametest`：JUnit 12/12（`TauntCatalogTest` 4、`TauntMemoryTest` 3、
  `TauntSettingsTest` 2、`TauntTextsTest` 3），GameTest 5/5。
- `xvfb-run ... :fabric:runClientTest`：`success=true`；简体中文客户端收到的聊天是
  「<村民> 哼！Player131，村长要是知道了，连夜把你列进黑名单！」，气泡折成三行画在村民头顶；
  探测区变化像素 visible=46675、late=45992，4.25 秒后气泡消失；
  气泡外接框 (433,229)–(846,352)，上沿两角不是底色，尾巴尖宽 6 像素、中心 x=639，与气泡中心 639 重合。
- 1.20.1 运行时自带的 Gson 是 2.10，`server/` 编译用 2.10.1，只能用 2.10 已有的 API；
  GameTest 在真实运行时上解析台词表，能发现越界调用。
- Damage 的观察事件 `registerDamageObserver` 仍在 Damage 工作区里未提交；Damage 提交前，
  本仓库只能对着它当前的构建产物编译和测试。
