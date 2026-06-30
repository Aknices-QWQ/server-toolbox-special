# 【自用】【特供】服务器工具箱

自用 Fabric 客户端工具箱，包含自动售卖、作物仓库补货、下界疣收种、自动强化、补船、村民交易、Node 机器人调用等功能。

## 版本

- 主线：Minecraft `26.1.2`，Fabric Loader `0.19.2`
- 移植：Minecraft `1.21.11`，Fabric Loader `0.19.2`

## 常用命令

- `/autohelp`：查看可点击帮助入口。
- `/autosettings`：打开配置界面。
- `/autosell`：自动售卖当前背包可卖物品。
- `/autosell refill` 或 `/autosell refill all`：从作物仓库整页取出并循环售卖，直到仓库取不出。
- `/autosell refill <数量>`：按目标数量循环补仓售卖。
- `/autosell item <物品>`：设置补仓目标，默认下界疣。
- `/autosell stop`：停止并清空自动售卖状态。
- `/autowart`：自动下界疣收种。
- `/autostrength`：自动强化。
- `/autostrength craft <剑|镐子|头盔|胸甲|靴子>`：优先强化背包内未强化或强化次数为 0 的目标装备；没有则打开 `/wb` 准备合成。
- `/autoboat1`：自动往发射器补船。
- `/nodebot start|stop|restart|status|send <消息>`：管理 Node 机器人。

## 构建

主线版本：

```powershell
.\gradlew.bat build
```

1.21.11 移植版本：

```powershell
cd screen-probe-1.21.11
.\gradlew.bat build
```

## Node 机器人

复制 `.env.example` 为 `.env` 后按需填写：

```powershell
npm install
npm start
```

不要提交 `.env`、日志、`node_modules` 或个人账号密码。

## 说明

本项目为特定服务器自用工具箱。使用前请确认符合服务器规则与本地环境配置。
