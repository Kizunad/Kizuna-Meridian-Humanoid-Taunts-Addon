# 构建与验证

Minecraft 26.2、Java 25、Gradle 9.8.0 wrapper、Loom 1.19.3（26.x 不混淆，不重映射），版本取自 Framework 的版本目录。
Framework、Threatening 与 Damage 在 `settings.gradle` 中按 Framework 统一装配清单以源码组合，
编译与开发运行使用其当前输出；客户端测试所需的 Human 客户端库按清单读已构建的 JAR。

```bash
./gradlew test build :fabric:runGametest
env -u DISPLAY -u WAYLAND_DISPLAY LIBGL_ALWAYS_SOFTWARE=1 JAVA_TOOL_OPTIONS=-Dfabric.noGui=true \
  xvfb-run -a -s "-screen 0 1280x960x24" ./gradlew :fabric:runClientTest
python3 scripts/check_docs.py
git diff --check
```

`runGametest` 每局先删掉 `build/gametest/world`，由 GameTest 服务端按自带的平坦预设新建世界。
`runClientTest` 复用这个世界的全局设置：每次只把 `level.dat` 与 `data/minecraft/`（26.2 的平坦生成设置、游戏规则、天气与时钟
都在这里）同步为单人存档 `taunts-bubble-test`，区块重新生成，GameTest 遗留的结构与上一局的玩家位置、村民都不会带进截图；
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
| `tauntCommandSpeaksNowAndCompletes` | `/meridian humanoid taunt <uuid> entity` 用「对生物」台词；玩家执行 `taunt <uuid>` 用「对玩家」台词且台词里是执行者名字；对牛执行失败；`player`、`entity` 可补全；`/meridian humanoid` 下权限 2 能补全到 `taunt`、权限 0 看不到；旧根 `/meridian_humanoid_taunts` 不存在 |
| `crisisSpeechIsRelayedAsGrayChat` | Threatening 发 `HumanoidSpeech.SPOKE`（村民对 17 格外的玩家说一句翻译文本）：3 格处的旁观者和对着说的那名玩家各收到一条浅灰色聊天，说话者是村民、台词原样；另一侧 17 格外的玩家收不到；随后打村民照常说垃圾话（招敌那句不记冷却） |

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
- 接着是招敌截图（`CrisisOmenShots`，用的是 Threatening 招敌的调试指令，只截图、核对文字，不比较像素）：
  - 先把时间定成正午，清掉玩家和村民以外的实体（世界时刻随上一局走，夜里截图看不清，来人会和野怪混在一起）。
  - `meridian humanoid crisis omen <玩家>` 先排上一场，再让同一个村民说第一句传言。聊天应整行浅灰色，正好是
    「<村民> 」加上 `crisis.meridian_humanoid.covetous.omen.rumor.1` 的译文。截图 `crisis-rumor.png`。
  - 在玩家正前方 2 格放一张床，设成重生点，再给征兆，直到字条落在床上。然后打开只画悬停说明、不压暗画面的界面：
    说明第一行是「字条」那个键的译文，下面三行都没有残留翻译键。
    掉落物一直在转，等纸面转到正对镜头（最多 7 秒）再截图 `crisis-note.png`。
  - 玩家切生存模式（原版的怪不追创造模式的玩家）、给抗性 V，用 `strike <玩家>` 叫来人。客户端按「索卷客」那个键的译文
    认出头目，转头看着它；它走进 12 格内（或者等满 20 秒）时关掉 HUD（免得聊天挡住来人），一秒后截图 `crisis-raiders.png`。
    再打开 HUD，头目要是走进准星，再截一张头顶带名号的 `crisis-leader.png`；
    它一直不近身也不算失败。
  - 客户端看到的这几段文字都写在 `crisis.txt`。

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

## 2026-11-01：S3 迁到 Minecraft 26.2

Minecraft 26.2、Java 25、Gradle 9.8.0 wrapper、Loom 1.19.3（26.x 不混淆，不重映射），依赖改为普通 `implementation`/`compileOnly`。
正式代码只做 26.2 改名与接口替换，不改行为：

- 气泡包改为 `BubblePayload`：通道 ID 与编解码只定义一份，模组初始化时登记为发往客户端的载荷；
  台词组件由 1.20.1 的 JSON 字符串改为 26.2 的结构化组件编码（收发两端都是本模组）。收包回调已在客户端主线程，去掉转交。
- 聊天改 `sendSystemMessage`：1.20.1 的 `displayClientMessage(…, false)` 本就委托给它，路径与显示不变。
- 气泡绘制拆成 26.2 的提取与提交两步：`END_EXTRACTION` 取说话者插值位置（游戏冻结时按原版实体规则取插值进度）
  与折好的行，`COLLECT_SUBMITS` 提交底色几何与文字。坐标系照 26.2 原版名牌（镜头朝向、`scale(0.025, -0.025, 0.025)`），
  远离镜头改为 -z，底色深度相应写成 -0.01，仍在文字之后；尺寸、颜色、全亮、4 秒、16 格不变。
- `server/` 编译仍用版本目录的 Gson 2.10.1；26.2 运行时自带 2.14.0，向下兼容。
- 26.2 原版 `MutableComponent.append(String)` 跳过空串（1.20.1 照样追加空字面子组件）：台词以 `{attacker}` 开头时
  名字成为第一个子组件，拼出的整句与显示不变；`TauntTextsTest` 与 GameTest 改为按名字组件查找，不再按下标取。

GameTest 改用 Fabric `@GameTest`，各例显式写 1.20.1 默认的 100 tick（26.2 默认只有 20 tick），冷却用例上限不变；
1.20.1 的每例一批改为每例一个空测试环境（`test_environment/`），仍互不串听。
受击改 `hurtServer`，权限 2/0 改 `GAMEMASTER`/`NO_PERMISSIONS`，实体类型常量改 `EntityTypes`，
字面文本与空内容改 `PlainTextContents`。客户端测试的截图改为 26.2 的异步回调（等待回调期间停在 `CAPTURING`），
HUD 显隐、加载遮罩与提示改经 `client.gui`，取像素改 ARGB 排列，`options.txt` 预置的数据版本由 3465 改为 4903。

命令：`./gradlew test build :fabric:compileGametestJava :fabric:compileClientTestJava`；
`python3 scripts/check_docs.py`；`git diff --check`。

结果：JUnit 19/19（`TauntCatalogTest` 4、`TauntMemoryTest` 3、`TauntSettingsTest` 2、`TauntBubbleLinesTest` 7、
`TauntTextsTest` 3），GameTest 与客户端测试源集编译通过，文档检查通过（5 个页面）。
GameTest 与客户端测试尚未运行：完整装配里还有附属没迁完，迁完后用正式完整装配统一跑。
