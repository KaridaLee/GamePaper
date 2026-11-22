# GamePaper Mini Games

## PVP 占点游戏

```text
/game pvp join
/game pvp start
/game pvp leave
/game pvp status
```

`pvp-game` 为红队和蓝队各配置一个共享重生点，同时配置游戏范围、中心占点区域、目标积分和每个积分所需的占领时间。默认游戏内白名单为 `/game pvp`。所有参赛玩家会同时看到红蓝两条 BossBar。职业分别为突击、补给和狙击；开局倒计时以及每次复活后都会打开职业选择 GUI，玩家必须选择职业才能继续。补给职业为全程生效的被动技能，队友装备默认每 3 tick 修复一点耐久。游戏结束后会显示胜利 Title，5 秒后清空装备并传送回世界出生点。

一个面向 Paper 26.2、使用 Java 25 编译的休闲小游戏合集，插件名为 `GamePaper`。

## 构建

需要 JDK 25 和 Maven。项目使用 Paper API `26.2.build.84-stable`，Paper API 以 `provided` 方式提供，不会被打进插件 JAR。

PowerShell 示例：

```powershell
$env:JAVA_HOME = 'C:\mmm\graalvm-jdk-25.0.3+9.1'
$env:Path = "$env:JAVA_HOME\bin;C:\MAVEN\apache-maven-3.9.4\bin;$env:Path"
mvn clean package '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=7890' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=7890'
```

产物：`target/GamePaper.jar`。

## 主命令

统一使用 `/game`，旧的 `/ttt` 和 `/gomoku` 命令仍保留兼容。

```text
/game ttt <操作>
/game gomuku <操作>
/game random <操作>
/game tnt <操作>
/game undercover <操作>
```

## 井字棋

```text
/game ttt invite <玩家>  邀请在线玩家
/game ttt accept [玩家]  接受邀请
/game ttt deny [玩家]    拒绝邀请
/game ttt board          重新打开共享棋盘
/game ttt leave          退出当前对局并认输
/game ttt cancel         取消自己发出的邀请
```

两名玩家共享同一个虚拟投掷器界面，邀请方使用红色混凝土，接受方使用蓝色混凝土。

## 五子棋

```text
/game gomuku invite <玩家>  邀请在线玩家
/game gomuku accept [玩家]  接受邀请
/game gomuku deny [玩家]    拒绝邀请
/game gomuku review <玩家>  观战该玩家所在的对局
/game gomuku board          重新打开自己的棋盘
/game gomuku leave          离开观战或退出自己的对局
/game gomuku cancel         取消自己发出的邀请
```

五子棋使用共享的 6 行箱子界面：9 列中的下方 5 行作为棋盘视窗，上方按钮控制滚动。完整棋盘是 9×9，点击“上一行”和“下一行”即可查看全部棋盘；滚动位置对双方和观战者同步。观战者可以查看棋盘，但所有点击都会被拦截，不能落子；关闭界面或使用 `leave` 即可离开观战。

黑方使用黑色混凝土，白方使用白色混凝土，横、竖或斜线连续五子即可获胜。对局结束时会向全服播报获胜者和失败者；平局也会播报。

邀请默认 60 秒后过期，对局结束后默认保留最终棋盘 3 秒，可在 `config.yml` 中调整。

## 随机物品柱子生存

```text
/game random join    加入等待区
/game random start   开始游戏
/game random leave   离开等待区或退出游戏
/game random status  查看状态
```

玩家必须在背包、盔甲栏和副手都为空时加入。玩家会被分散传送到配置的柱子坐标，倒计时结束后每隔 10 秒随机获得一个生存可用物品；死亡或退出服务器会被淘汰，死亡玩家会在原出生点以旁观模式观战，最后存活者获胜。游戏范围同时用于限制移动和开局清理，开局会清除多余方块和没有自定义名称的非玩家实体，黑曜石与带名称的实体会保留。

游戏进行超过默认 3 分钟后，每隔默认 90 秒随机触发一个事件。事件等待时间和间隔可在 `random-game.event-start-delay-seconds`、`random-game.event-interval-seconds` 中配置，事件包括：存活玩家头顶 50 格的“铁砧雨”、每名存活玩家获得 5 个随机物品，以及在存活玩家附近方块上生成命名为“灾厄”的僵尸。游戏范围内普通生物生成仍会被阻止，事件生成的带名僵尸会被放行。

游戏进行期间只有 `config.yml` 中 `random-game.command-whitelist` 的指令根名称可以使用，默认保留 `/game` 和常用私聊指令。服务器关闭会结束对局，并在玩家下次进入服务器时将其传送回世界出生点。

## TNT 烫手山芋

```text
/game tnt join    加入等待区
/game tnt start   开始游戏
/game tnt leave   离开等待区、当前游戏或旁观模式
/game tnt status  查看状态
/game reload      重载配置（需要权限）
/game gui         打开小游戏排队菜单
```

