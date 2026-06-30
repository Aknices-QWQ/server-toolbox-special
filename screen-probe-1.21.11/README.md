# ranmc: toolbox

最小的 Fabric 客户端调试模组，用来识别并测试服务器菜单里的输入框。

## 维护状态

`1.21.11` 移植版将保留为历史构建，不再继续更新功能。后续只维护主版本 `26.1.2`。

## 当前功能

- 界面一打开就自动打印当前界面的类名、标题、子控件数量、前几个子控件类型、输入框数量、菜单类名、槽位数量
- 提供客户端命令 `/autosell`
- 提供持续监控模式 `/autosell yolo`
- 提供下界疣自动收种 `/autowart`
- 提供发射器自动补船 `/autoboat1`
- 提供 ClickGUI `/clickgui`
- 提供 Node mineflayer 机器人外部进程管理 `/nodebot`

这个版本优先支持 `EditBox` 驱动的界面，例如常见的：

- 铁砧输入框
- 聊天输入框
- 很多模组自带的文本输入框

## 运行前提

Fabric 26.1.2 这代按官方文档需要 `JDK 25`。

另外，Fabric 26.1 官方博客建议开发时使用 `Gradle 9.4.0` 左右的版本；如果你用 IntelliJ 直接导入项目，它通常会提示你配置合适的 Gradle JVM。

参考文档：

- Fabric 26.1.2 开发环境要求：<https://docs.fabricmc.net/develop/getting-started/setting-up>
- Fabric 26.1.2 推荐版本：<https://fabricmc.net/develop/>
- Fabric 26.1 迁移说明：<https://docs.fabricmc.net/develop/porting/>
- Fabric 26.1 博客说明：<https://fabricmc.net/2026/03/14/261.html>

## 使用方式

1. 用支持 Gradle 的 IDE 打开这个目录
2. 把 Gradle JVM 设为 `JDK 25`
3. 如果 IDE 没有自动生成运行配置，执行 Gradle 的 `runClient`
4. 进服务器后打开目标界面
5. 模组会在界面打开时自动把探测信息保存到 `.minecraft/logs/ranmc-toolbox/`
6. 聊天栏只会提示保存的文件名
7. 把对应的 `ranmc-toolbox-*.log` 内容发给我
8. 输入 `/autosell` 会发送 `/sell`，然后自动点击背包中存在的可卖物品并完成确认
9. 输入 `/autosell yolo` 会在背包中检测到超过一组可卖物品时自动售卖
10. 输入 `/autowart start` 会进入可见范围收种模式，扫描玩家周围合法距离内所有可见灵魂沙/下界疣；只收成熟下界疣，空灵魂沙会补种，收种不会强制扭头或移动；缺下界疣时会从背包或 `/cd` 作物仓库一次补多组
11. 输入 `/autowart radius 4` 可设置扫描半径，合法范围 1-4；输入 `/autowart speed 3` 可设置每 tick 最大操作数，范围 1-5；输入 `/autowart minecart on` 可在乘坐矿车时自动按住前进防止停下
12. 输入 `/autoboat1` 会自动扫描附近够得到的发射器，里面已有船就跳过，没有船就从背包补 1 艘；再次输入可停止
13. 输入 `/nodebot start` 会从当前游戏实例版本目录的 `ranmc-toolbox-nodebot` 副本启动 `node mc-bot.js`；`/nodebot stop` 停止，`/nodebot restart` 重启，`/nodebot status` 查看副本目录和日志
14. 输入 `/clickgui` 可打开图形面板，直接开关自动下界疣、矿车防停、自动补船、Node 机器人，并调整下界疣扫描半径和操作速度
15. 如果需要下一步自动输入或自动点击，再把界面截图一起发给我

## Node 机器人副本

Fabric 端不会直接指向本仓库工作区。实际启动目录为当前游戏实例/版本目录里的 `ranmc-toolbox-nodebot`，其中需要有：

- `mc-bot.js`
- `package.json`
- `package-lock.json`
- `node_modules`
- 可选 `.env`

启动时会设置 `MC_HEADLESS=true`，日志写到当前游戏目录的 `logs/ranmc-toolbox/nodebot/`。

## 当前限制

- 这个工作区里还没有 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`
- 我尝试联网拉官方模板和 wrapper，但当前网络连接被重置，没法在这里补齐
- 所以这份代码目前更适合直接用 IDE 导入，或者你本机有可用的 Gradle 之后再生成 wrapper

## 下一步

拿到自动探测日志后，就可以继续针对具体界面写：

- 自动填写指定文本
- 自动点击确认按钮或槽位
- 按服务器菜单流程串联后续动作
