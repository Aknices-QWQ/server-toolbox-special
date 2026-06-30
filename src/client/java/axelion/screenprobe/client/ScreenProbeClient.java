package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public final class ScreenProbeClient implements ClientModInitializer {
    private static final String CLIENT_COMMAND = "autosell";
    private static final String CHAT_MODULE = "自动售卖";
    private static final String STATS_FILE_NAME = "autosell-stats.txt";
    private static final String SYSTEM_SHOP_TITLE = "系统商店";
    private static final String SELL_MENU_TITLE = "收购商品";
    private static final String SELL_CONFIRM_TITLE = "确认交易";
    private static final int REFILL_ALL_TARGET = -1;
    private static final Set<String> SELLABLE_ITEM_IDS = Set.of(
            "minecraft:dried_kelp_block",
            "minecraft:popped_chorus_fruit",
            "minecraft:iron_nugget",
            "minecraft:ender_pearl",
            "minecraft:apple",
            "minecraft:wheat",
            "minecraft:potato",
            "minecraft:carrot",
            "minecraft:pumpkin",
            "minecraft:melon",
            "minecraft:melon_slice",
            "minecraft:nether_wart",
            "minecraft:cactus",
            "minecraft:bamboo",
            "minecraft:sugar_cane",
            "minecraft:beetroot",
            "minecraft:cocoa_beans",
            "minecraft:bone",
            "minecraft:rotten_flesh",
            "minecraft:cobweb",
            "minecraft:tropical_fish",
            "minecraft:honeycomb_block",
            "minecraft:leather"
    );
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int FLOW_TIMEOUT_TICKS = 120;
    private static final int YOLO_THRESHOLD = 64 * 3;
    private static final int MAX_SCAN_RANGE = 256;
    private static final int MAX_SCAN_RANGE_SQUARED = MAX_SCAN_RANGE * MAX_SCAN_RANGE;
    private static final int MAX_SCAN_CHUNK_RADIUS = 16;
    private static final int MAX_CHEST_HEIGHT_ADVANTAGE = 6;
    private static final int NAVIGATION_TIMEOUT_TICKS = 20 * 45;
    private static final int CHEST_OPEN_TIMEOUT_TICKS = 20 * 8;
    private static final int CHEST_OPERATION_COOLDOWN_TICKS = 20 * 60 * 30;
    private static final int MAX_CHEST_RETRIES = 5;
    private static final int MAX_REFILL_RETRIES = 3;
    private static final int MAX_MODE_SEARCH_INTERVAL_TICKS = 20;
    private static final int LOCAL_PATH_NODE_LIMIT = 2048;
    private static final int LOCAL_PATH_SEARCH_RADIUS = 56;
    private static final double LOCAL_PATH_REACH_DISTANCE_SQUARED = 1.25;
    private static final double CHEST_INTERACT_DISTANCE_SQUARED = 20.25D;
    private static final int MAX_SAFE_DROP = 2;
    private static final int REFILL_TIMEOUT_TICKS = 20 * 30;

    private static Screen lastScreen;
    private static AutoSellState autoSellState = AutoSellState.IDLE;
    private static AutoSellMode autoSellMode = AutoSellMode.OFF;
    private static int autoSellDelayTicks;
    private static int autoSellTimeoutTicks;
    private static int pendingSellCount;
    private static int targetSellCount;
    private static int targetSellSoldCount;
    private static boolean yoloCheckQueued;
    private static int retryAttemptsRemaining;
    private static int refillRetryAttemptsRemaining;
    private static boolean retryAfterClose;
    private static LocalDate saleStatsDate;
    private static int soldTodayCount;
    private static boolean saleStatsLoaded;
    private static long clientTickCounter;
    private static long nextMaxSearchTick;
    private static ChestTarget activeChestTarget;
    private static int activeChestRetries;
    private static int activeChestStandIndex;
    private static List<BlockPos> activePath = List.of();
    private static int activePathIndex;
    private static final Map<Long, Long> chestCooldownUntilTick = new HashMap<>();
    private static final Map<Long, Long> chestGroupCooldownUntilTick = new HashMap<>();
    private static BlockPos boundaryPoint1;
    private static BlockPos boundaryPoint2;
    private static boolean emergencyStopLatch;
    private static boolean suppressCloseTriggerOnce;
    private static long activeChestGroupKey = Long.MIN_VALUE;
    private static boolean clientSessionArmed;
    private static int pendingClickGuiTicks;
    private static String autoSellRefillItemQuery = "下界疣";
    private static int refillInventoryCountBefore;
    private static int refillMovedStackClicks;

    @Override
    public void onInitializeClient() {
        NodeBotController.initialize();
        AutoNetherWartController.initialize(Minecraft.getInstance());
        ScreenProbeConfigScreenFactory.preload();
        ClientCommandRegistrationCallback.EVENT.register(ScreenProbeClient::registerCommands);
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            ScreenProbeGlobalConfig.handleServerMessage(message.getString());
            return true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ScreenProbeClient::onClientTick);
        ScreenProbe.LOGGER.info("{} ready. Use /{} , /{} yolo, /{} yolo max, /autodragon or /autochunk.", ScreenProbeGlobalConfig.BRAND, CLIENT_COMMAND, CLIENT_COMMAND, CLIENT_COMMAND);
    }

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> dispatcher,
                                         net.minecraft.commands.CommandBuildContext context) {
        AutoDragonController.registerCommands(dispatcher);
        AutoChunkMinerController.registerCommands(dispatcher);
        CdHelpDumpController.registerCommands(dispatcher);
        AutoStrengthenController.registerCommands(dispatcher);
        AutoNetherWartController.registerCommands(dispatcher);
        AutoBoatDispenserController.registerCommands(dispatcher);
        AutoVillagerTradeController.registerCommands(dispatcher);
        NodeBotController.registerCommands(dispatcher);
        dispatcher.register(ClientCommands.literal("autosettings")
                .executes(command -> {
                    openClickGuiSoon();
                    return 1;
                }));
        dispatcher.register(ClientCommands.literal("autohelp")
                .executes(command -> {
                    sendAutoHelp(command.getSource().getClient());
                    return 1;
                }));
        dispatcher.register(ClientCommands.literal(CLIENT_COMMAND)
                .executes(command -> {
                    startSingleRun(command.getSource().getClient());
                    return 1;
                })
                .then(ClientCommands.literal("stop")
                        .executes(command -> {
                            stopAutoSellFromCommand(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("refill")
                        .executes(command -> {
                            startRefillSellRun(command.getSource().getClient(), REFILL_ALL_TARGET);
                            return 1;
                        })
                        .then(ClientCommands.literal("all")
                                .executes(command -> {
                                    startRefillSellRun(command.getSource().getClient(), REFILL_ALL_TARGET);
                                    return 1;
                                }))
                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                .executes(command -> {
                                    startRefillSellRun(command.getSource().getClient(), IntegerArgumentType.getInteger(command, "count"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("item")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(command -> {
                                    autoSellRefillItemQuery = StringArgumentType.getString(command, "name").trim();
                                    if (autoSellRefillItemQuery.isBlank()) {
                                        autoSellRefillItemQuery = "下界疣";
                                    }
                                    sendMessage(command.getSource().getClient(), "自动售卖补仓目标已设为：" + autoSellRefillItemQuery);
                                    return 1;
                                })))
                .then(ClientCommands.literal("setpoint")
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1, 2))
                                .executes(command -> {
                                    int index = IntegerArgumentType.getInteger(command, "index");
                                    setBoundaryPoint(command.getSource().getClient(), index);
                                    return 1;
                                })))
                .then(ClientCommands.literal("yolo")
                        .executes(command -> {
                            toggleContinuousMode(command.getSource().getClient(), AutoSellMode.YOLO);
                            return 1;
                        })
                        .then(ClientCommands.literal("max")
                                .executes(command -> {
                                    toggleContinuousMode(command.getSource().getClient(), AutoSellMode.YOLO_MAX);
                                    return 1;
                                }))));
    }

    private static void onClientTick(Minecraft client) {
        clientTickCounter++;
        pruneChestCooldowns();
        ensureSessionReset(client);
        handleEmergencyStop(client);
        handlePendingClickGui(client);

        Screen screen = client.screen;
        Screen previousScreen = lastScreen;
        if (screen != previousScreen) {
            lastScreen = screen;
            handleScreenTransition(client, previousScreen, screen);
            if (screen != null && client.player != null) {
                inspectCurrentScreen(client, screen);
            }
        }

        tickAutoSell(client);
        AutoDragonController.tick(client);
        AutoChunkMinerController.tick(client);
        CdHelpDumpController.tick(client);
        AutoStrengthenController.tick(client);
        AutoNetherWartController.tick(client);
        AutoBoatDispenserController.tick(client);
        AutoVillagerTradeController.tick(client);
    }

    private static void openClickGuiSoon() {
        pendingClickGuiTicks = 2;
    }

    private static void handlePendingClickGui(Minecraft client) {
        if (pendingClickGuiTicks <= 0) {
            return;
        }

        pendingClickGuiTicks--;
        if (pendingClickGuiTicks > 0) {
            return;
        }

        try {
            client.setScreen(ScreenProbeConfigScreenFactory.create(null));
        } catch (Throwable throwable) {
            pendingClickGuiTicks = 0;
            ScreenProbe.LOGGER.error("Failed to open autosettings", throwable);
            sendMessage(client, "设置界面打开失败。如果刚覆盖过 mod jar，请完全重启客户端后再试。错误："
                    + throwable.getClass().getSimpleName());
        }
    }

    private static void ensureSessionReset(Minecraft client) {
        boolean ready = isClientReady(client);
        if (!ready) {
            clientSessionArmed = true;
            lastScreen = null;
            return;
        }

        if (!clientSessionArmed) {
            return;
        }

        clientSessionArmed = false;
        hardResetAutomation(client);
        sendFeatureList(client);
    }

    private static void handleEmergencyStop(Minecraft client) {
        if (autoSellMode != AutoSellMode.YOLO_MAX) {
            emergencyStopLatch = false;
            return;
        }

        boolean pressed = isEmergencyStopPressed(client);
        if (!pressed) {
            emergencyStopLatch = false;
            return;
        }

        if (emergencyStopLatch) {
            return;
        }
        emergencyStopLatch = true;

        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
        if (client.screen != null) {
            client.setScreen(null);
        }

        if (autoSellMode != AutoSellMode.OFF || autoSellState != AutoSellState.IDLE) {
            suppressCloseTriggerOnce = true;
            stopAutoSell(client, "主人，我已经紧急停下来了。");
        } else {
            resetAutomationState(client, true);
        }
    }

    private static boolean isEmergencyStopPressed(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }

        long window = client.getWindow().handle();
        return window != 0 && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
    }

    private static void startSingleRun(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoSell()) {
            sendMessage(client, "自动售卖已在 /autosettings 全局设置中屏蔽。");
            return;
        }
        AutoDragonController.handleSessionReset(client);
        AutoNetherWartController.handleSessionReset(client);
        if (!isClientReady(client)) {
            sendMessage(client, "主人，现在还不能启动自动收售呢。");
            return;
        }

        if (isBlockedByForeignScreen(client.screen)) {
            sendMessage(client, "主人先把眼前这个界面关一下，我才好替你开工呀。");
            return;
        }

        resetAutomationState(client, false);
        autoSellMode = AutoSellMode.SINGLE_RUN;
        prepareSellAttempt();
        startSellFlow(client);
    }

    private static void startRefillSellRun(Minecraft client, int sellCount) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoSell()) {
            sendMessage(client, "自动售卖已在 /autosettings 全局设置中屏蔽。");
            return;
        }
        AutoDragonController.handleSessionReset(client);
        AutoNetherWartController.handleSessionReset(client);
        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动补仓自动售卖。");
            return;
        }

        if (isBlockedByForeignScreen(client.screen)) {
            sendMessage(client, "先关闭当前界面，再启动补仓自动售卖。");
            return;
        }

        resetAutomationState(client, false);
        autoSellMode = AutoSellMode.SINGLE_RUN;
        prepareSellAttempt();
        targetSellCount = sellCount == REFILL_ALL_TARGET ? REFILL_ALL_TARGET : Math.max(0, sellCount);
        targetSellSoldCount = 0;
        startRefillBeforeSellFlow(client);
    }

    private static void toggleContinuousMode(Minecraft client, AutoSellMode targetMode) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoSell()) {
            sendMessage(client, "自动售卖已在 /autosettings 全局设置中屏蔽。");
            return;
        }
        AutoDragonController.handleSessionReset(client);
        if (autoSellMode == targetMode) {
            if (targetMode == AutoSellMode.YOLO_MAX) {
                stopAutoSell(client, "主人，巡仓收售先乖乖停下来啦。");
            } else {
                stopAutoSell(client, "主人，持续收售模式先乖乖停下啦。");
            }
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "主人，现在还开不了这个模式哦。");
            return;
        }

        resetAutomationState(client, false);
        autoSellMode = targetMode;
        yoloCheckQueued = true;
        nextMaxSearchTick = clientTickCounter;
        if (targetMode == AutoSellMode.YOLO_MAX) {
            sendMessage(client, "主人，巡仓收售已经待命啦，我会自己找附近箱子，拿货再替你悄悄卖掉。");
        } else {
            sendMessage(client, "主人，持续收售模式已经待命啦，每次你关掉页面后我都会帮你检查一下。");
        }
    }

    static void stopAutoSellForExternalAutomation(Minecraft client) {
        if (autoSellMode == AutoSellMode.OFF && autoSellState == AutoSellState.IDLE) {
            return;
        }

        resetAutomationState(client, true);
        sendMessage(client, "主人，自动收售先停下，给自动打龙让路。");
    }

    private static void stopAutoSellFromCommand(Minecraft client) {
        closeActiveContainerScreen(client);
        resetAutomationState(client, true);
        sendMessage(client, "自动售卖已停止，补仓、售卖、巡仓状态已清空。");
    }

    static void applyGlobalConfig(Minecraft client, ScreenProbeGlobalConfig.Config config) {
        if (!config.enableAutoSell()) {
            stopAutoSell(client, "自动售卖已被全局设置关闭。");
        }
        if (!config.enableAutoWart()) {
            AutoNetherWartController.setEnabled(client, false);
        }
        if (!config.enableAutoStrengthen()) {
            AutoStrengthenController.setEnabled(client, false);
        }
        if (!config.enableAutoBoat()) {
            AutoBoatDispenserController.setEnabled(client, false);
        }
        if (!config.enableNodeBot()) {
            NodeBotController.setEnabled(client, false);
        }
        if (!config.enableAutoDragon()) {
            AutoDragonController.handleSessionReset(client);
        }
        if (!config.enableAutoChunk()) {
            AutoChunkMinerController.handleSessionReset(client);
        }
        if (!config.enableAutoVillagerTrade()) {
            AutoVillagerTradeController.handleSessionReset(client);
        }
    }

    private static void tickAutoSell(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoSell()) {
            if (autoSellMode != AutoSellMode.OFF || autoSellState != AutoSellState.IDLE) {
                stopAutoSell(client, "自动售卖已被全局设置屏蔽。");
            }
            return;
        }

        if (autoSellMode != AutoSellMode.OFF || autoSellState != AutoSellState.IDLE) {
            ScreenProbeGlobalConfig.tick(client);
        }

        if (!isClientReady(client)) {
            if (autoSellMode != AutoSellMode.OFF) {
                stopAutoSell(client, "主人，你已经不在游戏里啦，我就先停下来等你。");
            }
            return;
        }

        if (autoSellState == AutoSellState.IDLE) {
            if (autoSellMode == AutoSellMode.YOLO) {
                tickYoloMode(client);
            } else if (autoSellMode == AutoSellMode.YOLO_MAX) {
                tickYoloMaxMode(client);
            }
            return;
        }

        if (autoSellTimeoutTicks-- <= 0) {
            handleStateTimeout(client);
            return;
        }

        if (autoSellDelayTicks > 0) {
            autoSellDelayTicks--;
            return;
        }

        Screen screen = client.screen;
        switch (autoSellState) {
            case WAITING_FOR_SELL_MENU -> handleSellMenuState(client, screen);
            case WAITING_FOR_CONFIRM_DIALOG -> handleConfirmDialogState(client, screen);
            case WAITING_FOR_MENU_RETURN -> handleMenuReturnState(client, screen);
            case WAITING_FOR_REFILL_CD_MENU -> handleRefillCdMenu(client, screen);
            case WAITING_FOR_REFILL_CROP_STORAGE -> handleRefillCropStorage(client, screen);
            case WAITING_FOR_REFILL_ITEM_STORAGE -> handleRefillItemStorage(client, screen);
            case WAITING_FOR_REFILL_VERIFY -> handleRefillVerify(client);
            case NAVIGATING_TO_CHEST -> handleChestNavigationState(client, screen);
            case WAITING_FOR_CHEST_SCREEN -> handleChestOpenState(client, screen);
            case IDLE -> {
            }
        }
    }

    private static void setBoundaryPoint(Minecraft client, int index) {
        if (!isClientReady(client)) {
            sendMessage(client, "主人，现在还记不了边界点呢。");
            return;
        }

        BlockPos pos = client.player.blockPosition().immutable();
        if (index == 1) {
            boundaryPoint1 = pos;
        } else {
            boundaryPoint2 = pos;
        }

        sendMessage(client, Component.empty()
                .append(Component.literal("主人，我记下了边界点 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(index)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" ：").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.GREEN)));
    }

    private static void tickYoloMode(Minecraft client) {
        if (autoSellMode != AutoSellMode.YOLO) {
            yoloCheckQueued = false;
            return;
        }

        if (!yoloCheckQueued || isBlockedByForeignScreen(client.screen)) {
            return;
        }

        yoloCheckQueued = false;
        if (!hasEnoughSellableItems(client)) {
            return;
        }

        prepareSellAttempt();
        startSellFlow(client);
    }

    private static void tickYoloMaxMode(Minecraft client) {
        if (autoSellMode != AutoSellMode.YOLO_MAX) {
            yoloCheckQueued = false;
            return;
        }

        if (isBlockedByForeignScreen(client.screen)) {
            return;
        }

        if (!yoloCheckQueued && clientTickCounter < nextMaxSearchTick) {
            return;
        }

        yoloCheckQueued = false;
        nextMaxSearchTick = clientTickCounter + MAX_MODE_SEARCH_INTERVAL_TICKS;

        boolean hasSellables = hasAnySellableItems(client);
        // Once every slot is occupied, sell first instead of trying to top off partial stacks.
        Optional<ChestTarget> target = hasInventoryFreeSlot(client)
                ? findNearestChestTarget(client)
                : Optional.empty();
        if (target.isEmpty()) {
            if (hasSellables) {
                prepareSellAttempt();
                startSellFlow(client);
                return;
            }
            nextMaxSearchTick = clientTickCounter + 60;
            return;
        }

        beginChestRun(client, target.get());
    }

    private static void startSellFlow(Minecraft client) {
        autoSellState = AutoSellState.WAITING_FOR_SELL_MENU;
        autoSellDelayTicks = screenActionDelayTicks(client);
        autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;

        Screen screen = client.screen;
        if (isSystemShop(screen) || isSellMenu(screen)) {
            autoSellDelayTicks = 0;
            return;
        }

        if (ScreenProbeInspector.isSellQuantityDialog(screen)) {
            autoSellState = AutoSellState.WAITING_FOR_CONFIRM_DIALOG;
            autoSellDelayTicks = screenActionDelayTicks(client);
            return;
        }

        client.getConnection().sendCommand("sell");
    }

    private static void startRefillBeforeSellFlow(Minecraft client) {
        int currentInventoryCount = countInventoryByQuery(client, autoSellRefillItemQuery);
        if (targetSellCount == REFILL_ALL_TARGET) {
            if (currentInventoryCount > 0) {
                sendMessage(client, "背包已有 " + currentInventoryCount + " 个 " + autoSellRefillItemQuery
                        + "，all 模式先售卖背包库存。");
                prepareSellAttempt();
                startSellFlow(client);
                return;
            }
        } else if (targetSellCount > 0) {
            int remainingTarget = Math.max(0, targetSellCount - targetSellSoldCount);
            if (remainingTarget <= 0) {
                finishTargetedRefillSell(client);
                return;
            }
            if (currentInventoryCount >= remainingTarget) {
                sendMessage(client, "背包已有 " + currentInventoryCount + " 个 " + autoSellRefillItemQuery
                        + "，本轮还需 " + remainingTarget + " 个，直接开始自动售卖。");
                prepareSellAttempt();
                startSellFlow(client);
                return;
            }
        } else if (currentInventoryCount > 0) {
            sendMessage(client, "背包已有 " + currentInventoryCount + " 个 " + autoSellRefillItemQuery
                    + "，不再从仓库取出，直接开始自动售卖。");
            prepareSellAttempt();
            startSellFlow(client);
            return;
        }

        String command = AutoNetherWartConfig.get(client).cropStorageCommand();
        if (client.player != null && client.screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        refillInventoryCountBefore = 0;
        refillMovedStackClicks = 0;
        refillRetryAttemptsRemaining = MAX_REFILL_RETRIES;
        autoSellState = AutoSellState.WAITING_FOR_REFILL_CD_MENU;
        autoSellDelayTicks = refillScreenDelayTicks(client);
        autoSellTimeoutTicks = REFILL_TIMEOUT_TICKS;
        client.getConnection().sendCommand(command);
        sendMessage(client, "正在打开 /" + command + "，从作物仓库补取 " + autoSellRefillItemQuery
                + (targetSellCount == REFILL_ALL_TARGET ? " 后全部售卖。" : targetSellCount > 0 ? " 后循环售卖到 " + targetSellCount + " 个。" : " 后自动售卖。"));
    }

    private static void handleSellMenuState(Minecraft client, Screen screen) {
        if (ScreenProbeInspector.isSellQuantityDialog(screen)) {
            pendingSellCount = resolveSellQuantityFromDialog(screen, pendingSellCount);
            autoSellState = AutoSellState.WAITING_FOR_CONFIRM_DIALOG;
            autoSellDelayTicks = screenActionDelayTicks(client);
            autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
            return;
        }

        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        if (isSystemShop(screen)) {
            SlotClickResult result = clickMatchingSystemShopSlot(containerScreen);
            if (!result.success()) {
                if (result.retryable() && retryCurrentFlow(client)) {
                    return;
                }
                finishSellCycle(client, result.message(), shouldAnnounceFailures());
                return;
            }

            autoSellDelayTicks = screenActionDelayTicks(client);
            autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
            return;
        }

        if (!isSellMenu(screen)) {
            return;
        }

        SlotClickResult result = clickSellConfirmSlot(containerScreen);
        if (!result.success()) {
            finishSellCycle(client, result.message(), shouldAnnounceFailures());
            return;
        }

        autoSellState = AutoSellState.WAITING_FOR_CONFIRM_DIALOG;
        autoSellDelayTicks = screenActionDelayTicks(client);
        autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
    }

    private static void handleConfirmDialogState(Minecraft client, Screen screen) {
        if (!ScreenProbeInspector.isSellQuantityDialog(screen)) {
            return;
        }

        if (targetSellCount > 0) {
            Integer currentQuantity = ScreenProbeInspector.readSellQuantity(screen);
            int remaining = Math.max(0, targetSellCount - targetSellSoldCount);
            int desiredQuantity = currentQuantity == null || currentQuantity <= 0
                    ? remaining
                    : Math.min(remaining, currentQuantity);
            if (desiredQuantity <= 0) {
                finishTargetedRefillSell(client);
                return;
            }
            if (currentQuantity == null || currentQuantity != desiredQuantity) {
                ScreenProbeInspector.InputAttemptResult inputResult =
                        ScreenProbeInspector.fillFirstEditBox(screen, Integer.toString(desiredQuantity));
                if (!inputResult.success()) {
                    handleSellFailure(client, "没有成功写入指定售卖数量 " + desiredQuantity + "，自动售卖已停止。", true);
                    return;
                }
                pendingSellCount = desiredQuantity;
                autoSellDelayTicks = screenActionDelayTicks(client);
                autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
                return;
            }
        }

        ScreenProbeInspector.ButtonClickResult result = ScreenProbeInspector.clickButtonByText(screen, "确认");
        if (!result.success()) {
            handleSellFailure(client, result.message(), shouldAnnounceFailures());
            return;
        }

        pendingSellCount = resolveSellQuantityFromDialog(screen, pendingSellCount);
        autoSellState = AutoSellState.WAITING_FOR_MENU_RETURN;
        autoSellDelayTicks = screenActionDelayTicks(client);
        autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
    }

    private static void handleRefillCdMenu(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        int cropSlot = findSlotByKeywords(containerScreen, false, "作物", "农作物", "庄稼", "仓库", "crop", "storage");
        if (cropSlot < 0) {
            return;
        }

        client.gameMode.handleContainerInput(
                containerScreen.getMenu().containerId,
                cropSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        autoSellState = AutoSellState.WAITING_FOR_REFILL_CROP_STORAGE;
        autoSellDelayTicks = refillScreenDelayTicks(client);
        autoSellTimeoutTicks = REFILL_TIMEOUT_TICKS;
    }

    private static void handleRefillCropStorage(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        int itemSlot = findSlotByRefillQuery(containerScreen, false, autoSellRefillItemQuery);
        if (itemSlot < 0) {
            return;
        }

        client.gameMode.handleContainerInput(
                containerScreen.getMenu().containerId,
                itemSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        autoSellState = AutoSellState.WAITING_FOR_REFILL_ITEM_STORAGE;
        autoSellDelayTicks = refillScreenDelayTicks(client);
        autoSellTimeoutTicks = REFILL_TIMEOUT_TICKS;
    }

    private static void handleRefillItemStorage(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        List<Integer> itemSlots = findSlotsByRefillQuery(containerScreen, false, autoSellRefillItemQuery);
        if (itemSlots.isEmpty()) {
            return;
        }

        refillInventoryCountBefore = countInventoryByQuery(client, autoSellRefillItemQuery);
        int neededItems = computeRefillNeededItems(client, refillInventoryCountBefore);
        if (neededItems <= 0) {
            if (client.player != null) {
                client.player.closeContainer();
            }
            if (refillInventoryCountBefore > 0) {
                sendMessage(client, "背包已有 " + refillInventoryCountBefore + " 个 " + autoSellRefillItemQuery
                        + "，或背包没有可用空间，不再从仓库取出，直接开始自动售卖。");
                prepareSellAttempt();
                startSellFlow(client);
            } else {
                handleSellFailure(client, "背包没有 " + autoSellRefillItemQuery + "，且没有可用空间从作物仓库取出。", true);
            }
            return;
        }
        int quickTakeSlot = findQuickTakeCurrentPageSlot(containerScreen);
        if (shouldUseQuickTakeCurrentPage(neededItems) && quickTakeSlot >= 0) {
            clickContainerSlot(client, containerScreen, quickTakeSlot, 0, ContainerInput.PICKUP);
            refillMovedStackClicks = 1;
        } else {
            refillMovedStackClicks = quickMoveRefillStacks(client, containerScreen, itemSlots, neededItems);
        }
        if (client.player != null) {
            client.player.closeContainer();
        }
        autoSellState = AutoSellState.WAITING_FOR_REFILL_VERIFY;
        autoSellDelayTicks = refillScreenDelayTicks(client);
        autoSellTimeoutTicks = REFILL_TIMEOUT_TICKS;
    }

    private static int computeRefillNeededItems(Minecraft client, int currentInventoryCount) {
        int inventoryCapacity = countInventoryCapacityForQuery(client, autoSellRefillItemQuery);
        if (targetSellCount == REFILL_ALL_TARGET) {
            return inventoryCapacity;
        }
        if (targetSellCount > 0) {
            int remainingTarget = Math.max(0, targetSellCount - targetSellSoldCount);
            return Math.min(Math.max(0, remainingTarget - currentInventoryCount), inventoryCapacity);
        }
        return inventoryCapacity;
    }

    private static void handleRefillVerify(Minecraft client) {
        int currentCount = countInventoryByQuery(client, autoSellRefillItemQuery);
        int movedItems = Math.max(0, currentCount - refillInventoryCountBefore);
        if (movedItems <= 0) {
            if (targetSellCount == REFILL_ALL_TARGET && targetSellSoldCount > 0) {
                finishSellCycle(client, buildTargetSuccessSummary(targetSellSoldCount, soldTodayCount), true);
                return;
            }
            handleSellFailure(client, "作物仓库没有实际取出 " + autoSellRefillItemQuery
                    + "。点击槽位 " + refillMovedStackClicks + " 次，背包数量未增加。", true);
            return;
        }

        sendMessage(client, "已从作物仓库实际取出 " + movedItems + " 个 " + autoSellRefillItemQuery
                + "（点击 " + refillMovedStackClicks + " 组），开始自动售卖。");
        prepareSellAttempt();
        startSellFlow(client);
    }

    private static void handleMenuReturnState(Minecraft client, Screen screen) {
        if (retryAfterClose) {
            if (screen == null) {
                retryAfterClose = false;
                startSellFlow(client);
            }
            return;
        }

        if (isSystemShop(screen) || isSellMenu(screen)) {
            autoSellState = AutoSellState.WAITING_FOR_SELL_MENU;
            autoSellDelayTicks = screenActionDelayTicks(client);
            autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
            return;
        }

        if (screen != null) {
            return;
        }

        if (pendingSellCount > 0) {
            int soldThisTime = pendingSellCount;
            int soldToday = recordSuccessfulSale(client, soldThisTime);
            if (targetSellCount == REFILL_ALL_TARGET) {
                targetSellSoldCount += soldThisTime;
                sendMessage(client, "all 模式已售卖 " + targetSellSoldCount + " 个，继续从作物仓库取下一页。");
                prepareSellAttempt();
                startRefillBeforeSellFlow(client);
                return;
            }
            if (targetSellCount > 0) {
                targetSellSoldCount += soldThisTime;
                if (targetSellSoldCount < targetSellCount) {
                    sendMessage(client, "目标售卖进度 " + targetSellSoldCount + "/" + targetSellCount + "，继续补仓售卖。");
                    prepareSellAttempt();
                    startRefillBeforeSellFlow(client);
                    return;
                }
                finishSellCycle(client, buildTargetSuccessSummary(targetSellSoldCount, soldToday), true);
                return;
            }
            finishSellCycle(client, buildSuccessSummary(soldThisTime, soldToday), true);
            return;
        }

        finishSellCycle(client, "主人，这一趟我已经替你收拾好啦。", shouldAnnounceFailures());
    }

    private static void beginChestRun(Minecraft client, ChestTarget target) {
        activeChestTarget = target;
        activeChestRetries = 0;
        activeChestStandIndex = 0;
        activeChestGroupKey = resolveChestGroupKey(client.level, target.pos());
        autoSellState = AutoSellState.NAVIGATING_TO_CHEST;
        autoSellDelayTicks = 0;
        autoSellTimeoutTicks = NAVIGATION_TIMEOUT_TICKS;
        cooldownChestGroup(client, target.pos(), CHEST_OPERATION_COOLDOWN_TICKS);

        if (!navigateToActiveChestStand(client)) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
        }
    }

    private static void handleChestNavigationState(Minecraft client, Screen screen) {
        if (activeChestTarget == null) {
            resetToIdle(client, true);
            return;
        }

        if (screen instanceof AbstractContainerScreen<?> containerScreen && isGenericLootContainer(screen)) {
            handleChestContainerState(client, containerScreen);
            return;
        }

        BlockPos standPos = getActiveChestStandPos();
        if (standPos == null) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            return;
        }

        if (isNearStandPosition(client.player, standPos)) {
            if (activeChestRetries >= MAX_CHEST_RETRIES) {
                skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
                return;
            }

            stopLocalNavigation(client);
            attemptOpenChest(client, activeChestTarget);
            autoSellState = AutoSellState.WAITING_FOR_CHEST_SCREEN;
            autoSellDelayTicks = screenActionDelayTicks(client);
            autoSellTimeoutTicks = CHEST_OPEN_TIMEOUT_TICKS;
            activeChestRetries++;
            return;
        }

        if (followActivePath(client)) {
            autoSellDelayTicks = 5;
            return;
        }

        if (activeChestRetries >= MAX_CHEST_RETRIES) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            return;
        }

        if (!advanceToNextChestStand(client)) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            return;
        }

        autoSellDelayTicks = 10;
    }

    private static void handleChestOpenState(Minecraft client, Screen screen) {
        if (activeChestTarget == null) {
            resetToIdle(client, true);
            return;
        }

        if (screen instanceof AbstractContainerScreen<?> containerScreen && isGenericLootContainer(screen)) {
            handleChestContainerState(client, containerScreen);
            return;
        }

        BlockPos standPos = getActiveChestStandPos();
        if (standPos == null) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            return;
        }

        if (!isNearStandPosition(client.player, standPos)) {
            autoSellState = AutoSellState.NAVIGATING_TO_CHEST;
            autoSellDelayTicks = 0;
            autoSellTimeoutTicks = NAVIGATION_TIMEOUT_TICKS;
            return;
        }

        if (activeChestRetries >= MAX_CHEST_RETRIES) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            return;
        }

        if (!retryChestOpenWithNavigation(client)) {
            skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
        }
    }

    private static void handleChestContainerState(Minecraft client, AbstractContainerScreen<?> containerScreen) {
        cooldownChest(activeChestTarget.pos(), CHEST_OPERATION_COOLDOWN_TICKS);
        int movedCount = transferSellablesFromChest(client, containerScreen);
        if (client.player != null) {
            client.player.closeContainer();
        }

        if (movedCount > 0) {
            cooldownChest(activeChestTarget.pos(), CHEST_OPERATION_COOLDOWN_TICKS);
            activeChestTarget = null;
            resetToIdle(client, true);
            return;
        }

        skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
    }

    private static void handleStateTimeout(Minecraft client) {
        switch (autoSellState) {
            case NAVIGATING_TO_CHEST, WAITING_FOR_CHEST_SCREEN -> {
                closeActiveContainerScreen(client);
                skipCurrentChest(client, CHEST_OPERATION_COOLDOWN_TICKS, true);
            }
            case WAITING_FOR_REFILL_CD_MENU, WAITING_FOR_REFILL_CROP_STORAGE, WAITING_FOR_REFILL_ITEM_STORAGE, WAITING_FOR_REFILL_VERIFY -> {
                closeActiveContainerScreen(client);
                if (!retryRefillFlow(client, "补仓页面不可点击或等待超时")) {
                    handleSellFailure(client, "补仓等待超时，没有成功从作物仓库取出 " + autoSellRefillItemQuery + "。", true);
                }
            }
            case WAITING_FOR_SELL_MENU, WAITING_FOR_CONFIRM_DIALOG, WAITING_FOR_MENU_RETURN ->
                    handleSellFailure(client, "主人，我这次等得太久了，只好先收手啦。", shouldAnnounceFailures());
            case IDLE -> {
            }
        }
    }

    private static void closeActiveContainerScreen(Minecraft client) {
        if (client == null) {
            return;
        }

        if (client.player != null && client.screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?>) {
            client.setScreen(null);
        }
    }

    private static SlotClickResult clickSellConfirmSlot(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (!isClientReady(client)) {
            return new SlotClickResult(false, false, "主人，现在还不能继续自动收售呢。");
        }

        List<Slot> slots = screen.getMenu().slots;
        if (slots.isEmpty()) {
            return new SlotClickResult(false, false, "主人，这个收购界面今天有点奇怪，我没找到能点的格子。");
        }

        int playerInventoryStart = Math.max(0, slots.size() - 36);
        int confirmSlotIndex = -1;
        String confirmLabel = null;

        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            String label = stack.getHoverName().getString();
            if (!containsNormalized(label, SELL_CONFIRM_TITLE)) {
                continue;
            }

            confirmSlotIndex = slotIndex;
            confirmLabel = label;
            break;
        }

        if (confirmSlotIndex < 0) {
            return new SlotClickResult(false, false, "主人，我在收购商品页里没有找到确认交易那个玻璃板呢。");
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                confirmSlotIndex,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        return new SlotClickResult(true, false, "主人，我已经替你点下 " + confirmLabel + " 啦。");
    }

    private static SlotClickResult clickMatchingSystemShopSlot(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (!isClientReady(client)) {
            return new SlotClickResult(false, false, "主人，现在还不能继续自动收售呢。");
        }

        Map<String, InventoryNameCount> inventoryNameCounts = collectInventoryNameCounts(client);
        if (inventoryNameCounts.isEmpty()) {
            return new SlotClickResult(false, false, "主人，背包里暂时没有我能帮你卖的东西呀。");
        }

        List<Slot> slots = screen.getMenu().slots;
        if (slots.isEmpty()) {
            return new SlotClickResult(false, false, "主人，这个系统商店界面今天有点奇怪，我没找到能点的格子。");
        }

        int playerInventoryStart = Math.max(0, slots.size() - 36);
        int bestSlotIndex = -1;
        int bestCount = -1;
        String bestShopLabel = null;
        String bestInventoryLabel = null;

        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            String shopLabel = stack.getHoverName().getString();
            if (shopLabel == null || shopLabel.isEmpty()) {
                continue;
            }

            String normalizedShopLabel = normalizeItemName(shopLabel);
            for (InventoryNameCount inventoryItem : inventoryNameCounts.values()) {
                if (!namesRoughlyMatch(normalizedShopLabel, inventoryItem.normalizedName())) {
                    continue;
                }

                if (inventoryItem.count() > bestCount) {
                    bestSlotIndex = slotIndex;
                    bestCount = inventoryItem.count();
                    bestShopLabel = shopLabel;
                    bestInventoryLabel = inventoryItem.displayName();
                }
            }
        }

        if (bestSlotIndex < 0) {
            return new SlotClickResult(false, true, "主人，我在系统商店里没有找到和背包名字相近的物品呢。");
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                bestSlotIndex,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        pendingSellCount = bestCount;
        return new SlotClickResult(true, false, "主人，我已经替你选中 " + bestInventoryLabel + " 了，对应商店物品是 " + bestShopLabel + "。");
    }

    private static Optional<ChestTarget> findNearestChestTarget(Minecraft client) {
        if (client.level == null || client.player == null) {
            return Optional.empty();
        }

        ClientLevel level = client.level;
        BlockPos playerPos = client.player.blockPosition();
        List<ChestTarget> candidates = new ArrayList<>();
        Set<Long> visitedChestGroups = new HashSet<>();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;
        for (int chunkX = playerChunkX - MAX_SCAN_CHUNK_RADIUS; chunkX <= playerChunkX + MAX_SCAN_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = playerChunkZ - MAX_SCAN_CHUNK_RADIUS; chunkZ <= playerChunkZ + MAX_SCAN_CHUNK_RADIUS; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ChestBlockEntity)) {
                        continue;
                    }

                    BlockPos chestPos = blockEntity.getBlockPos();
                    if (playerPos.distSqr(chestPos) > MAX_SCAN_RANGE_SQUARED) {
                        continue;
                    }

                    if (!isInsideBoundary(chestPos)) {
                        continue;
                    }

                    if (chestPos.getY() - playerPos.getY() > MAX_CHEST_HEIGHT_ADVANTAGE) {
                        continue;
                    }

                    long chestGroupKey = resolveChestGroupKey(level, chestPos);
                    if (!visitedChestGroups.add(chestGroupKey)) {
                        continue;
                    }

                    if (chestGroupKey == activeChestGroupKey || isChestCoolingDown(level, chestPos, chestGroupKey)) {
                        continue;
                    }

                    List<BlockPos> standPositions = resolveStandPositions(level, chestPos, playerPos);
                    if (standPositions.isEmpty()) {
                        cooldownChest(chestPos, CHEST_OPERATION_COOLDOWN_TICKS);
                        continue;
                    }

                    BlockPos primaryStandPos = standPositions.getFirst();
                    double score = client.player.position().distanceToSqr(primaryStandPos.getCenter()) + Math.abs(primaryStandPos.getY() - playerPos.getY()) * 3.0;
                    candidates.add(new ChestTarget(chestPos.immutable(), List.copyOf(standPositions), score));
                }
            }
        }

        return candidates.stream().min(Comparator.comparingDouble(ChestTarget::score));
    }

    private static List<BlockPos> resolveStandPositions(ClientLevel level, BlockPos chestPos, BlockPos playerPos) {
        List<StandCandidate> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos base = chestPos.offset(dx, 0, dz);
                for (int deltaY = -1; deltaY <= 1; deltaY++) {
                    BlockPos standPos = base.offset(0, deltaY, 0);
                    if (!isDryStandPosition(level, chestPos, standPos)) {
                        continue;
                    }

                    if (standPos.getCenter().distanceToSqr(chestPos.getCenter()) > CHEST_INTERACT_DISTANCE_SQUARED) {
                        continue;
                    }

                    if (!seen.add(standPos.asLong())) {
                        continue;
                    }

                    double score = playerPos.distSqr(standPos) + Math.abs(playerPos.getY() - standPos.getY()) * 4.0;
                    candidates.add(new StandCandidate(standPos.immutable(), score));
                    break;
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(StandCandidate::score));
        List<BlockPos> orderedPositions = new ArrayList<>(candidates.size());
        for (StandCandidate candidate : candidates) {
            orderedPositions.add(candidate.pos());
        }
        return orderedPositions;
    }

    private static boolean isDryStandPosition(ClientLevel level, BlockPos chestPos, BlockPos standPos) {
        if (!level.getFluidState(standPos).isEmpty()
                || !level.getFluidState(standPos.above()).isEmpty()
                || !level.getFluidState(standPos.below()).isEmpty()) {
            return false;
        }

        BlockState feet = level.getBlockState(standPos);
        BlockState head = level.getBlockState(standPos.above());
        BlockState below = level.getBlockState(standPos.below());

        if (feet.blocksMotion() || head.blocksMotion()) {
            return false;
        }

        if (!below.blocksMotion()) {
            return false;
        }

        if (wouldFallIntoWater(level, standPos)) {
            return false;
        }

        return standPos.getCenter().distanceToSqr(chestPos.getCenter()) <= CHEST_INTERACT_DISTANCE_SQUARED;
    }

    private static boolean isInsideBoundary(BlockPos pos) {
        if (boundaryPoint1 == null || boundaryPoint2 == null) {
            return true;
        }

        int minX = Math.min(boundaryPoint1.getX(), boundaryPoint2.getX());
        int maxX = Math.max(boundaryPoint1.getX(), boundaryPoint2.getX());
        int minZ = Math.min(boundaryPoint1.getZ(), boundaryPoint2.getZ());
        int maxZ = Math.max(boundaryPoint1.getZ(), boundaryPoint2.getZ());

        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private static boolean startLocalNavigation(Minecraft client, BlockPos targetPos) {
        if (!isClientReady(client) || client.level == null || client.player == null) {
            return false;
        }

        List<BlockPos> path = buildLocalPath(client.level, client.player.blockPosition(), targetPos);
        if (path.isEmpty()) {
            return false;
        }

        activePath = path;
        activePathIndex = 0;
        return true;
    }

    private static boolean followActivePath(Minecraft client) {
        if (!isClientReady(client) || client.player == null || activePath.isEmpty()) {
            stopLocalNavigation(client);
            return false;
        }

        while (activePathIndex < activePath.size()) {
            BlockPos waypoint = activePath.get(activePathIndex);
            if (client.player.position().distanceToSqr(waypoint.getCenter()) <= LOCAL_PATH_REACH_DISTANCE_SQUARED) {
                activePathIndex++;
                continue;
            }

            steerTowards(client, waypoint);
            return true;
        }

        stopLocalNavigation(client);
        return false;
    }

    private static void stopLocalNavigation(Minecraft client) {
        activePath = List.of();
        activePathIndex = 0;
        releaseMovementKeys(client);
    }

    private static void steerTowards(Minecraft client, BlockPos waypoint) {
        if (!isClientReady(client) || client.player == null) {
            return;
        }

        Vec3 center = waypoint.getCenter();
        GroundMovementController.walkToward(client, center, 0.15D, 1.6D,
                waypoint.getY() > client.player.blockPosition().getY(), false, 360.0F);
    }

    private static void releaseMovementKeys(Minecraft client) {
        GroundMovementController.release(client);
    }

    private static List<BlockPos> buildLocalPath(ClientLevel level, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) {
            return List.of(goal);
        }

        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(PathNode::fScore));
        Map<Long, PathNode> bestNodes = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        PathNode startNode = new PathNode(start.immutable(), null, 0.0D, heuristic(start, goal));
        openSet.add(startNode);
        bestNodes.put(start.asLong(), startNode);

        int expanded = 0;
        while (!openSet.isEmpty() && expanded < LOCAL_PATH_NODE_LIMIT) {
            PathNode current = openSet.poll();
            if (!closed.add(current.pos().asLong())) {
                continue;
            }

            if (current.pos().equals(goal) || current.pos().distSqr(goal) <= 1.0D) {
                return reconstructPath(current);
            }

            expanded++;
            for (BlockPos neighbor : collectNeighbors(level, current.pos(), start)) {
                if (closed.contains(neighbor.asLong())) {
                    continue;
                }

                double newCost = current.gScore() + movementCost(level, current.pos(), neighbor);
                PathNode previous = bestNodes.get(neighbor.asLong());
                if (previous != null && newCost >= previous.gScore()) {
                    continue;
                }

                PathNode next = new PathNode(neighbor.immutable(), current, newCost, newCost + heuristic(neighbor, goal));
                bestNodes.put(neighbor.asLong(), next);
                openSet.add(next);
            }
        }

        return Collections.emptyList();
    }

    private static List<BlockPos> collectNeighbors(ClientLevel level, BlockPos current, BlockPos start) {
        List<BlockPos> neighbors = new ArrayList<>(8);
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] offset : offsets) {
            int dx = offset[0];
            int dz = offset[1];
            for (int deltaY = -1; deltaY <= 1; deltaY++) {
                BlockPos candidate = current.offset(dx, deltaY, dz);
                if (candidate.distSqr(start) > LOCAL_PATH_SEARCH_RADIUS * LOCAL_PATH_SEARCH_RADIUS) {
                    continue;
                }

                if (!isTraversable(level, current, candidate)) {
                    continue;
                }

                if (dx != 0 && dz != 0) {
                    BlockPos sideA = current.offset(dx, deltaY, 0);
                    BlockPos sideB = current.offset(0, deltaY, dz);
                    if (!isDryFooting(level, sideA) && !isDryFooting(level, sideB)) {
                        continue;
                    }
                }

                neighbors.add(candidate);
                break;
            }
        }
        return neighbors;
    }

    private static boolean isTraversable(ClientLevel level, BlockPos from, BlockPos to) {
        if (!isDryFooting(level, to)) {
            return false;
        }

        if (Math.abs(to.getY() - from.getY()) > 1) {
            return false;
        }

        if (isUnsafeDrop(level, to) || wouldFallIntoWater(level, to)) {
            return false;
        }

        return true;
    }

    private static boolean isDryFooting(ClientLevel level, BlockPos pos) {
        if (!level.getFluidState(pos).isEmpty()
                || !level.getFluidState(pos.above()).isEmpty()
                || !level.getFluidState(pos.below()).isEmpty()) {
            return false;
        }

        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());
        return !feet.blocksMotion() && !head.blocksMotion() && below.blocksMotion();
    }

    private static boolean wouldFallIntoWater(ClientLevel level, BlockPos pos) {
        BlockPos cursor = pos;
        for (int depth = 0; depth < 3; depth++) {
            BlockPos below = cursor.below();
            if (!level.getBlockState(below).blocksMotion()) {
                if (!level.getFluidState(below).isEmpty()) {
                    return true;
                }
                cursor = below;
                continue;
            }
            return false;
        }

        return !level.getFluidState(cursor.below()).isEmpty();
    }

    private static boolean isUnsafeDrop(ClientLevel level, BlockPos pos) {
        BlockPos cursor = pos;
        int fallDistance = 0;
        while (fallDistance <= MAX_SAFE_DROP) {
            BlockPos below = cursor.below();
            if (level.getBlockState(below).blocksMotion()) {
                return false;
            }

            if (!level.getFluidState(below).isEmpty()) {
                return true;
            }

            cursor = below;
            fallDistance++;
        }
        return true;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return from.distManhattan(to);
    }

    private static double movementCost(ClientLevel level, BlockPos from, BlockPos to) {
        double cost = (from.getX() != to.getX() && from.getZ() != to.getZ()) ? 1.45D : 1.0D;
        if (to.getY() > from.getY()) {
            cost += 0.75D;
        } else if (to.getY() < from.getY()) {
            cost += 0.25D;
        }
        cost += edgeRiskPenalty(level, to);
        return cost;
    }

    private static double edgeRiskPenalty(ClientLevel level, BlockPos pos) {
        double penalty = 0.0D;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (!level.getBlockState(side).blocksMotion() && !level.getBlockState(side.below()).blocksMotion()) {
                penalty += 0.8D;
            }
            if (!level.getFluidState(side).isEmpty() || !level.getFluidState(side.below()).isEmpty()) {
                penalty += 1.5D;
            }
        }
        return penalty;
    }

    private static List<BlockPos> reconstructPath(PathNode target) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = target;
        while (current != null) {
            path.add(current.pos());
            current = current.parent();
        }
        Collections.reverse(path);
        return path;
    }

    private static void attemptOpenChest(Minecraft client, ChestTarget target) {
        if (!isClientReady(client) || target == null) {
            return;
        }

        faceBlockCenter(client, target.pos());
        Vec3 chestCenter = target.pos().getCenter();
        Vec3 playerPos = client.player.position();
        Direction side = Direction.getApproximateNearest(
                playerPos.x - chestCenter.x,
                0.0,
                playerPos.z - chestCenter.z
        );
        if (side.getAxis().isVertical()) {
            side = Direction.NORTH;
        }

        BlockHitResult hitResult = new BlockHitResult(chestCenter, side, target.pos(), false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void faceBlockCenter(Minecraft client, BlockPos targetPos) {
        if (!isClientReady(client) || targetPos == null) {
            return;
        }

        LocalPlayer player = client.player;
        Vec3 eyePos = player.getEyePosition();
        Vec3 center = targetPos.getCenter();
        double dx = center.x - eyePos.x;
        double dy = center.y - eyePos.y;
        double dz = center.z - eyePos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDistance, 1.0E-4D)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
    }

    private static int transferSellablesFromChest(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!isClientReady(client)) {
            return 0;
        }

        int movedCount = 0;
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isSellableStack(stack)) {
                continue;
            }

            if (!canMoveIntoInventory(client, stack)) {
                continue;
            }

            movedCount += stack.getCount();
            client.gameMode.handleContainerInput(
                    screen.getMenu().containerId,
                    slotIndex,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
        }

        return movedCount;
    }

    private static boolean canMoveIntoInventory(Minecraft client, ItemStack stack) {
        return client.player.getInventory().getFreeSlot() >= 0
                || client.player.getInventory().getSlotWithRemainingSpace(stack) >= 0;
    }

    private static boolean isNearStandPosition(LocalPlayer player, BlockPos standPos) {
        return player.position().distanceToSqr(standPos.getCenter()) <= CHEST_INTERACT_DISTANCE_SQUARED;
    }

    private static BlockPos getActiveChestStandPos() {
        if (activeChestTarget == null
                || activeChestStandIndex < 0
                || activeChestStandIndex >= activeChestTarget.standPositions().size()) {
            return null;
        }
        return activeChestTarget.standPositions().get(activeChestStandIndex);
    }

    private static boolean navigateToActiveChestStand(Minecraft client) {
        BlockPos standPos = getActiveChestStandPos();
        return standPos != null && startLocalNavigation(client, standPos);
    }

    private static boolean retryChestOpenWithNavigation(Minecraft client) {
        if (activeChestTarget == null || activeChestTarget.standPositions().isEmpty()) {
            return false;
        }

        stopLocalNavigation(client);

        int standCount = activeChestTarget.standPositions().size();
        for (int offset = 1; offset <= standCount; offset++) {
            int nextIndex = (activeChestStandIndex + offset) % standCount;
            activeChestStandIndex = nextIndex;
            if (navigateToActiveChestStand(client)) {
                autoSellState = AutoSellState.NAVIGATING_TO_CHEST;
                autoSellDelayTicks = 0;
                autoSellTimeoutTicks = NAVIGATION_TIMEOUT_TICKS;
                return true;
            }
        }

        return false;
    }

    private static boolean advanceToNextChestStand(Minecraft client) {
        if (activeChestTarget == null) {
            return false;
        }

        int standCount = activeChestTarget.standPositions().size();
        for (int offset = 1; offset <= standCount; offset++) {
            int nextIndex = activeChestStandIndex + offset;
            if (nextIndex >= standCount) {
                break;
            }

            activeChestStandIndex = nextIndex;
            if (navigateToActiveChestStand(client)) {
                autoSellState = AutoSellState.NAVIGATING_TO_CHEST;
                autoSellDelayTicks = 10;
                autoSellTimeoutTicks = NAVIGATION_TIMEOUT_TICKS;
                return true;
            }
        }

        return false;
    }

    private static boolean hasEnoughSellableItems(Minecraft client) {
        Map<String, Integer> counts = collectInventoryCounts(client);
        int totalCount = 0;
        for (Integer count : counts.values()) {
            if (count == null || count <= 0) {
                continue;
            }
            totalCount += count;
            if (totalCount >= YOLO_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnySellableItems(Minecraft client) {
        Map<String, Integer> counts = collectInventoryCounts(client);
        for (Integer count : counts.values()) {
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInventoryFreeSlot(Minecraft client) {
        if (!isClientReady(client) || client.player == null) {
            return false;
        }

        return client.player.getInventory().getFreeSlot() >= 0;
    }

    private static int countInventoryByQuery(Minecraft client, String query) {
        if (!isClientReady(client) || client.player == null) {
            return 0;
        }

        String normalizedQuery = normalizeItemName(query);
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            if (normalizeItemName(itemId).contains(normalizedQuery) || normalizeItemName(name).contains(normalizedQuery)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countInventoryCapacityForQuery(Minecraft client, String query) {
        if (!isClientReady(client) || client.player == null) {
            return 0;
        }

        String normalizedQuery = normalizeItemName(query);
        int capacity = 0;
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                capacity += 64;
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            if ((normalizeItemName(itemId).contains(normalizedQuery) || normalizeItemName(name).contains(normalizedQuery))
                    && stack.getCount() < stack.getMaxStackSize()) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    private static int quickMoveRefillStacks(Minecraft client, AbstractContainerScreen<?> screen, List<Integer> itemSlots, int neededItems) {
        int moved = 0;
        int remainingItems = Math.max(0, neededItems);
        for (int slotIndex : itemSlots) {
            if (remainingItems <= 0 || !hasInventoryCapacityForRefillQuery(client, autoSellRefillItemQuery)) {
                break;
            }
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !canMoveIntoInventory(client, slot.getItem())) {
                continue;
            }
            int stackCount = slot.getItem().getCount();
            client.gameMode.handleContainerInput(
                    screen.getMenu().containerId,
                    slotIndex,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
            moved++;
            remainingItems -= stackCount;
        }
        return moved;
    }

    private static boolean shouldUseQuickTakeCurrentPage(int neededItems) {
        return targetSellCount == REFILL_ALL_TARGET || neededItems >= countInventoryCapacityForQuery(Minecraft.getInstance(), autoSellRefillItemQuery);
    }

    private static int findQuickTakeCurrentPageSlot(AbstractContainerScreen<?> screen) {
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (containsNormalized(stack.getHoverName().getString(), "取出该页")
                    || containsNormalized(stack.getHoverName().getString(), "拿出该页")
                    || tooltipContains(stack, "点击取出该页")
                    || tooltipContains(stack, "取出该页作物")
                    || tooltipContains(stack, "拿出该页作物")) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean tooltipContains(ItemStack stack, String expectedText) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return false;
        }
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.ADVANCED)) {
                if (containsNormalized(line.getString(), expectedText)) {
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            ScreenProbe.LOGGER.warn("Failed to read tooltip while finding refill quick take slot", exception);
        }
        return false;
    }

    private static void clickContainerSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex, int button, ContainerInput input) {
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotIndex,
                button,
                input,
                client.player
        );
    }

    private static boolean hasInventoryCapacityForRefillQuery(Minecraft client, String query) {
        return countInventoryCapacityForQuery(client, query) > 0;
    }

    private static int findSlotByRefillQuery(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String query) {
        List<Integer> slots = findSlotsByRefillQuery(screen, includePlayerInventory, query);
        return slots.isEmpty() ? -1 : slots.getFirst();
    }

    private static List<Integer> findSlotsByRefillQuery(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String query) {
        String normalizedQuery = normalizeItemName(query);
        List<Integer> matched = new ArrayList<>();
        if (normalizedQuery.isBlank()) {
            return matched;
        }

        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            if (normalizeItemName(itemId).contains(normalizedQuery) || normalizeItemName(name).contains(normalizedQuery)) {
                matched.add(slotIndex);
            }
        }
        return matched;
    }

    private static int findSlotByKeywords(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String... keywords) {
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            String name = slot.getItem().getHoverName().getString();
            for (String keyword : keywords) {
                if (containsNormalized(name, keyword)) {
                    return slotIndex;
                }
            }
        }
        return -1;
    }

    private static Map<String, Integer> collectInventoryCounts(Minecraft client) {
        Map<String, Integer> counts = new HashMap<>();
        int inventorySize = client.player.getInventory().getContainerSize();
        for (int slotIndex = 0; slotIndex < inventorySize; slotIndex++) {
            ItemStack stack = client.player.getInventory().getItem(slotIndex);
            if (!isSellableStack(stack)) {
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static Map<String, InventoryNameCount> collectInventoryNameCounts(Minecraft client) {
        Map<String, InventoryNameCount> counts = new HashMap<>();
        int inventorySize = client.player.getInventory().getContainerSize();
        for (int slotIndex = 0; slotIndex < inventorySize; slotIndex++) {
            ItemStack stack = client.player.getInventory().getItem(slotIndex);
            if (!isSellableStack(stack)) {
                continue;
            }

            String displayName = stack.getHoverName().getString();
            String normalizedName = normalizeItemName(displayName);
            if (normalizedName.isEmpty()) {
                continue;
            }

            InventoryNameCount previous = counts.get(normalizedName);
            if (previous == null) {
                counts.put(normalizedName, new InventoryNameCount(displayName, normalizedName, stack.getCount()));
                continue;
            }

            counts.put(normalizedName, new InventoryNameCount(
                    previous.displayName(),
                    previous.normalizedName(),
                    previous.count() + stack.getCount()
            ));
        }
        return counts;
    }

    private static boolean isSellableStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return SELLABLE_ITEM_IDS.contains(itemId);
    }

    private static boolean isSellMenu(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }

        String title = screen.getTitle().getString();
        return title != null && title.contains(SELL_MENU_TITLE);
    }

    private static boolean isSystemShop(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }

        String title = screen.getTitle().getString();
        return title != null && title.contains(SYSTEM_SHOP_TITLE);
    }

    private static boolean isGenericLootContainer(Screen screen) {
        return screen instanceof AbstractContainerScreen<?>
                && !isSystemShop(screen)
                && !isSellMenu(screen)
                && !ScreenProbeInspector.isSellQuantityDialog(screen);
    }

    private static boolean isBlockedByForeignScreen(Screen screen) {
        if (screen == null || screen instanceof ChatScreen) {
            return false;
        }

        return !(isSystemShop(screen) || isSellMenu(screen) || ScreenProbeInspector.isSellQuantityDialog(screen));
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null
                && client.player != null
                && client.getConnection() != null
                && client.gameMode != null;
    }

    private static void handleScreenTransition(Minecraft client, Screen previousScreen, Screen currentScreen) {
        if (autoSellState != AutoSellState.IDLE || !isClientReady(client)) {
            return;
        }

        if (suppressCloseTriggerOnce) {
            suppressCloseTriggerOnce = false;
            return;
        }

        if (autoSellMode != AutoSellMode.YOLO && autoSellMode != AutoSellMode.YOLO_MAX) {
            yoloCheckQueued = false;
            return;
        }

        if (currentScreen != null || !isCloseTriggerScreen(previousScreen)) {
            return;
        }

        yoloCheckQueued = true;
        nextMaxSearchTick = clientTickCounter;
    }

    private static boolean isCloseTriggerScreen(Screen screen) {
        if (screen == null || screen instanceof ChatScreen) {
            return false;
        }

        return screen instanceof AbstractContainerScreen<?> || ScreenProbeInspector.isSellQuantityDialog(screen);
    }

    private static boolean containsNormalized(String source, String target) {
        String normalizedSource = normalizeItemName(source);
        String normalizedTarget = normalizeItemName(target);
        if (normalizedSource.isEmpty() || normalizedTarget.isEmpty()) {
            return false;
        }
        return normalizedSource.contains(normalizedTarget);
    }

    private static boolean namesRoughlyMatch(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private static String normalizeItemName(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        return input
                .replaceAll("§.", "")
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private static void handleSellFailure(Minecraft client, String message, boolean announce) {
        if (autoSellMode == AutoSellMode.SINGLE_RUN || autoSellMode == AutoSellMode.OFF) {
            stopAutoSell(client, message);
            return;
        }

        resetToIdle(client, true);
        if (announce) {
            sendMessage(client, message);
        }
    }

    private static void finishSellCycle(Minecraft client, String message, boolean announce) {
        if (client.player != null && client.screen instanceof AbstractContainerScreen<?> && isSellMenu(client.screen)) {
            client.player.closeContainer();
        }

        if (autoSellMode == AutoSellMode.SINGLE_RUN || autoSellMode == AutoSellMode.OFF) {
            stopAutoSell(client, message);
            return;
        }

        resetToIdle(client, true);
        if (announce) {
            sendMessage(client, message);
        }
    }

    private static void finishSellCycle(Minecraft client, Component message, boolean announce) {
        if (client.player != null && client.screen instanceof AbstractContainerScreen<?> && isSellMenu(client.screen)) {
            client.player.closeContainer();
        }

        if (autoSellMode == AutoSellMode.SINGLE_RUN || autoSellMode == AutoSellMode.OFF) {
            stopAutoSell(client, message);
            return;
        }

        resetToIdle(client, true);
        if (announce) {
            sendMessage(client, message);
        }
    }

    private static void stopAutoSell(Minecraft client, String message) {
        resetAutomationState(client, true);
        sendMessage(client, message);
    }

    private static void stopAutoSell(Minecraft client, Component message) {
        resetAutomationState(client, true);
        sendMessage(client, message);
    }

    private static void prepareSellAttempt() {
        retryAttemptsRemaining = 1;
        retryAfterClose = false;
        pendingSellCount = 0;
    }

    private static boolean retryCurrentFlow(Minecraft client) {
        if (retryAttemptsRemaining <= 0 || client.player == null || !(client.screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }

        retryAttemptsRemaining--;
        retryAfterClose = true;
        pendingSellCount = 0;
        autoSellState = AutoSellState.WAITING_FOR_MENU_RETURN;
        autoSellDelayTicks = screenActionDelayTicks(client);
        autoSellTimeoutTicks = FLOW_TIMEOUT_TICKS;
        client.player.closeContainer();
        return true;
    }

    private static boolean retryRefillFlow(Minecraft client, String reason) {
        if (refillRetryAttemptsRemaining <= 0 || client == null || client.getConnection() == null) {
            return false;
        }

        refillRetryAttemptsRemaining--;
        if (client.player != null && client.screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        refillInventoryCountBefore = 0;
        refillMovedStackClicks = 0;
        autoSellState = AutoSellState.WAITING_FOR_REFILL_CD_MENU;
        autoSellDelayTicks = refillScreenDelayTicks(client);
        autoSellTimeoutTicks = REFILL_TIMEOUT_TICKS;
        String command = AutoNetherWartConfig.get(client).cropStorageCommand();
        client.getConnection().sendCommand(command);
        sendMessage(client, reason + "，正在重试打开 /" + command + "。剩余 " + refillRetryAttemptsRemaining + " 次。");
        return true;
    }

    private static int recordSuccessfulSale(Minecraft client, int soldThisTime) {
        ensureSaleStatsLoaded(client);
        LocalDate today = LocalDate.now();
        if (saleStatsDate == null || !saleStatsDate.equals(today)) {
            saleStatsDate = today;
            soldTodayCount = 0;
            saveSaleStats(client);
        }

        soldTodayCount += soldThisTime;
        saveSaleStats(client);
        return soldTodayCount;
    }

    private static void ensureSaleStatsLoaded(Minecraft client) {
        if (saleStatsLoaded) {
            return;
        }

        saleStatsLoaded = true;
        saleStatsDate = null;
        soldTodayCount = 0;

        Path statsFile = getSaleStatsFile(client);
        if (!Files.exists(statsFile)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(statsFile);
            LocalDate loadedDate = null;
            int loadedCount = 0;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator <= 0 || separator >= line.length() - 1) {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("date".equals(key)) {
                    loadedDate = LocalDate.parse(value);
                } else if ("count".equals(key)) {
                    loadedCount = Math.max(0, Integer.parseInt(value));
                }
            }

            saleStatsDate = loadedDate;
            soldTodayCount = loadedCount;
        } catch (Exception exception) {
            ScreenProbe.LOGGER.warn("Failed to load autosell stats from {}", statsFile.toAbsolutePath(), exception);
            saleStatsDate = null;
            soldTodayCount = 0;
        }
    }

    private static void saveSaleStats(Minecraft client) {
        if (client == null) {
            return;
        }

        Path statsFile = getSaleStatsFile(client);
        try {
            Files.createDirectories(statsFile.getParent());
            List<String> lines = List.of(
                    "date=" + (saleStatsDate == null ? "" : saleStatsDate),
                    "count=" + Math.max(0, soldTodayCount)
            );
            Files.write(statsFile, lines);
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("Failed to save autosell stats to {}", statsFile.toAbsolutePath(), exception);
        }
    }

    private static Path getSaleStatsFile(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(STATS_FILE_NAME);
    }

    private static Component buildSuccessSummary(int soldThisTime, int soldToday) {
        return Component.empty()
                .append(Component.literal("主人，这一趟悄悄替你清了 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(soldThisTime)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" 个宝贝，今天已经卖掉 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(soldToday)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" 个啦。").withStyle(ChatFormatting.AQUA));
    }

    private static Component buildTargetSuccessSummary(int soldTargetCount, int soldToday) {
        return Component.empty()
                .append(Component.literal("目标售卖完成：本次已卖 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(soldTargetCount)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" 个，今天累计 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(soldToday)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" 个。").withStyle(ChatFormatting.AQUA));
    }

    private static void finishTargetedRefillSell(Minecraft client) {
        ensureSaleStatsLoaded(client);
        finishSellCycle(client, buildTargetSuccessSummary(targetSellSoldCount, soldTodayCount), true);
    }

    private static int resolveSellQuantityFromDialog(Screen screen, int fallback) {
        Integer quantity = ScreenProbeInspector.readSellQuantity(screen);
        if (quantity == null || quantity <= 0) {
            return fallback;
        }
        return quantity;
    }

    private static boolean shouldAnnounceFailures() {
        return autoSellMode == AutoSellMode.SINGLE_RUN || autoSellMode == AutoSellMode.OFF;
    }

    private static void skipCurrentChest(Minecraft client, int cooldownTicks, boolean queueNext) {
        if (activeChestTarget != null) {
            cooldownChestGroup(client, activeChestTarget.pos(), cooldownTicks);
        }

        if (client.player != null && client.screen instanceof AbstractContainerScreen<?> && isGenericLootContainer(client.screen)) {
            client.player.closeContainer();
        }

        resetToIdle(client, queueNext);
    }

    private static void resetToIdle(Minecraft client, boolean queueNext) {
        autoSellState = AutoSellState.IDLE;
        autoSellDelayTicks = 0;
        autoSellTimeoutTicks = 0;
        pendingSellCount = 0;
        retryAfterClose = false;
        activeChestTarget = null;
        activeChestRetries = 0;
        activeChestStandIndex = 0;
        activeChestGroupKey = Long.MIN_VALUE;
        stopLocalNavigation(client);
        if (queueNext && autoSellMode == AutoSellMode.YOLO_MAX) {
            yoloCheckQueued = true;
            nextMaxSearchTick = clientTickCounter;
        }
    }

    private static void resetAutomationState(Minecraft client, boolean stopMode) {
        stopLocalNavigation(client);
        autoSellState = AutoSellState.IDLE;
        autoSellDelayTicks = 0;
        autoSellTimeoutTicks = 0;
        pendingSellCount = 0;
        targetSellCount = 0;
        targetSellSoldCount = 0;
        refillInventoryCountBefore = 0;
        refillMovedStackClicks = 0;
        yoloCheckQueued = false;
        retryAttemptsRemaining = 0;
        retryAfterClose = false;
        activeChestTarget = null;
        activeChestRetries = 0;
        activeChestStandIndex = 0;
        activeChestGroupKey = Long.MIN_VALUE;
        nextMaxSearchTick = 0L;
        if (stopMode) {
            autoSellMode = AutoSellMode.OFF;
        }
    }

    private static void hardResetAutomation(Minecraft client) {
        if (client.player != null && client.screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        if (client.screen instanceof AbstractContainerScreen<?>) {
            client.setScreen(null);
        }

        suppressCloseTriggerOnce = false;
        emergencyStopLatch = false;
        AutoDragonController.handleSessionReset(client);
        AutoChunkMinerController.handleSessionReset(client);
        AutoStrengthenController.handleSessionReset(client);
        AutoNetherWartController.handleSessionReset(client);
        AutoBoatDispenserController.handleSessionReset(client);
        AutoVillagerTradeController.handleSessionReset(client);
        resetAutomationState(client, true);
        chestCooldownUntilTick.clear();
        chestGroupCooldownUntilTick.clear();
        releaseMovementKeys(client);
    }

    private static void sendFeatureList(Minecraft client) {
        sendMessage(client, ScreenProbeGlobalConfig.BUILD_NOTICE
                + "\n后续只维护主版本 26.1.2；1.21.11 将不再更新。"
                + "\n输入 /autohelp 查看功能列表；输入 /autosettings 打开配置。");
    }

    private static void sendAutoHelp(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("功能帮助：").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(helpButton("设置", "/autosettings", "打开配置界面"))
                .append(helpButton("售卖", "/autosell status", "/autosell refill all 全部补仓售卖；/autosell refill <数量> 卖够指定数量；/autosell item <物品> 设置目标；/autosell stop 停止"))
                .append(helpButton("下界疣", "/autowart status", "/autowart start|stop；spectator on|off；radius/speed 调整"))
                .append(helpButton("强化", "/autostrength status", "/autostrength <次数>；/autostrength craft <剑|镐子|头盔|胸甲|靴子>；按物理/真实/近战伤害和物理/近战防御评判"))
                .append(helpButton("补船", "/autoboat1 status", "/autoboat1 start|stop 自动往发射器放船"))
                .append(helpButton("Node", "/nodebot status", "/nodebot start|stop|restart|send <消息>"));
        sendMessage(client, message);
        sendMessage(client, "常用：/autosell refill 或 /autosell refill all = 全部补仓售卖；/autosell refill 1000 = 卖够 1000 个；/autosell item 下界疣 = 设置补仓物品。");
        sendMessage(client, "维护说明：后续只维护主版本 26.1.2；1.21.11 将不再更新。");
    }

    private static Component helpButton(String label, String command, String hover) {
        Style style = Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover + "\n点击执行：" + command)));
        return Component.literal(" [" + label + "]").withStyle(style);
    }

    private static void cooldownChest(BlockPos pos, int cooldownTicks) {
        cooldownChestGroup(Minecraft.getInstance(), pos, cooldownTicks);
    }

    private static void cooldownChestGroup(Minecraft client, BlockPos pos, int cooldownTicks) {
        if (pos == null) {
            return;
        }

        long untilTick = clientTickCounter + cooldownTicks;
        chestCooldownUntilTick.merge(pos.asLong(), untilTick, Math::max);

        if (client == null || client.level == null) {
            return;
        }

        long chestGroupKey = resolveChestGroupKey(client.level, pos);
        chestGroupCooldownUntilTick.merge(chestGroupKey, untilTick, Math::max);
        BlockState chestState = client.level.getBlockState(pos);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (client.level.getBlockState(neighbor).getBlock() != chestState.getBlock()) {
                continue;
            }
            chestCooldownUntilTick.merge(neighbor.asLong(), untilTick, Math::max);
        }
    }

    private static boolean isChestCoolingDown(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.level != null
                ? isChestCoolingDown(client.level, pos, resolveChestGroupKey(client.level, pos))
                : isChestCoolingDownAtKey(pos.asLong());
    }

    private static boolean isChestCoolingDown(ClientLevel level, BlockPos pos, long chestGroupKey) {
        Long groupUntilTick = chestGroupCooldownUntilTick.get(chestGroupKey);
        if (groupUntilTick != null && groupUntilTick > clientTickCounter) {
            return true;
        }

        if (isChestCoolingDownAtKey(pos.asLong())) {
            return true;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (level.getBlockState(neighbor).getBlock() != level.getBlockState(pos).getBlock()) {
                continue;
            }
            if (isChestCoolingDownAtKey(neighbor.asLong())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChestCoolingDownAtKey(long posKey) {
        Long untilTick = chestCooldownUntilTick.get(posKey);
        return untilTick != null && untilTick > clientTickCounter;
    }

    private static long resolveChestGroupKey(ClientLevel level, BlockPos pos) {
        long groupKey = pos.asLong();
        BlockState baseState = level.getBlockState(pos);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (level.getBlockState(neighbor).getBlock() != baseState.getBlock()) {
                continue;
            }
            groupKey = Math.min(groupKey, neighbor.asLong());
        }
        return groupKey;
    }

    private static boolean isChestCoolingDownLegacy(BlockPos pos) {
        Long untilTick = chestCooldownUntilTick.get(pos.asLong());
        return untilTick != null && untilTick > clientTickCounter;
    }

    private static void pruneChestCooldowns() {
        chestCooldownUntilTick.entrySet().removeIf(entry -> entry.getValue() <= clientTickCounter);
        chestGroupCooldownUntilTick.entrySet().removeIf(entry -> entry.getValue() <= clientTickCounter);
    }

    private static void inspectCurrentScreen(Minecraft client, Screen screen) {
        List<String> lines = ScreenProbeInspector.inspect(screen);
        Path logPath = writeInspectionLog(client, screen, lines);

        if (logPath != null) {
            ScreenProbe.LOGGER.info("Full log saved to {}", logPath.toAbsolutePath());
            return;
        }

        ScreenProbe.LOGGER.warn("Failed to save inspection log for {}", screen.getClass().getName());
    }

    private static void sendMessage(Minecraft client, String message) {
        sendMessage(client, Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    private static int screenActionDelayTicks(Minecraft client) {
        return ScreenProbeGlobalConfig.menuActionDelayTicks(client);
    }

    private static int refillScreenDelayTicks(Minecraft client) {
        return ScreenProbeGlobalConfig.menuActionDelayTicks(client);
    }

    private static void sendStatus(Minecraft client) {
        if (client == null) {
            return;
        }

        String targetPos = activeChestTarget == null ? "-" : formatBlockPos(activeChestTarget.pos());
        String standPos = getActiveChestStandPos() == null ? "-" : formatBlockPos(getActiveChestStandPos());
        String boundary1 = boundaryPoint1 == null ? "-" : formatBlockPos(boundaryPoint1);
        String boundary2 = boundaryPoint2 == null ? "-" : formatBlockPos(boundaryPoint2);

        Component message = Component.empty()
                .append(Component.literal("模式 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(autoSellMode.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" | 状态 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(autoSellState.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" | 队列 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(yoloCheckQueued)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 目标箱 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(targetPos).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 站位 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(standPos).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 冷却箱 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(chestCooldownUntilTick.size())).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" | 冷却组 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(chestGroupCooldownUntilTick.size())).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" | 边界1 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(boundary1).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 边界2 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(boundary2).withStyle(ChatFormatting.YELLOW));

        sendMessage(client, message);
    }

    private static void sendMessage(Minecraft client, Component message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message.getString());

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.empty()
                            .append(Component.literal(prefix).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                            .append(message)
            );
        }
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Path writeInspectionLog(Minecraft client, Screen screen, List<String> lines) {
        try {
            Path logDir = client.gameDirectory.toPath().resolve("logs").resolve("ranmc-toolbox");
            Files.createDirectories(logDir);

            String fileName = "ranmc-toolbox-" + FILE_TIME_FORMAT.format(LocalDateTime.now())
                    + "-" + screen.getClass().getSimpleName() + ".log";
            Path logFile = logDir.resolve(fileName);

            List<String> fileLines = new ArrayList<>();
            fileLines.add("screen=" + screen.getClass().getName());
            fileLines.add("savedAt=" + LocalDateTime.now());
            fileLines.add("");
            fileLines.addAll(lines);

            Files.write(logFile, fileLines);
            return logFile;
        } catch (IOException exception) {
            ScreenProbe.LOGGER.error("Failed to save inspection log", exception);
            return null;
        }
    }

    private enum AutoSellMode {
        OFF,
        SINGLE_RUN,
        YOLO,
        YOLO_MAX
    }

    private enum AutoSellState {
        IDLE,
        WAITING_FOR_SELL_MENU,
        WAITING_FOR_CONFIRM_DIALOG,
        WAITING_FOR_MENU_RETURN,
        WAITING_FOR_REFILL_CD_MENU,
        WAITING_FOR_REFILL_CROP_STORAGE,
        WAITING_FOR_REFILL_ITEM_STORAGE,
        WAITING_FOR_REFILL_VERIFY,
        NAVIGATING_TO_CHEST,
        WAITING_FOR_CHEST_SCREEN
    }

    private record SlotClickResult(boolean success, boolean retryable, String message) {
    }

    private record InventoryNameCount(String displayName, String normalizedName, int count) {
    }

    private record StandCandidate(BlockPos pos, double score) {
    }

    private record ChestTarget(BlockPos pos, List<BlockPos> standPositions, double score) {
    }

    private record PathNode(BlockPos pos, PathNode parent, double gScore, double fScore) {
    }
}