TNT 游戏最大人数由 `tnt-game.max-players` 配置，实际人数还受重生点数量限制。加入时背包、盔甲栏和副手必须为空。玩家会被传送到 `config.yml` 中 `tnt-game.spawn-points` 配置的位置，先进行默认 10 秒准备倒计时，期间可以自由移动。准备结束后随机给一名玩家 TNT，并开始默认 30 秒倒计时；持有 TNT 的玩家攻击其他存活玩家后，TNT 会传给被攻击者。倒计时结束时持有者会死亡并播放 TNT 爆炸效果，默认冷却 5 秒后从剩余玩家中随机选择下一名持有者。

TNT 游戏中禁止丢弃、捡起物品、使用非 `/game tnt` 指令、破坏和放置方块，场地内不会生成生物。死亡玩家会在场地内以旁观模式观战，游戏结束后所有参与者传送回世界出生点。`tnt-game.boundary` 可以配置场地范围，`round-seconds`、`cooldown-seconds` 和 `start-countdown-seconds` 可以调整时间。

对局期间所有存活玩家会持续显示发光轮廓，所有游戏玩家会获得饱腹效果；当前 TNT 持有者会获得速度 I，位置每秒会生成一次明显的纯粒子烟花爆炸特效。粒子不会生成实体、造成伤害或击退；TNT 传递或游戏结束后会移除对应效果。

每轮 TNT 下发时从配置的 `tnt-game.round-seconds` 开始倒计时，TNT 传递不会重置本轮倒计时；倒计时结束后当前持有者被淘汰，冷却结束后才会开始下一轮。`/game reload`（默认仅管理员可用）会重载 `config.yml`；随机物品或 TNT 对局进行中时不会执行重载。

## 谁是卧底

```text
/game undercover join    加入等待区
/game undercover start   至少 3 人时开始游戏
/game undercover leave   离开等待区或当前游戏
/game undercover status  查看状态
/game undercover debug [数量]  生成 1–20 组 DeepSeek 测试词（默认 5 组）
```

游戏支持 3–6 人，加入时背包、装备和副手必须为空。每轮获取一组新的平民词与卧底词，并在开局时随机选择一名卧底；词语只通过各自的 Title 和个人计分板显示。玩家会被固定在 `undercover-game.spawn-points` 配置的位置并持续获得饱腹效果。

每轮随机排列说明顺序，轮到的玩家会打开可署名书本，写完并点击“署名”后才算提交。填写时间由 `description-seconds` 配置，默认 60 秒；超时会自动提交一本内容为“超时未填写”的署名书。所有说明按顺序存入分页箱子 GUI；每名参与者物品栏中的“查看所有说明”书可随时右键打开该 GUI。最后一名玩家提交后，所有参与者会先获得 30 秒查看本轮说明，然后开始默认 20 秒投票。左键头像即可投票，每人一票且结束前可以改票；头像说明会实时列出所有投给该玩家的人。关闭投票页后，可以右键物品栏里的指南针重新打开。某玩家票数严格超过当前存活人数一半时出局，未投票视为弃权。

`undercover-game.ai` 可以启用 DeepSeek 每轮新词。填写 `api-key` 并将 `enabled` 设为 `true` 后，插件通过 Chat Completions JSON Output 异步请求一组新词，校验固定 `{"civilian":"...","undercover":"..."}` 格式，并记录本局已经使用过的所有词和词组；返回重复或无效内容会重试，最终失败则使用本地题库。默认模型为 `deepseek-v4-flash`，可在配置中修改。API Key 不会写入插件日志。

系统提示词和每轮请求模板分别完整显示在 `ai.system-prompt`、`ai.round-prompt`，修改后执行 `/game reload` 生效。每轮模板支持 `{round}`、`{used_words}`、`{used_pairs}` 变量；请保留输出 JSON 的 `civilian` 和 `undercover` 字段。管理员可执行 `/game undercover debug [数量]` 测试实际生成质量，默认 5 组、最多 20 组；该命令只要求已填写 API Key，即使 `enabled: false` 也可以测试，权限节点为 `gamepaper.undercover.debug`（默认 OP）。

卧底出局则平民获胜；卧底仍存活且平民只剩一人时卧底获胜。退出服务器或主动离开均按出局处理。对局结束会揭晓身份和两组词，关闭计分板、清空背包、恢复生存模式，并将玩家送回世界出生点。题库、投票时间与最多六个重生点都可在 `undercover-game` 下配置。

`/game gui` 会打开小游戏排队菜单，包含随机物品柱子生存、TNT 烫手山芋和谁是卧底。每个游戏按钮的 lore 会显示当前已经进入该队列的玩家名，并会定时刷新。点击按钮加入等待区，再次点击同一按钮退出等待区；玩家已经在一个小游戏等待区或对局中时，不能加入另一个小游戏。井字棋和五子棋为双人对战，不加入该菜单。通过 DeluxeMenus 等菜单插件执行 `[player] game gui` 时，插件会持续关闭外部菜单并等待玩家背包界面真正恢复后再打开匹配 UI，等待上限由 `game-gui.external-menu-wait-ticks` 配置，默认 2 tick，范围 0–40；推荐也可以使用 `[console] game gui %player_name%`，该路径需要 `gamepaper.gui.others` 权限（默认 OP）。
