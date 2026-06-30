package axelion.screenprobe.client;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ScreenProbeConfigScreenFactory {
    private ScreenProbeConfigScreenFactory() {
    }

    static void preload() {
        try {
            ClassLoader loader = ScreenProbeConfigScreenFactory.class.getClassLoader();
            Class.forName(ScreenProbeConfigScreenFactory.class.getName() + "$Draft", true, loader);
            Class.forName(ScreenProbeConfigScreenFactory.class.getName() + "$StrengthenDraft", true, loader);
        } catch (ClassNotFoundException | LinkageError exception) {
            axelion.screenprobe.ScreenProbe.LOGGER.warn("Failed to preload autosettings classes", exception);
        }
    }

    static Screen create(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        AutoNetherWartConfig.Config defaults = AutoNetherWartConfig.Config.defaults();
        AutoNetherWartConfig.Config current = AutoNetherWartConfig.get(client);
        AutoStrengthenConfig.Config strengthenDefaults = AutoStrengthenConfig.Config.defaults();
        AutoStrengthenConfig.Config strengthenCurrent = AutoStrengthenConfig.get(client);
        ScreenProbeGlobalConfig.Config globalDefaults = ScreenProbeGlobalConfig.Config.defaults();
        ScreenProbeGlobalConfig.Config globalCurrent = ScreenProbeGlobalConfig.get(client);
        Draft draft = new Draft(current);
        StrengthenDraft strengthenDraft = new StrengthenDraft(strengthenCurrent);
        GlobalDraft globalDraft = new GlobalDraft(globalCurrent);

        YetAnotherConfigLib config = YetAnotherConfigLib.createBuilder()
                .title(Component.literal(ScreenProbeGlobalConfig.prefix(client) + " 设置"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("全局设置"))
                        .option(intOption("页面操作延迟", "打开菜单、点击菜单项后的等待 tick。调低更快，调高更稳。", globalDefaults.menuActionDelayTicks(), 0, 60,
                                () -> globalDraft.menuActionDelayTicks, value -> globalDraft.menuActionDelayTicks = value))
                        .option(intOption("结果等待延迟", "放入物品、点击确认后的等待 tick。", globalDefaults.menuResultDelayTicks(), 0, 100,
                                () -> globalDraft.menuResultDelayTicks, value -> globalDraft.menuResultDelayTicks = value))
                        .option(intOption("关闭页面延迟", "自动关闭页面后等待下一步的 tick。", globalDefaults.menuCloseDelayTicks(), 0, 40,
                                () -> globalDraft.menuCloseDelayTicks, value -> globalDraft.menuCloseDelayTicks = value))
                        .option(booleanOption("低TPS自动降速", "开启后定时执行 ranmc:tps，低于阈值时自动放慢菜单操作。", globalDefaults.lowTpsAutoSlowdown(),
                                () -> globalDraft.lowTpsAutoSlowdown, value -> globalDraft.lowTpsAutoSlowdown = value))
                        .option(intOption("低TPS阈值", "TPS 低于此值时触发自动降速。", (int) globalDefaults.lowTpsThreshold(), 5, 20,
                                () -> globalDraft.lowTpsThreshold, value -> globalDraft.lowTpsThreshold = value))
                        .option(intOption("降速倍率", "低 TPS 时页面操作延迟放大的倍数。", globalDefaults.lowTpsDelayMultiplier(), 1, 5,
                                () -> globalDraft.lowTpsDelayMultiplier, value -> globalDraft.lowTpsDelayMultiplier = value))
                        .option(intOption("TPS查询间隔", "每隔多少 tick 执行一次 ranmc:tps。", globalDefaults.tpsQueryIntervalTicks(), 20, 6000,
                                () -> globalDraft.tpsQueryIntervalTicks, value -> globalDraft.tpsQueryIntervalTicks = value))
                        .option(ButtonOption.createBuilder()
                                .name(Component.literal("当前TPS"))
                                .text(Component.literal("显示"))
                                .description(OptionDescription.of(Component.literal("输出最近一次从 ranmc:tps 解析到的 TPS。")))
                                .action(screen -> {
                                    if (client.player != null) {
                                        client.player.sendSystemMessage(Component.literal("当前记录 TPS：" + String.format(java.util.Locale.ROOT, "%.2f", ScreenProbeGlobalConfig.lastTps())));
                                    }
                                })
                                .build())
                        .option(booleanOption("启用自动下界疣", "关闭后屏蔽 /autowart 和后台处理。", globalDefaults.enableAutoWart(),
                                () -> globalDraft.enableAutoWart, value -> globalDraft.enableAutoWart = value))
                        .option(booleanOption("启用自动强化", "关闭后屏蔽 /autostrength 和后台处理。", globalDefaults.enableAutoStrengthen(),
                                () -> globalDraft.enableAutoStrengthen, value -> globalDraft.enableAutoStrengthen = value))
                        .option(booleanOption("启用自动售卖", "关闭后屏蔽 /autosell 和后台处理。", globalDefaults.enableAutoSell(),
                                () -> globalDraft.enableAutoSell, value -> globalDraft.enableAutoSell = value))
                        .option(booleanOption("启用自动补船", "关闭后屏蔽 /autoboat1 和后台处理。", globalDefaults.enableAutoBoat(),
                                () -> globalDraft.enableAutoBoat, value -> globalDraft.enableAutoBoat = value))
                        .option(booleanOption("启用Node机器人", "关闭后屏蔽 /nodebot 启动和发送。", globalDefaults.enableNodeBot(),
                                () -> globalDraft.enableNodeBot, value -> globalDraft.enableNodeBot = value))
                        .option(booleanOption("启用自动打龙", "关闭后屏蔽 /autodragon。", globalDefaults.enableAutoDragon(),
                                () -> globalDraft.enableAutoDragon, value -> globalDraft.enableAutoDragon = value))
                        .option(booleanOption("启用自动挖区块", "关闭后屏蔽 /autochunk。", globalDefaults.enableAutoChunk(),
                                () -> globalDraft.enableAutoChunk, value -> globalDraft.enableAutoChunk = value))
                        .option(booleanOption("启用村民交易", "关闭后屏蔽自动村民交易。", globalDefaults.enableAutoVillagerTrade(),
                                () -> globalDraft.enableAutoVillagerTrade, value -> globalDraft.enableAutoVillagerTrade = value))
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("自动下界疣"))
                        .option(booleanOption("启用", "启动后是否立即运行自动下界疣。", defaults.enabled(),
                                () -> draft.enabled, value -> draft.enabled = value))
                        .option(intOption("扫描半径", "可见范围扫描半径。", defaults.scanRadius(), 1, 6,
                                () -> draft.scanRadius, value -> draft.scanRadius = value))
                        .option(intOption("操作/tick", "每 tick 最多执行的收种次数。", defaults.actionsPerTick(), 1, 5,
                                () -> draft.actionsPerTick, value -> draft.actionsPerTick = value))
                        .option(booleanOption("自动前进", "是否持续按住前进键。", defaults.autoWalk(),
                                () -> draft.autoWalk, value -> draft.autoWalk = value))
                        .option(booleanOption("矿车防停", "乘坐矿车时自动按住前进，并处理矿车补放。", defaults.minecartForwardAssist(),
                                () -> draft.minecartForwardAssist, value -> draft.minecartForwardAssist = value))
                        .option(booleanOption("自动上矿车", "检测附近空矿车后自动交互乘坐。", defaults.autoRideMinecart(),
                                () -> draft.autoRideMinecart, value -> draft.autoRideMinecart = value))
                        .option(booleanOption("自动放矿车", "附近有轨道且背包有矿车时自动放置。", defaults.autoPlaceMinecart(),
                                () -> draft.autoPlaceMinecart, value -> draft.autoPlaceMinecart = value))
                        .option(booleanOption("自动清矿车", "自动清理轨道上没有玩家乘坐的矿车。", defaults.autoClearNonPlayerMinecart(),
                                () -> draft.autoClearNonPlayerMinecart, value -> draft.autoClearNonPlayerMinecart = value))
                        .option(booleanOption("自动重生返回", "死亡后自动重生并返回工作领地。", defaults.autoRespawnReturn(),
                                () -> draft.autoRespawnReturn, value -> draft.autoRespawnReturn = value))
                        .option(booleanOption("旁观检测", "检测到旁观模式玩家时自动撤离。", defaults.spectatorDangerDetection(),
                                () -> draft.spectatorDangerDetection, value -> draft.spectatorDangerDetection = value))
                        .option(intOption("重生返回延迟", "死亡后等待多少 tick 再返回工作领地。", defaults.respawnReturnDelayTicks(), 0, 1200,
                                () -> draft.respawnReturnDelayTicks, value -> draft.respawnReturnDelayTicks = value))
                        .option(intOption("危险返回延迟", "危险消失后等待多少 tick 再返回工作领地。", defaults.dangerReturnDelayTicks(), 0, 1200,
                                () -> draft.dangerReturnDelayTicks, value -> draft.dangerReturnDelayTicks = value))
                        .option(intOption("危险指令冷却", "撤离后再次触发撤离前的冷却 tick。", defaults.dangerCommandCooldownTicks(), 0, 12000,
                                () -> draft.dangerCommandCooldownTicks, value -> draft.dangerCommandCooldownTicks = value))
                        .option(intOption("开局传送冷却", "启动和返回后暂停危险检测的 tick。", defaults.startTeleportCooldownTicks(), 0, 12000,
                                () -> draft.startTeleportCooldownTicks, value -> draft.startTeleportCooldownTicks = value))
                        .option(intOption("矿车动作冷却", "矿车清理、上车、放车之间的冷却 tick。", defaults.minecartActionCooldownTicks(), 0, 200,
                                () -> draft.minecartActionCooldownTicks, value -> draft.minecartActionCooldownTicks = value))
                        .option(intOption("补货界面延迟", "打开仓库后等待界面稳定的 tick。", defaults.refillScreenDelayTicks(), 0, 200,
                                () -> draft.refillScreenDelayTicks, value -> draft.refillScreenDelayTicks = value))
                        .option(intOption("补货超时", "仓库补货流程超时 tick。", defaults.refillTimeoutTicks(), 20, 12000,
                                () -> draft.refillTimeoutTicks, value -> draft.refillTimeoutTicks = value))
                        .option(intOption("单次取货组数", "兼容旧配置；当前会按背包缺口和容量动态取出。", defaults.storageRefillStacksPerRun(), 1, 36,
                                () -> draft.storageRefillStacksPerRun, value -> draft.storageRefillStacksPerRun = value))
                        .option(stringOption("工作领地", "启动、死亡返回、危险解除后返回的领地名。", defaults.startTerritory(),
                                () -> draft.startTerritory, value -> draft.startTerritory = value))
                        .option(stringOption("安全领地", "检测到危险玩家后撤离去的领地名。", defaults.safeTerritory(),
                                () -> draft.safeTerritory, value -> draft.safeTerritory = value))
                        .option(stringOption("危险玩家名", "指定玩家在线时视为危险并撤离。", defaults.dangerPlayerName(),
                                () -> draft.dangerPlayerName, value -> draft.dangerPlayerName = value))
                        .option(stringOption("作物仓库命令", "补种子时发送的仓库命令，不含斜杠。", defaults.cropStorageCommand(),
                                () -> draft.cropStorageCommand, value -> draft.cropStorageCommand = value))
                        .option(ButtonOption.createBuilder()
                                .name(Component.literal("Node 状态"))
                                .text(Component.literal("发送"))
                                .description(OptionDescription.of(Component.literal("在配置页里直接输出当前 Node 机器人状态。")))
                                .action(screen -> NodeBotController.sendStatus(client))
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("自动强化"))
                        .option(intOption("默认强化次数", "命令启动自动强化时默认执行的最大次数。", strengthenDefaults.defaultRolls(), 1, 64,
                                () -> strengthenDraft.defaultRolls, value -> strengthenDraft.defaultRolls = value))
                        .option(intOption("高伤害阈值", "总伤害达到此值时停止并保留。", strengthenDefaults.damageThreshold(), 1, 100,
                                () -> strengthenDraft.damageThreshold, value -> strengthenDraft.damageThreshold = value))
                        .option(intOption("高防御阈值", "总防御达到此值时停止并保留。", strengthenDefaults.defenseThreshold(), 1, 100,
                                () -> strengthenDraft.defenseThreshold, value -> strengthenDraft.defenseThreshold = value))
                        .option(intOption("伤防都高-伤害", "伤防同时达标时的伤害阈值。", strengthenDefaults.bothDamageThreshold(), 1, 100,
                                () -> strengthenDraft.bothDamageThreshold, value -> strengthenDraft.bothDamageThreshold = value))
                        .option(intOption("伤防都高-防御", "伤防同时达标时的防御阈值。", strengthenDefaults.bothDefenseThreshold(), 1, 100,
                                () -> strengthenDraft.bothDefenseThreshold, value -> strengthenDraft.bothDefenseThreshold = value))
                        .option(intOption("几率侧重阈值", "几率/概率/率类词条达到此值时停止并保留。", strengthenDefaults.focusChanceThreshold(), 1, 100,
                                () -> strengthenDraft.focusChanceThreshold, value -> strengthenDraft.focusChanceThreshold = value))
                        .option(booleanOption("自动打开菜单", "启动自动强化时自动打开 /cd 并进入装备强化。", strengthenDefaults.autoOpenStrengthenMenu(),
                                () -> strengthenDraft.autoOpenStrengthenMenu, value -> strengthenDraft.autoOpenStrengthenMenu = value))
                        .option(booleanOption("自动放入手持", "进入强化菜单后自动把当前主手物品放入强化槽。", strengthenDefaults.autoInsertHeldItem(),
                                () -> strengthenDraft.autoInsertHeldItem, value -> strengthenDraft.autoInsertHeldItem = value))
                        .option(booleanOption("启动先清属性", "开始强化前先进入清除属性菜单清一次。", strengthenDefaults.clearBeforeStart(),
                                () -> strengthenDraft.clearBeforeStart, value -> strengthenDraft.clearBeforeStart = value))
                        .option(booleanOption("差词条自动清", "前 N 个词条不同且和值较低时自动进入清除属性菜单。", strengthenDefaults.autoClearMixedLowAttributes(),
                                () -> strengthenDraft.autoClearMixedLowAttributes, value -> strengthenDraft.autoClearMixedLowAttributes = value))
                        .option(intOption("检查前N词条", "用于差词条自动清除判断的前几个词条。", strengthenDefaults.autoClearAttributeCount(), 2, 6,
                                () -> strengthenDraft.autoClearAttributeCount, value -> strengthenDraft.autoClearAttributeCount = value))
                        .option(intOption("清除和值阈值", "前 N 个词条和值不高于此值时自动清除属性。", strengthenDefaults.autoClearAttributeSumThreshold(), 1, 100,
                                () -> strengthenDraft.autoClearAttributeSumThreshold, value -> strengthenDraft.autoClearAttributeSumThreshold = value))
                        .option(stringOption("CD命令", "自动打开菜单时发送的命令，不含斜杠。", strengthenDefaults.cdCommand(),
                                () -> strengthenDraft.cdCommand, value -> strengthenDraft.cdCommand = value))
                        .option(stringOption("装备分类名", "CD 菜单中进入装备功能的按钮名。", strengthenDefaults.equipmentCategoryLabel(),
                                () -> strengthenDraft.equipmentCategoryLabel, value -> strengthenDraft.equipmentCategoryLabel = value))
                        .option(stringOption("强化菜单名", "装备分类中进入装备强化的按钮名。", strengthenDefaults.strengthenMenuLabel(),
                                () -> strengthenDraft.strengthenMenuLabel, value -> strengthenDraft.strengthenMenuLabel = value))
                        .option(stringOption("清属性菜单名", "装备分类中进入清除属性的按钮名。", strengthenDefaults.clearAttributesMenuLabel(),
                                () -> strengthenDraft.clearAttributesMenuLabel, value -> strengthenDraft.clearAttributesMenuLabel = value))
                        .option(stringOption("确认清除名", "清除属性界面中确认执行的按钮名。", strengthenDefaults.clearAttributesConfirmLabel(),
                                () -> strengthenDraft.clearAttributesConfirmLabel, value -> strengthenDraft.clearAttributesConfirmLabel = value))
                        .option(booleanOption("飞书出货通知", "自动强化命中保留条件时发送飞书机器人通知。", strengthenDefaults.feishuNotificationEnabled(),
                                () -> strengthenDraft.feishuNotificationEnabled, value -> strengthenDraft.feishuNotificationEnabled = value))
                        .option(stringOption("飞书App ID", "飞书应用的 App ID。", strengthenDefaults.feishuAppId(),
                                () -> strengthenDraft.feishuAppId, value -> strengthenDraft.feishuAppId = value))
                        .option(stringOption("飞书App Secret", "飞书应用的 App Secret。", strengthenDefaults.feishuAppSecret(),
                                () -> strengthenDraft.feishuAppSecret, value -> strengthenDraft.feishuAppSecret = value))
                        .option(stringOption("飞书接收类型", "消息接收 ID 类型，例如 chat_id、open_id、user_id、email。", strengthenDefaults.feishuReceiveIdType(),
                                () -> strengthenDraft.feishuReceiveIdType, value -> strengthenDraft.feishuReceiveIdType = value))
                        .option(stringOption("飞书接收ID", "群聊 chat_id 或用户 open_id 等，需与接收类型一致。", strengthenDefaults.feishuReceiveId(),
                                () -> strengthenDraft.feishuReceiveId, value -> strengthenDraft.feishuReceiveId = value))
                        .build())
                .save(() -> {
                    ScreenProbeGlobalConfig.Config globalSaved = globalDraft.toConfig().clamped();
                    ScreenProbeGlobalConfig.setAndSave(client, globalSaved);
                    ScreenProbeClient.applyGlobalConfig(client, globalSaved);
                    AutoNetherWartConfig.Config saved = draft.toConfig().clamped();
                    AutoNetherWartConfig.setAndSave(client, saved);
                    AutoNetherWartController.applyConfig(client, saved);
                    AutoStrengthenConfig.Config strengthenSaved = strengthenDraft.toConfig().clamped();
                    AutoStrengthenConfig.setAndSave(client, strengthenSaved);
                })
                .build();
        return config.generateScreen(parent);
    }

    private static Option<Boolean> booleanOption(String name, String description, boolean defaultValue,
                                                 java.util.function.Supplier<Boolean> getter,
                                                 java.util.function.Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(description)))
                .binding(Binding.generic(defaultValue, getter, setter))
                .controller(option -> BooleanControllerBuilder.create(option).trueFalseFormatter())
                .build();
    }

    private static Option<Integer> intOption(String name, String description, int defaultValue, int min, int max,
                                             java.util.function.Supplier<Integer> getter,
                                             java.util.function.Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(description)))
                .binding(Binding.generic(defaultValue, getter, setter))
                .controller(option -> IntegerSliderControllerBuilder.create(option).range(min, max).step(1))
                .build();
    }

    private static Option<String> stringOption(String name, String description, String defaultValue,
                                               java.util.function.Supplier<String> getter,
                                               java.util.function.Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(description)))
                .binding(Binding.generic(defaultValue, getter, setter))
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static final class Draft {
        private boolean enabled;
        private int scanRadius;
        private int actionsPerTick;
        private boolean autoWalk;
        private boolean minecartForwardAssist;
        private boolean autoRideMinecart;
        private boolean autoPlaceMinecart;
        private boolean autoClearNonPlayerMinecart;
        private boolean autoRespawnReturn;
        private boolean spectatorDangerDetection;
        private int respawnReturnDelayTicks;
        private int dangerReturnDelayTicks;
        private int dangerCommandCooldownTicks;
        private int startTeleportCooldownTicks;
        private int minecartActionCooldownTicks;
        private int refillScreenDelayTicks;
        private int refillTimeoutTicks;
        private int storageRefillStacksPerRun;
        private String startTerritory;
        private String safeTerritory;
        private String dangerPlayerName;
        private String cropStorageCommand;

        private Draft(AutoNetherWartConfig.Config current) {
            enabled = current.enabled();
            scanRadius = current.scanRadius();
            actionsPerTick = current.actionsPerTick();
            autoWalk = current.autoWalk();
            minecartForwardAssist = current.minecartForwardAssist();
            autoRideMinecart = current.autoRideMinecart();
            autoPlaceMinecart = current.autoPlaceMinecart();
            autoClearNonPlayerMinecart = current.autoClearNonPlayerMinecart();
            autoRespawnReturn = current.autoRespawnReturn();
            spectatorDangerDetection = current.spectatorDangerDetection();
            respawnReturnDelayTicks = current.respawnReturnDelayTicks();
            dangerReturnDelayTicks = current.dangerReturnDelayTicks();
            dangerCommandCooldownTicks = current.dangerCommandCooldownTicks();
            startTeleportCooldownTicks = current.startTeleportCooldownTicks();
            minecartActionCooldownTicks = current.minecartActionCooldownTicks();
            refillScreenDelayTicks = current.refillScreenDelayTicks();
            refillTimeoutTicks = current.refillTimeoutTicks();
            storageRefillStacksPerRun = current.storageRefillStacksPerRun();
            startTerritory = current.startTerritory();
            safeTerritory = current.safeTerritory();
            dangerPlayerName = current.dangerPlayerName();
            cropStorageCommand = current.cropStorageCommand();
        }

        private AutoNetherWartConfig.Config toConfig() {
            return new AutoNetherWartConfig.Config(
                    enabled,
                    scanRadius,
                    actionsPerTick,
                    autoWalk,
                    minecartForwardAssist,
                    autoRideMinecart,
                    autoPlaceMinecart,
                    autoClearNonPlayerMinecart,
                    autoRespawnReturn,
                    spectatorDangerDetection,
                    respawnReturnDelayTicks,
                    dangerReturnDelayTicks,
                    dangerCommandCooldownTicks,
                    startTeleportCooldownTicks,
                    minecartActionCooldownTicks,
                    refillScreenDelayTicks,
                    refillTimeoutTicks,
                    storageRefillStacksPerRun,
                    startTerritory,
                    safeTerritory,
                    dangerPlayerName,
                    cropStorageCommand
            );
        }
    }

    private static final class GlobalDraft {
        private int menuActionDelayTicks;
        private int menuResultDelayTicks;
        private int menuCloseDelayTicks;
        private boolean enableAutoWart;
        private boolean enableAutoStrengthen;
        private boolean enableAutoSell;
        private boolean enableAutoBoat;
        private boolean enableNodeBot;
        private boolean enableAutoDragon;
        private boolean enableAutoChunk;
        private boolean enableAutoVillagerTrade;
        private boolean lowTpsAutoSlowdown;
        private int lowTpsThreshold;
        private int lowTpsDelayMultiplier;
        private int tpsQueryIntervalTicks;

        private GlobalDraft(ScreenProbeGlobalConfig.Config current) {
            menuActionDelayTicks = current.menuActionDelayTicks();
            menuResultDelayTicks = current.menuResultDelayTicks();
            menuCloseDelayTicks = current.menuCloseDelayTicks();
            enableAutoWart = current.enableAutoWart();
            enableAutoStrengthen = current.enableAutoStrengthen();
            enableAutoSell = current.enableAutoSell();
            enableAutoBoat = current.enableAutoBoat();
            enableNodeBot = current.enableNodeBot();
            enableAutoDragon = current.enableAutoDragon();
            enableAutoChunk = current.enableAutoChunk();
            enableAutoVillagerTrade = current.enableAutoVillagerTrade();
            lowTpsAutoSlowdown = current.lowTpsAutoSlowdown();
            lowTpsThreshold = (int) current.lowTpsThreshold();
            lowTpsDelayMultiplier = current.lowTpsDelayMultiplier();
            tpsQueryIntervalTicks = current.tpsQueryIntervalTicks();
        }

        private ScreenProbeGlobalConfig.Config toConfig() {
            return new ScreenProbeGlobalConfig.Config(
                    menuActionDelayTicks,
                    menuResultDelayTicks,
                    menuCloseDelayTicks,
                    enableAutoWart,
                    enableAutoStrengthen,
                    enableAutoSell,
                    enableAutoBoat,
                    enableNodeBot,
                    enableAutoDragon,
                    enableAutoChunk,
                    enableAutoVillagerTrade,
                    lowTpsAutoSlowdown,
                    lowTpsThreshold,
                    lowTpsDelayMultiplier,
                    tpsQueryIntervalTicks
            );
        }
    }

    private static final class StrengthenDraft {
        private int defaultRolls;
        private int damageThreshold;
        private int defenseThreshold;
        private int bothDamageThreshold;
        private int bothDefenseThreshold;
        private int focusChanceThreshold;
        private boolean autoOpenStrengthenMenu;
        private boolean autoInsertHeldItem;
        private boolean clearBeforeStart;
        private boolean autoClearMixedLowAttributes;
        private int autoClearAttributeCount;
        private int autoClearAttributeSumThreshold;
        private String cdCommand;
        private String equipmentCategoryLabel;
        private String strengthenMenuLabel;
        private String clearAttributesMenuLabel;
        private String clearAttributesConfirmLabel;
        private boolean feishuNotificationEnabled;
        private String feishuAppId;
        private String feishuAppSecret;
        private String feishuReceiveIdType;
        private String feishuReceiveId;

        private StrengthenDraft(AutoStrengthenConfig.Config current) {
            defaultRolls = current.defaultRolls();
            damageThreshold = current.damageThreshold();
            defenseThreshold = current.defenseThreshold();
            bothDamageThreshold = current.bothDamageThreshold();
            bothDefenseThreshold = current.bothDefenseThreshold();
            focusChanceThreshold = current.focusChanceThreshold();
            autoOpenStrengthenMenu = current.autoOpenStrengthenMenu();
            autoInsertHeldItem = current.autoInsertHeldItem();
            clearBeforeStart = current.clearBeforeStart();
            autoClearMixedLowAttributes = current.autoClearMixedLowAttributes();
            autoClearAttributeCount = current.autoClearAttributeCount();
            autoClearAttributeSumThreshold = current.autoClearAttributeSumThreshold();
            cdCommand = current.cdCommand();
            equipmentCategoryLabel = current.equipmentCategoryLabel();
            strengthenMenuLabel = current.strengthenMenuLabel();
            clearAttributesMenuLabel = current.clearAttributesMenuLabel();
            clearAttributesConfirmLabel = current.clearAttributesConfirmLabel();
            feishuNotificationEnabled = current.feishuNotificationEnabled();
            feishuAppId = current.feishuAppId();
            feishuAppSecret = current.feishuAppSecret();
            feishuReceiveIdType = current.feishuReceiveIdType();
            feishuReceiveId = current.feishuReceiveId();
        }

        private AutoStrengthenConfig.Config toConfig() {
            return new AutoStrengthenConfig.Config(
                    defaultRolls,
                    damageThreshold,
                    defenseThreshold,
                    bothDamageThreshold,
                    bothDefenseThreshold,
                    focusChanceThreshold,
                    autoOpenStrengthenMenu,
                    autoInsertHeldItem,
                    clearBeforeStart,
                    autoClearMixedLowAttributes,
                    autoClearAttributeCount,
                    autoClearAttributeSumThreshold,
                    cdCommand,
                    equipmentCategoryLabel,
                    strengthenMenuLabel,
                    clearAttributesMenuLabel,
                    clearAttributesConfirmLabel,
                    feishuNotificationEnabled,
                    feishuAppId,
                    feishuAppSecret,
                    feishuReceiveIdType,
                    feishuReceiveId
            );
        }
    }

}
