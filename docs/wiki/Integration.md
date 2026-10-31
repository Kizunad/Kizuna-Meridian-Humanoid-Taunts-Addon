# 整合接口

本模组只使用 Framework、Threatening 与 Damage 已有的公开入口，不改这些仓库，也不复制它们的代码。

## Threatening 物种

`HumanoidSpecies.fromEntityId(String)`：说话者的实体 ID 必须被 Threatening 认领为类人身体宿主（七个内置物种，
或其他附属经数据驱动注册点登记的物种），并且当前台词表里有这个 ID，才会开口。
登记了物种但台词表里没有的实体（例如训练假人）不说话；台词表里有但 Threatening 没认领的实体也不说话。

## Damage 观察事件

`VanillaDamageFabric.registerDamageObserver` 在身体事务提交后通知只读事实。本模组用到其中三项：

- `target()`：受击者，即说话者；
- `attacker()`：伤害源的生物攻击者，可能为空；弹射物算射手；
- `result().body().getVitality().getDead()`：提交后的身体是否死亡，用来跳过致死一击。

观察事件只对有身体的目标触发，所以「有身体的生人」这一条件由 Damage 保证；原版 `hurt()` 被取消、
没有进入身体事务的伤害不会触发。观察方抛出的异常由 Damage 隔离，不改变结算。

## Framework 指令注册点

`MeridianCommands.register(Branch.HUMANOID, "taunt", GAMEMASTER, …)`：调试命令挂在 `/meridian humanoid` 下，
权限 2 由注册点套在 `taunt` 节点上。指令写法只在 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands#humanoid)记录。

## 气泡网络包

S2C 通道 `meridian_humanoid_taunts:bubble`，载荷依次为：

| 字段 | 编码 | 含义 |
| --- | --- | --- |
| 实体 ID | VarInt | 说话者的实体网络 ID |
| 台词 | `ComponentSerialization.TRUSTED_STREAM_CODEC` | 已替换攻击者名字的文本组件，可翻译部分由客户端解析 |

通道 ID 与编解码只在 `BubblePayload` 定义一份。台词用 26.2 的结构化组件编码（1.20.1 是 JSON 字符串），
收发两端都是本模组，与原版系统聊天同一种编码。

服务端只向能接收此通道（装了本模组客户端）的玩家发送。客户端收包回调在主线程（即渲染线程）登记气泡；
每帧在 `LevelExtractionEvents.END_EXTRACTION` 里取出说话者位置与折好的行，
在 `LevelRenderEvents.COLLECT_SUBMITS` 里按原版名牌的坐标系提交绘制，断开连接时清空。
气泡底色用 `RenderTypes.textBackground()` 按横条拼出圆角框与尾巴，深度比字形远 0.01 个字体像素，
与原版文字底色相同，所以文字始终压在底色上、气泡仍会被方块挡住。
