# 类人垃圾话

模组命名空间为 `meridian_humanoid_taunts`。Vanilla-Humanoid-Threatening 认领、台词表里有台词的生人受到
Vanilla-Damage 结算的伤害时，对攻击者说一句：玩家打它，从「对玩家」台词里抽；其他生物打它，从「对生物」台词里抽。

- [使用方法](Usage.md)：触发规则、聊天与气泡、命令、配置、台词数据与语言切换。
- [整合接口](Integration.md)：Threatening 物种、Damage 观察事件、气泡网络包。
- [构建与验证](Verification.md)：构建命令、JUnit、GameTest 与 Xvfb 客户端测试覆盖。

## 依赖

Framework、Human、Vanilla-Humanoid-Threatening 与 Vanilla-Damage 都是必装依赖，没有「未安装」时的替代路径。
本模组不改这些仓库，只使用它们已有的公开入口。

## 物种与口吻

台词表 `zh_cn` 覆盖 Threatening 内置的七个物种，口吻按交接要求各不相同（数据里的 `voice` 只写物种名）：

| 实体 | 口吻 | 对生物 | 对玩家 |
| --- | --- | --- | --- |
| `minecraft:villager` | 村民：怕事但嘴碎 | 20 | 100 |
| `minecraft:wandering_trader` | 流浪商人：推销腔 | 20 | 101 |
| `minecraft:pillager` | 掠夺者：粗鄙 | 20 | 100 |
| `minecraft:vindicator` | 卫道士：守道武僧 | 20 | 100 |
| `minecraft:evoker` | 唤魔者：傲慢术士 | 19 | 100 |
| `minecraft:witch` | 女巫：博学 | 20 | 101 |
| `minecraft:illusioner` | 幻术师：戏谑 | 20 | 100 |

## 源码分层

`shared/` 只放台词数据；`server/` 是与 Minecraft 无关的台词表解析、配置读取与「最近 5 句不重复」抽取规则；
`fabric/` 负责 Damage 观察方、聊天发送、气泡网络包、调试命令与客户端气泡渲染。
