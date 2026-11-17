# 仓库协作约定

- 本仓库只收 Minecraft Java 版 26.2 的类人受击垃圾话：台词数据、抽取规则、聊天与头顶气泡、调试命令。物种与身体宿主归 Vanilla-Humanoid-Threatening，伤害结算归 Vanilla-Damage，权威身体状态归 Framework。
- Meridian 各附属按完整装配运行；不得添加缺少必需附属时的替代路径、降级或可选依赖判断。
- 不复制宿主或结算代码：说话者只认 Threatening 的 `HumanoidSpecies`，受击只订阅 Damage 提交后的观察事件。
- 台词由交接目录里的生成稿合并而来，不在仓库里手写或改写台词；新语言按 `taunts/<language>.json` 加文件。
- `shared/` 只放台词数据；`server/` 保存与 Minecraft 无关的台词表、配置与抽取规则；`fabric/` 负责事件接线、聊天、网络、命令与客户端气泡。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，命名、换行和注释密度与相邻代码保持一致。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口。交付执行 Fabric GameTest、Xvfb 客户端测试、文档引用检查和 `git diff --check`。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
