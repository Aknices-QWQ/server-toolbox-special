package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutoStrengthenController {
    private static final String COMMAND = "autostrength";
    private static final String CHAT_MODULE = "自动强化";
    private static final String SCREEN_TITLE = "装备强化";
    private static final String CLEAR_SCREEN_TITLE = "清除属性";
    private static final String CONFIRM_LABEL = "确认强化";
    private static final List<String> CLEAR_MENU_FALLBACK_LABELS = List.of("属性清除", "清除属性");
    private static final List<String> CLEAR_CONFIRM_FALLBACK_LABELS = List.of("确认清除", "确认", "开始清除", "确认重置", "重置属性");
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("(?<![\\d.])([+-]?\\d+)(?![\\d.])");
    private static final int TIMEOUT_TICKS = 20 * 30;

    private static final List<String> DAMAGE_ATTRIBUTES = List.of(
            "物理伤害",
            "真实伤害",
            "近战伤害"
    );
    private static final List<String> DEFENSE_ATTRIBUTES = List.of(
            "物理防御",
            "近战防御"
    );
    private static final List<String> FOCUS_ATTRIBUTES = List.of(
            "暴击率",
            "暴击几率",
            "闪避",
            "闪避率",
            "吸血",
            "吸血率",
            "攻速",
            "几率",
            "概率",
            "率"
    );

    private static boolean active;
    private static State state = State.IDLE;
    private static int maxRolls = AutoStrengthenConfig.Config.defaults().defaultRolls();
    private static int rollsDone;
    private static int delayTicks;
    private static int timeoutTicks;
    private static int nonStrengthenScreenTicks;
    private static boolean clearBeforeStartPending;
    private static boolean clearBeforeStartRequested;
    private static boolean insertHeldItemRequested;
    private static boolean returningFromClear;
    private static boolean pendingClearHadAttributes;
    private static boolean destroyMode;
    private static String destroyTargetLabel = "";
    private static State stateAfterScreenClose = State.IDLE;
    private static String pendingCloseReason = "";
    private static int heldHotbarSlotBeforeClear;
    private static Evaluation lastEvaluation = Evaluation.empty();
    private static final List<String> runLog = new ArrayList<>();
    private static Path lastLogPath;

    private AutoStrengthenController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        registerCommand(dispatcher, COMMAND);
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, String commandName) {
        dispatcher.register(ClientCommands.literal(commandName)
                .executes(command -> {
                    startFromCommand(command.getSource().getClient(),
                            AutoStrengthenConfig.get(command.getSource().getClient()).defaultRolls());
                    return 1;
                })
                .then(ClientCommands.literal("config")
                        .executes(command -> {
                            openSettings(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("craft")
                        .then(ClientCommands.literal("剑")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "剑");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("头盔")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "头盔");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("胸甲")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "胸甲");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("裤子")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "裤子");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("镐子")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "镐子");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("靴子")
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), "靴子");
                                    return 1;
                                }))
                        .then(ClientCommands.argument("part", StringArgumentType.greedyString())
                                .executes(command -> {
                                    startDestroyMode(command.getSource().getClient(), StringArgumentType.getString(command, "part"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "自动强化已停止。", true);
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        if (!active) {
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoStrengthen()) {
            stop(client, "自动强化已被全局设置屏蔽。", true);
            return;
        }

        if (!isClientReady(client)) {
            stop(client, "客户端状态不可用，自动强化已停止。", true);
            return;
        }

        if (isWaitingOnStrengthenScreen(state) && client.screen != null) {
            if (!isStrengthenScreen(client.screen)) {
                nonStrengthenScreenTicks++;
                if (nonStrengthenScreenTicks >= 6) {
                    stop(client, "已退出装备强化界面，自动强化已关闭。", true);
                    return;
                }
                return;
            }
            nonStrengthenScreenTicks = 0;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (timeoutTicks-- <= 0) {
            stop(client, "等待强化结果超时，自动强化已停止。", true);
            return;
        }

        switch (state) {
            case OPEN_ROOT_MENU -> tickOpenRootMenu(client);
            case WAIT_FOR_SCREEN_CLOSE -> tickWaitForScreenClose(client);
            case CLICK_EQUIPMENT_CATEGORY -> tickClickMenuItem(client, client.screen,
                    AutoStrengthenConfig.get(client).equipmentCategoryLabel(), State.CLICK_STRENGTHEN_MENU);
            case CLICK_EQUIPMENT_CATEGORY_FOR_CLEAR -> tickClickMenuItem(client, client.screen,
                    AutoStrengthenConfig.get(client).equipmentCategoryLabel(), State.CLICK_CLEAR_MENU);
            case CLICK_STRENGTHEN_MENU -> tickClickMenuItem(client, client.screen,
                    AutoStrengthenConfig.get(client).strengthenMenuLabel(), State.READY_TO_INSERT_HELD);
            case CLICK_CLEAR_MENU -> tickClickMenuItem(client, client.screen,
                    AutoStrengthenConfig.get(client).clearAttributesMenuLabel(), State.READY_TO_INSERT_CLEAR_HELD);
            case READY_TO_INSERT_HELD -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickReadyToInsertHeld(client, containerScreen);
                }
            }
            case READY_TO_REMOVE_STRENGTHENED_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickReadyToRemoveStrengthenedItem(client, containerScreen);
                }
            }
            case WAIT_FOR_REMOVE_STRENGTHENED_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickWaitForRemoveStrengthenedItem(client, containerScreen);
                }
            }
            case READY_TO_INSERT_CLEAR_HELD -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickReadyToInsertClearHeld(client, containerScreen);
                }
            }
            case WAIT_FOR_CLEAR_INSERT -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickWaitForClearInsert(client, containerScreen);
                }
            }
            case WAIT_FOR_INSERT -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickWaitForInsert(client, containerScreen);
                }
            }
            case READY_TO_CLEAR -> {
                tickReadyToClear(client);
            }
            case READY_TO_DESTROY_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickReadyToDestroyItem(client, containerScreen);
                }
            }
            case WAIT_FOR_DESTROY_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickWaitForDestroyItem(client, containerScreen);
                }
            }
            case WAIT_FOR_CLEAR_RESULT -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickWaitForClearResult(client, containerScreen);
                }
            }
            case WAIT_FOR_CLEAR_DONE -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickWaitForClearDone(client, containerScreen);
                }
            }
            case READY_TO_REMOVE_CLEARED_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickReadyToRemoveClearedItem(client, containerScreen);
                }
            }
            case WAIT_FOR_REMOVE_CLEARED_ITEM -> {
                AbstractContainerScreen<?> containerScreen = requireClearScreen(client);
                if (containerScreen != null) {
                    tickWaitForRemoveClearedItem(client, containerScreen);
                }
            }
            case OPEN_STRENGTHEN_AFTER_CLEAR -> tickOpenStrengthenAfterClear(client);
            case READY_TO_ROLL -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickReadyToRoll(client, containerScreen);
                }
            }
            case WAIT_FOR_RESULT -> {
                AbstractContainerScreen<?> containerScreen = requireStrengthenScreen(client);
                if (containerScreen != null) {
                    tickWaitForResult(client, containerScreen);
                }
            }
            case IDLE -> {
            }
        }
    }

    static void handleSessionReset(Minecraft client) {
        if (active) {
            stop(client, null, true);
        }
    }

    static void openSettings(Minecraft client) {
        if (client == null) {
            return;
        }
        client.setScreen(ScreenProbeConfigScreenFactory.create(client.screen));
    }

    static boolean isActive() {
        return active;
    }

    static void setEnabled(Minecraft client, boolean value) {
        if (!value && active) {
            stop(client, "自动强化已被全局设置关闭。", true);
        }
    }

    static int rollsDone() {
        return rollsDone;
    }

    static int maxRolls() {
        return maxRolls;
    }

    static AnalysisSnapshot currentSnapshot(Minecraft client) {
        if (!isClientReady(client)) {
            return AnalysisSnapshot.empty();
        }

        ItemStack stack = ItemStack.EMPTY;
        if (client.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Optional<Slot> itemSlot = findStrengthenedItemSlot(containerScreen);
            if (itemSlot.isPresent()) {
                stack = itemSlot.get().getItem();
            } else if (isStrengthenScreen(client.screen)) {
                return AnalysisSnapshot.empty();
            }
        }

        if (stack.isEmpty()) {
            stack = client.player.getMainHandItem();
        }

        if (stack.isEmpty()) {
            return AnalysisSnapshot.empty();
        }

        Evaluation evaluation = evaluate(client, stack);
        return AnalysisSnapshot.from(stack, evaluation);
    }

    private static void startFromCommand(Minecraft client, int requestedRolls) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        destroyMode = false;
        destroyTargetLabel = "";
        start(client, requestedRolls, config.clearBeforeStart());
    }

    private static void startDestroyMode(Minecraft client, String part) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        destroyMode = true;
        destroyTargetLabel = normalizeDestroyTargetLabel(part);
        if (isClientReady(client) && selectExistingUnstrengthenedTarget(client, destroyTargetLabel)) {
            sendMessage(client, "已在背包找到未强化或强化次数为 0 的 " + destroyTargetLabel + "，优先强化该物品。");
        } else if (isClientReady(client) && client.getConnection() != null) {
            client.getConnection().sendCommand("wb");
            sendMessage(client, "背包没有可用的 " + destroyTargetLabel + "，已打开 /wb。自动合成需要工作台界面槽位日志确认后启用。");
        }
        start(client, config.defaultRolls(), false);
    }

    private static void start(Minecraft client, int requestedRolls, boolean clearBeforeStart) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoStrengthen()) {
            sendMessage(client, "自动强化已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动强化。");
            return;
        }

        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (active) {
            stop(client, "自动强化重新启动。", true);
        }

        clearBeforeStartPending = clearBeforeStart;
        insertHeldItemRequested = config.autoInsertHeldItem();
        maxRolls = requestedRolls;
        rollsDone = 0;
        delayTicks = 0;
        timeoutTicks = TIMEOUT_TICKS;
        nonStrengthenScreenTicks = 0;
        lastEvaluation = Evaluation.empty();
        lastLogPath = null;
        runLog.clear();
        runLog.add("# 自动强化记录");
        runLog.add("startedAt=" + LocalDateTime.now());
        runLog.add("maxRolls=" + maxRolls);
        runLog.add("clearBeforeStart=" + clearBeforeStartPending);
        runLog.add("autoOpenStrengthenMenu=" + config.autoOpenStrengthenMenu());
        runLog.add("autoInsertHeldItem=" + config.autoInsertHeldItem());
        runLog.add("destroyMode=" + destroyMode);
        runLog.add("destroyTargetLabel=" + destroyTargetLabel);
        runLog.add("");

        if (client.screen instanceof AbstractContainerScreen<?> containerScreen && isStrengthenScreen(client.screen)) {
            active = true;
            state = State.READY_TO_INSERT_HELD;
            tickReadyToInsertHeld(client, containerScreen);
            return;
        }

        if (!config.autoOpenStrengthenMenu()) {
            sendMessage(client, "请先打开 /" + config.cdCommand() + " -> "
                    + config.equipmentCategoryLabel() + " -> " + config.strengthenMenuLabel()
                    + "，或在 /autosettings 开启自动打开菜单。");
            return;
        }

        active = true;
        state = client.screen == null ? State.OPEN_ROOT_MENU : State.WAIT_FOR_SCREEN_CLOSE;
        stateAfterScreenClose = State.OPEN_ROOT_MENU;
        pendingCloseReason = "启动自动强化";
        if (client.screen != null) {
            closeCurrentScreen(client);
        }
        sendMessage(client, "正在打开 /" + config.cdCommand() + " -> "
                + config.equipmentCategoryLabel() + " -> " + config.strengthenMenuLabel()
                + "，随后强化主手物品。");
    }

    private static void beginRolling(Minecraft client, AbstractContainerScreen<?> screen, Slot itemSlot) {
        clearBeforeStartRequested = clearBeforeStartPending;
        state = clearBeforeStartRequested ? State.READY_TO_CLEAR : State.READY_TO_ROLL;
        delayTicks = 0;
        timeoutTicks = TIMEOUT_TICKS;
        lastEvaluation = evaluate(client, itemSlot.getItem());
        runLog.add("screenTitle=" + client.screen.getTitle().getString());
        runLog.add("itemSlot=" + itemSlot.index);
        runLog.add("");
        appendEvaluationLog("启动前", itemSlot.getItem(), lastEvaluation);

        sendMessage(client, "自动强化启动：每轮 " + maxRolls + " 次"
                + (destroyMode ? "，销毁不合格模式：" + destroyTargetLabel : "")
                + (clearBeforeStartRequested ? "，先清除属性" : "")
                + (destroyMode ? "。命中保留条件会停止；本轮未命中会丢弃。" : "。命中保留条件会停止；本轮未命中会清除属性后继续。"));
        clearBeforeStartPending = false;
        insertHeldItemRequested = false;
    }

    private static void tickOpenRootMenu(Minecraft client) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (client.screen != null) {
            beginCloseScreen(client, State.OPEN_ROOT_MENU, "打开根菜单");
            return;
        }
        client.getConnection().sendCommand(config.cdCommand());
        state = State.CLICK_EQUIPMENT_CATEGORY;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForScreenClose(Minecraft client) {
        if (client.screen != null) {
            closeCurrentScreen(client);
            delayTicks = closeScreenDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        State nextState = stateAfterScreenClose == State.IDLE ? State.OPEN_ROOT_MENU : stateAfterScreenClose;
        runLog.add("screenClosedFor=" + pendingCloseReason + ",nextState=" + nextState);
        stateAfterScreenClose = State.IDLE;
        pendingCloseReason = "";
        state = nextState;
        delayTicks = closeScreenDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickClickMenuItem(Minecraft client, Screen screen, String label, State nextState) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen && isStrengthenScreen(screen)) {
            if (nextState != State.CLICK_CLEAR_MENU && nextState != State.READY_TO_INSERT_CLEAR_HELD) {
                state = State.READY_TO_INSERT_HELD;
                delayTicks = actionDelayTicks(client);
                timeoutTicks = TIMEOUT_TICKS;
                return;
            }
        }

        if (screen instanceof AbstractContainerScreen<?> containerScreen && isClearScreen(screen)) {
            state = State.READY_TO_INSERT_CLEAR_HELD;
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        int slot = findSlotByText(containerScreen, label);
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (slot < 0 && containsNormalized(label, config.clearAttributesMenuLabel())) {
            slot = findSlotByAnyText(containerScreen, CLEAR_MENU_FALLBACK_LABELS);
        }
        if (slot < 0) {
            return;
        }

        clickSlot(client, containerScreen, slot);
        runLog.add("clickedMenu=" + label);
        state = nextState;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReadyToInsertHeld(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isPresent()) {
            beginRolling(client, screen, itemSlot.get());
            return;
        }

        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (!insertHeldItemRequested || !config.autoInsertHeldItem()) {
            stop(client, "没有读到待强化物品。请把物品放进装备强化槽，或在 /autosettings 开启自动放入手持。", true);
            return;
        }

        if (client.player.getInventory().getSelectedItem().isEmpty()) {
            stop(client, "主手为空，无法自动放入强化槽。", true);
            return;
        }

        int inputSlot = findStrengthenInputSlot(screen);
        if (inputSlot < 0) {
            stop(client, "没有找到强化槽，无法自动放入主手物品。", true);
            return;
        }

        int hotbarSlot = client.player.getInventory().getSelectedSlot();
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                inputSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        runLog.add("swappedHeldItemToSlot=" + inputSlot + ",hotbar=" + hotbarSlot);
        state = State.WAIT_FOR_INSERT;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForInsert(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            return;
        }
        beginRolling(client, screen, itemSlot.get());
    }

    private static void tickReadyToClear(Minecraft client) {
        if (client.screen instanceof AbstractContainerScreen<?> screen && isStrengthenScreen(client.screen)) {
            if (findStrengthenedItemSlot(screen).isPresent()) {
                state = State.READY_TO_REMOVE_STRENGTHENED_ITEM;
                delayTicks = 0;
                timeoutTicks = TIMEOUT_TICKS;
                return;
            }
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen && isClearScreen(client.screen)) {
            state = State.READY_TO_INSERT_CLEAR_HELD;
            delayTicks = 0;
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (client.screen != null) {
            beginCloseScreen(client, State.READY_TO_CLEAR, "进入清除属性");
            return;
        }
        client.getConnection().sendCommand(config.cdCommand());
        state = State.CLICK_EQUIPMENT_CATEGORY_FOR_CLEAR;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "正在进入 /" + config.cdCommand() + " -> "
                + config.equipmentCategoryLabel() + " -> " + config.clearAttributesMenuLabel() + " 清除属性。");
    }

    private static void tickReadyToRemoveStrengthenedItem(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            state = State.READY_TO_CLEAR;
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        heldHotbarSlotBeforeClear = client.player.getInventory().getSelectedSlot();
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                itemSlot.get().index,
                heldHotbarSlotBeforeClear,
                ContainerInput.SWAP,
                client.player
        );
        runLog.add("removedStrengthenedItemToHotbar=" + heldHotbarSlotBeforeClear);
        state = State.WAIT_FOR_REMOVE_STRENGTHENED_ITEM;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForRemoveStrengthenedItem(Minecraft client, AbstractContainerScreen<?> screen) {
        if (findStrengthenedItemSlot(screen).isPresent()) {
            return;
        }

        state = State.READY_TO_CLEAR;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReadyToInsertClearHeld(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client.player.getInventory().getSelectedItem().isEmpty()) {
            stop(client, "主手为空，无法放入清除属性槽。", true);
            return;
        }

        int inputSlot = findStrengthenInputSlot(screen);
        if (inputSlot < 0) {
            stop(client, "没有找到清除属性槽，自动强化已停止。", true);
            return;
        }

        int hotbarSlot = client.player.getInventory().getSelectedSlot();
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                inputSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        runLog.add("swappedHeldItemToClearSlot=" + inputSlot + ",hotbar=" + hotbarSlot);
        state = State.WAIT_FOR_CLEAR_INSERT;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForClearInsert(Minecraft client, AbstractContainerScreen<?> screen) {
        if (findStrengthenedItemSlot(screen).isEmpty()) {
            return;
        }

        state = State.WAIT_FOR_CLEAR_RESULT;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForClearResult(Minecraft client, AbstractContainerScreen<?> screen) {
        int confirmSlot = findClearConfirmSlot(screen);
        if (confirmSlot < 0) {
            return;
        }

        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        pendingClearHadAttributes = itemSlot.isPresent() && !evaluate(client, itemSlot.get().getItem()).values().isEmpty();
        clickSlot(client, screen, confirmSlot);
        runLog.add("clickedClearConfirm=" + confirmSlot);
        state = State.WAIT_FOR_CLEAR_DONE;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForClearDone(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        Evaluation evaluation = evaluate(client, itemSlot.get().getItem());
        if (pendingClearHadAttributes && !evaluation.values().isEmpty()) {
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        lastEvaluation = evaluation;
        appendEvaluationLog("清除属性后", itemSlot.get().getItem(), lastEvaluation);
        clearBeforeStartRequested = false;
        returningFromClear = true;
        pendingClearHadAttributes = false;
        state = State.READY_TO_REMOVE_CLEARED_ITEM;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReadyToRemoveClearedItem(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            state = State.OPEN_STRENGTHEN_AFTER_CLEAR;
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        int hotbarSlot = client.player.getInventory().getSelectedSlot();
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                itemSlot.get().index,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        runLog.add("removedClearedItemToHotbar=" + hotbarSlot);
        state = State.WAIT_FOR_REMOVE_CLEARED_ITEM;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForRemoveClearedItem(Minecraft client, AbstractContainerScreen<?> screen) {
        if (findStrengthenedItemSlot(screen).isPresent()) {
            return;
        }

        state = State.OPEN_STRENGTHEN_AFTER_CLEAR;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickOpenStrengthenAfterClear(Minecraft client) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (client.screen != null) {
            beginCloseScreen(client, State.OPEN_STRENGTHEN_AFTER_CLEAR, "清除后返回强化");
            return;
        }

        insertHeldItemRequested = true;
        returningFromClear = false;
        rollsDone = 0;
        client.getConnection().sendCommand(config.cdCommand());
        state = State.CLICK_EQUIPMENT_CATEGORY;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void beginCloseScreen(Minecraft client, State nextState, String reason) {
        stateAfterScreenClose = nextState;
        pendingCloseReason = reason;
        closeCurrentScreen(client);
        state = State.WAIT_FOR_SCREEN_CLOSE;
        delayTicks = closeScreenDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void closeCurrentScreen(Minecraft client) {
        if (client == null || client.screen == null || client.player == null) {
            return;
        }
        client.player.closeContainer();
    }

    private static void tickReadyToDestroyItem(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            state = State.READY_TO_INSERT_HELD;
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        ItemStack stack = itemSlot.get().getItem();
        runLog.add("destroyUnmatchedItem=" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + ",name=" + stack.getHoverName().getString());
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                itemSlot.get().index,
                1,
                ContainerInput.THROW,
                client.player
        );
        state = State.WAIT_FOR_DESTROY_ITEM;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForDestroyItem(Minecraft client, AbstractContainerScreen<?> screen) {
        if (findStrengthenedItemSlot(screen).isPresent()) {
            return;
        }

        rollsDone = 0;
        insertHeldItemRequested = true;
        state = State.READY_TO_INSERT_HELD;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReadyToRoll(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            stop(client, "强化槽里没有可判定的物品，自动强化已停止。", true);
            return;
        }

        lastEvaluation = evaluate(client, itemSlot.get().getItem());
        appendEvaluationLog("第 " + rollsDone + " 次后检查", itemSlot.get().getItem(), lastEvaluation);

        if (lastEvaluation.keep()) {
            announceMatchedItem(client, itemSlot.get().getItem(), lastEvaluation);
            stop(client, "已命中保留条件：" + lastEvaluation.reason() + "。", true);
            return;
        }

        Optional<String> clearReason = mixedLowAttributeClearReason(client, lastEvaluation);
        if (clearReason.isPresent()) {
            if (destroyMode) {
                scheduleDestroy(client, clearReason.get());
                return;
            }
            scheduleClear(client, clearReason.get());
            return;
        }

        if (rollsDone >= maxRolls) {
            if (destroyMode) {
                scheduleDestroy(client, "本轮已强化 " + rollsDone + "/" + maxRolls + " 次仍未命中保留条件");
                return;
            }
            scheduleClear(client, "本轮已强化 " + rollsDone + "/" + maxRolls + " 次仍未命中保留条件");
            return;
        }

        int confirmSlot = findSlotByText(screen, CONFIRM_LABEL);
        if (confirmSlot < 0) {
            runLog.add("waitingForStrengthenConfirmButton");
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        clickSlot(client, screen, confirmSlot);
        rollsDone++;
        runLog.add("clickedConfirmRoll=" + rollsDone);
        state = State.WAIT_FOR_RESULT;
        delayTicks = resultDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickWaitForResult(Minecraft client, AbstractContainerScreen<?> screen) {
        Optional<Slot> itemSlot = findStrengthenedItemSlot(screen);
        if (itemSlot.isEmpty()) {
            delayTicks = actionDelayTicks(client);
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        lastEvaluation = evaluate(client, itemSlot.get().getItem());
        appendEvaluationLog("第 " + rollsDone + " 次强化后", itemSlot.get().getItem(), lastEvaluation);

        if (lastEvaluation.keep()) {
            announceMatchedItem(client, itemSlot.get().getItem(), lastEvaluation);
            stop(client, "第 " + rollsDone + " 次强化后命中保留条件：" + lastEvaluation.reason() + "。", true);
            return;
        }

        Optional<String> clearReason = mixedLowAttributeClearReason(client, lastEvaluation);
        if (clearReason.isPresent()) {
            if (destroyMode) {
                scheduleDestroy(client, clearReason.get());
                return;
            }
            scheduleClear(client, clearReason.get());
            return;
        }

        if (rollsDone >= maxRolls) {
            if (destroyMode) {
                scheduleDestroy(client, "本轮已强化 " + rollsDone + "/" + maxRolls + " 次仍未命中保留条件");
                return;
            }
            scheduleClear(client, "本轮已强化 " + rollsDone + "/" + maxRolls + " 次仍未命中保留条件");
            return;
        }

        state = State.READY_TO_ROLL;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void scheduleClear(Minecraft client, String reason) {
        runLog.add("autoClearReason=" + reason);
        sendMessage(client, reason + "，执行清除属性。");
        state = State.READY_TO_CLEAR;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void scheduleDestroy(Minecraft client, String reason) {
        runLog.add("autoDestroyReason=" + reason);
        sendMessage(client, reason + "，丢弃不合格装备。");
        state = State.READY_TO_DESTROY_ITEM;
        delayTicks = actionDelayTicks(client);
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static boolean isWaitingOnStrengthenScreen(State currentState) {
        return currentState == State.READY_TO_INSERT_HELD
                || currentState == State.WAIT_FOR_INSERT
                || currentState == State.READY_TO_ROLL
                || currentState == State.WAIT_FOR_RESULT
                || currentState == State.READY_TO_DESTROY_ITEM
                || currentState == State.WAIT_FOR_DESTROY_ITEM
                || currentState == State.READY_TO_REMOVE_STRENGTHENED_ITEM
                || currentState == State.WAIT_FOR_REMOVE_STRENGTHENED_ITEM;
    }

    private static void announceMatchedItem(Minecraft client, ItemStack stack, Evaluation evaluation) {
        String itemName = stack.getHoverName().getString();
        String message = "出货：" + itemName + "，" + evaluation.reason();
        sendMessage(client, message);
        try {
            client.gui.setTitle(Component.literal(message).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            client.gui.setSubtitle(Component.literal(evaluation.summary()).withStyle(ChatFormatting.YELLOW));
            client.gui.setTimes(10, 80, 20);
        } catch (RuntimeException exception) {
            ScreenProbe.LOGGER.warn("Failed to show strengthen title", exception);
        }
    }

    private static String normalizeDestroyTargetLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "钻石剑";
        }
        String value = raw.trim();
        return switch (normalize(value)) {
            case "剑", "钻石剑", "sword", "diamondsword" -> "钻石剑";
            case "镐", "稿", "镐子", "稿子", "钻石镐", "钻石镐子", "pickaxe", "diamondpickaxe" -> "钻石镐";
            case "头", "头盔", "钻石头盔", "helmet", "diamondhelmet" -> "钻石头盔";
            case "胸", "胸甲", "钻石胸甲", "chest", "chestplate", "diamondchestplate" -> "钻石胸甲";
            case "腿", "裤", "裤子", "钻石裤", "钻石裤子", "护腿", "钻石护腿", "leggings", "diamondleggings" -> "钻石护腿";
            case "鞋", "靴子", "钻石靴子", "boots", "diamondboots" -> "钻石靴子";
            default -> value;
        };
    }

    private static boolean selectExistingUnstrengthenedTarget(Minecraft client, String targetLabel) {
        Inventory inventory = client.player.getInventory();
        int targetSlot = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matchesDestroyTarget(stack, targetLabel) || !isUnstrengthenedOrZero(stack)) {
                continue;
            }
            targetSlot = slot;
            break;
        }
        if (targetSlot < 0) {
            return false;
        }

        if (targetSlot < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(targetSlot);
            return true;
        }

        int hotbarSlot = findHotbarTargetSlot(inventory);
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                targetSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        inventory.setSelectedSlot(hotbarSlot);
        return true;
    }

    private static int findHotbarTargetSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return inventory.getSelectedSlot();
    }

    private static boolean matchesDestroyTarget(ItemStack stack, String targetLabel) {
        String normalizedTarget = normalize(targetLabel);
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String name = stack.getHoverName().getString();
        return normalize(name).contains(normalizedTarget)
                || switch (normalizedTarget) {
                    case "钻石剑" -> itemId.equals("minecraft:diamond_sword");
                    case "钻石镐" -> itemId.equals("minecraft:diamond_pickaxe");
                    case "钻石头盔" -> itemId.equals("minecraft:diamond_helmet");
                    case "钻石胸甲" -> itemId.equals("minecraft:diamond_chestplate");
                    case "钻石靴子" -> itemId.equals("minecraft:diamond_boots");
                    default -> false;
                };
    }

    private static boolean isUnstrengthenedOrZero(ItemStack stack) {
        List<String> lines = collectItemLines(Minecraft.getInstance(), stack);
        boolean sawStrengthen = false;
        for (String line : lines) {
            String normalized = normalize(line);
            if (normalized.contains("强化次数") || normalized.contains("强化等级") || normalized.contains("装备强化")) {
                sawStrengthen = true;
                if (normalized.contains("强化次数0") || normalized.contains("强化等级0")
                        || normalized.contains("强化次数:0") || normalized.contains("强化次数：0")) {
                    return true;
                }
            }
        }
        return !sawStrengthen;
    }

    private static Optional<Slot> findStrengthenedItemSlot(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);

        if (playerInventoryStart > 4) {
            Slot center = slots.get(4);
            if (isCandidateItemSlot(center)) {
                return Optional.of(center);
            }
        }

        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (isCandidateItemSlot(slot)) {
                return Optional.of(slot);
            }
        }

        return Optional.empty();
    }

    private static int findStrengthenInputSlot(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);

        if (playerInventoryStart > 4) {
            Slot center = slots.get(4);
            if (center != null && center.isActive()) {
                return 4;
            }
        }

        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.isActive()) {
                continue;
            }
            if (!slot.hasItem()) {
                return slotIndex;
            }
            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString();
            if (containsNormalized(name, "请放入物品")
                    || containsNormalized(name, "请放置强化物品")
                    || containsNormalized(name, "请放入装备")
                    || containsNormalized(name, "请放置装备")
                    || containsNormalized(name, "强化槽")
                    || containsNormalized(name, "清除槽")
                    || tooltipContains(stack, "请放入物品")
                    || tooltipContains(stack, "请放置强化物品")
                    || tooltipContains(stack, "请放入装备")
                    || tooltipContains(stack, "请放置装备")
                    || tooltipContains(stack, "强化槽")
                    || tooltipContains(stack, "清除槽")) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean isCandidateItemSlot(Slot slot) {
        if (slot == null || !slot.hasItem() || !slot.isActive()) {
            return false;
        }

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return false;
        }

        String name = stack.getHoverName().getString();
        if (containsNormalized(name, "请放入物品")
                || containsNormalized(name, "请放置强化物品")
                || containsNormalized(name, "请放入装备")
                || containsNormalized(name, "请放置装备")
                || containsNormalized(name, "返回菜单")
                || containsNormalized(name, CONFIRM_LABEL)
                || isClearControlStack(stack)) {
            return false;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return !itemId.endsWith("_stained_glass_pane");
    }

    private static int findSlotByText(AbstractContainerScreen<?> screen, String expectedText) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (containsNormalized(stack.getHoverName().getString(), expectedText)
                    || tooltipContains(stack, expectedText)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static int findSlotByAnyText(AbstractContainerScreen<?> screen, List<String> expectedTexts) {
        for (String expectedText : expectedTexts) {
            int slot = findSlotByText(screen, expectedText);
            if (slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isClearControlStack(ItemStack stack) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(Minecraft.getInstance());
        if (containsNormalized(stack.getHoverName().getString(), config.clearAttributesConfirmLabel())
                || containsNormalized(stack.getHoverName().getString(), config.clearAttributesMenuLabel())) {
            return true;
        }
        for (String label : CLEAR_CONFIRM_FALLBACK_LABELS) {
            if (containsNormalized(stack.getHoverName().getString(), label) || tooltipContains(stack, label)) {
                return true;
            }
        }
        return tooltipContains(stack, config.clearAttributesConfirmLabel())
                || tooltipContains(stack, config.clearAttributesMenuLabel());
    }

    private static Evaluation evaluate(Minecraft client, ItemStack stack) {
        Map<String, Integer> values = new LinkedHashMap<>();
        List<AttributeValue> orderedValues = new ArrayList<>();
        int damage = 0;
        int defense = 0;
        int bestFocus = 0;
        String bestFocusName = "";

        List<String> itemLines = collectItemLines(client, stack);
        collectAttributeValues(itemLines, values, orderedValues);
        if (values.isEmpty()) {
            collectAttributeValues(List.of(stack.getComponentsPatch().toString()), values, orderedValues);
        }

        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String attribute = entry.getKey();
            int value = entry.getValue();
            if (isDamageAttribute(attribute)) {
                damage += value;
            } else if (isDefenseAttribute(attribute)) {
                defense += value;
            } else if (value > bestFocus) {
                bestFocus = value;
                bestFocusName = attribute;
            }
        }

        boolean keep = false;
        String reason = "未命中";
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (damage >= config.damageThreshold()) {
            keep = true;
            reason = "高伤害 " + damage;
        }
        if (defense >= config.defenseThreshold()) {
            keep = true;
            reason = "高防御 " + defense;
        }
        if (damage >= config.bothDamageThreshold() && defense >= config.bothDefenseThreshold()) {
            keep = true;
            reason = "伤防都高：伤害 " + damage + " / 防御 " + defense;
        }
        if (bestFocus >= config.focusChanceThreshold()) {
            keep = true;
            reason = "侧重 " + bestFocusName + " " + bestFocus;
        }

        return new Evaluation(keep, damage, defense, bestFocusName, bestFocus, reason, values, orderedValues);
    }

    private static Optional<String> mixedLowAttributeClearReason(Minecraft client, Evaluation evaluation) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (!config.autoClearMixedLowAttributes()) {
            return Optional.empty();
        }

        List<AttributeValue> attributes = evaluation.orderedValues();
        int count = config.autoClearAttributeCount();
        if (attributes.size() < count) {
            return Optional.empty();
        }

        List<AttributeValue> checked = attributes.subList(0, count);
        int sum = 0;
        List<String> names = new ArrayList<>();
        for (AttributeValue attribute : checked) {
            if (!isKnownAttribute(attribute.name())) {
                return Optional.empty();
            }
            if (names.contains(attribute.name())) {
                return Optional.empty();
            }
            names.add(attribute.name());
            sum += attribute.value();
        }

        if (sum <= config.autoClearAttributeSumThreshold()) {
            return Optional.of("前 " + count + " 个词条不同且和值 " + sum
                    + " <= " + config.autoClearAttributeSumThreshold());
        }
        return Optional.empty();
    }

    private static void collectAttributeValues(List<String> lines, Map<String, Integer> values,
                                               List<AttributeValue> orderedValues) {
        for (String line : lines) {
            String stripped = stripFormatting(line);
            if (stripped.isBlank() || stripped.startsWith("minecraft:") || stripped.endsWith("个组件")) {
                continue;
            }

            readLineAttribute(stripped).ifPresent(attribute -> {
                if (!isKnownAttribute(attribute.name())) {
                    return;
                }
                values.merge(attribute.name(), attribute.value(), Integer::sum);
                orderedValues.add(attribute);
            });
        }
    }

    private static Optional<AttributeValue> readLineAttribute(String line) {
        if (line.contains("[") && line.contains("]") || containsNormalized(line, "装备强化")) {
            return Optional.empty();
        }

        Matcher matcher = INTEGER_PATTERN.matcher(line);
        int best = 0;
        while (matcher.find()) {
            int value;
            try {
                value = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            int abs = Math.abs(value);
            if (abs <= 100 && abs > best) {
                best = abs;
            }
        }

        if (best <= 0) {
            return Optional.empty();
        }

        String name = line;
        int colon = Math.max(name.lastIndexOf(':'), name.lastIndexOf('：'));
        if (colon >= 0) {
            name = name.substring(0, colon);
        } else {
            name = name.replaceFirst("[+-]?\\d+.*$", "");
        }
        name = name.replace("+", "").trim();
        if (name.isBlank() || name.length() > 16) {
            return Optional.empty();
        }
        return Optional.of(new AttributeValue(name, best));
    }

    private static boolean isKnownAttribute(String attribute) {
        return DAMAGE_ATTRIBUTES.contains(attribute)
                || DEFENSE_ATTRIBUTES.contains(attribute)
                || FOCUS_ATTRIBUTES.contains(attribute)
                || containsNormalized(attribute, "几率")
                || containsNormalized(attribute, "概率")
                || containsNormalized(attribute, "率")
                || containsNormalized(attribute, "吸血")
                || containsNormalized(attribute, "闪避")
                || containsNormalized(attribute, "攻速");
    }

    private static boolean isDamageAttribute(String attribute) {
        return DAMAGE_ATTRIBUTES.contains(attribute);
    }

    private static boolean isDefenseAttribute(String attribute) {
        return DEFENSE_ATTRIBUTES.contains(attribute);
    }

    private static List<String> collectItemLines(Minecraft client, ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getHoverName().getString());
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.ADVANCED)) {
                lines.add(line.getString());
            }
        } catch (RuntimeException exception) {
            ScreenProbe.LOGGER.warn("Failed to read strengthen item tooltip for {}", stack, exception);
        }
        return lines;
    }

    private static int readAttributeValue(String line, String attribute) {
        if (!containsNormalized(line, attribute)) {
            return 0;
        }

        Matcher matcher = INTEGER_PATTERN.matcher(line);
        int best = 0;
        while (matcher.find()) {
            int value;
            try {
                value = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            int abs = Math.abs(value);
            if (abs <= 100 && abs > best) {
                best = abs;
            }
        }
        return best;
    }

    private static int findClearConfirmSlot(AbstractContainerScreen<?> screen) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(Minecraft.getInstance());
        int slot = findSlotByText(screen, config.clearAttributesConfirmLabel());
        if (slot >= 0) {
            return slot;
        }
        for (String label : CLEAR_CONFIRM_FALLBACK_LABELS) {
            slot = findSlotByText(screen, label);
            if (slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean tooltipContains(ItemStack stack, String expectedText) {
        Minecraft client = Minecraft.getInstance();
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.ADVANCED)) {
                if (containsNormalized(line.getString(), expectedText)) {
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            ScreenProbe.LOGGER.warn("Failed to read tooltip while finding slot", exception);
        }
        return false;
    }

    private static void clickSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex) {
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotIndex,
                0,
                ContainerInput.PICKUP,
                client.player
        );
    }

    static boolean isStrengthenScreen(Screen screen) {
        return screen != null && containsNormalized(screen.getTitle().getString(), SCREEN_TITLE);
    }

    private static boolean isClearScreen(Screen screen) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(Minecraft.getInstance());
        if (screen == null) {
            return false;
        }
        if (containsNormalized(screen.getTitle().getString(), CLEAR_SCREEN_TITLE)
                || containsNormalized(screen.getTitle().getString(), config.clearAttributesMenuLabel())) {
            return true;
        }
        for (String label : CLEAR_MENU_FALLBACK_LABELS) {
            if (containsNormalized(screen.getTitle().getString(), label)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractContainerScreen<?> requireStrengthenScreen(Minecraft client) {
        Screen screen = client.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || !isStrengthenScreen(screen)) {
            stop(client, "请保持打开 桃花源丨装备强化 界面，自动强化已停止。", true);
            return null;
        }
        return containerScreen;
    }

    private static AbstractContainerScreen<?> requireClearScreen(Minecraft client) {
        Screen screen = client.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || !isClearScreen(screen)) {
            stop(client, "请保持打开 清除属性 界面，自动强化已停止。", true);
            return null;
        }
        return containerScreen;
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.getConnection() != null && client.gameMode != null;
    }

    private static void appendEvaluationLog(String phase, ItemStack stack, Evaluation evaluation) {
        runLog.add("## " + phase);
        runLog.add("time=" + LocalDateTime.now());
        runLog.add("itemId=" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
        runLog.add("hoverName=" + stack.getHoverName().getString());
        runLog.add("evaluation=" + evaluation.summary());
        runLog.add("reason=" + evaluation.reason());
        runLog.add("componentsPatch=" + stack.getComponentsPatch());
        runLog.add("");
    }

    private static void sendStatus(Minecraft client) {
        String log = lastLogPath == null ? "-" : lastLogPath.toAbsolutePath().toString();
        sendMessage(client, "启用 " + active
                + " | 状态 " + state
                + " | 已强化 " + rollsDone + "/" + maxRolls
                + " | 当前 " + lastEvaluation.summary()
                + " | 日志 " + log);
    }

    private static void stop(Minecraft client, String message, boolean writeLog) {
        if (writeLog && message != null && !runLog.isEmpty()) {
            runLog.add("stoppedAt=" + LocalDateTime.now());
            runLog.add("stopMessage=" + message);
            runLog.add("finalState=" + state);
        }
        if (writeLog && !runLog.isEmpty()) {
            lastLogPath = writeLog(client);
        }
        active = false;
        state = State.IDLE;
        clearBeforeStartPending = false;
        clearBeforeStartRequested = false;
        insertHeldItemRequested = false;
        returningFromClear = false;
        pendingClearHadAttributes = false;
        destroyMode = false;
        destroyTargetLabel = "";
        stateAfterScreenClose = State.IDLE;
        pendingCloseReason = "";
        delayTicks = 0;
        timeoutTicks = 0;
        nonStrengthenScreenTicks = 0;
        if (message != null) {
            String suffix = lastLogPath == null ? "" : " 日志：" + lastLogPath.toAbsolutePath();
            sendMessage(client, message + suffix);
        }
    }

    private static Path writeLog(Minecraft client) {
        if (client == null) {
            return null;
        }
        try {
            Path logDir = client.gameDirectory.toPath().resolve("logs").resolve("screenprobe").resolve("auto-strengthen");
            Files.createDirectories(logDir);
            Path file = logDir.resolve("auto-strengthen-" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".txt");
            Files.write(file, runLog);
            return file;
        } catch (IOException exception) {
            ScreenProbe.LOGGER.error("Failed to write auto strengthen log", exception);
            return null;
        }
    }

    private static int actionDelayTicks(Minecraft client) {
        return ScreenProbeGlobalConfig.menuActionDelayTicks(client);
    }

    private static int resultDelayTicks(Minecraft client) {
        return ScreenProbeGlobalConfig.menuResultDelayTicks(client);
    }

    private static int closeScreenDelayTicks(Minecraft client) {
        return ScreenProbeGlobalConfig.menuCloseDelayTicks(client);
    }

    private static void sendMessage(Minecraft client, String message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(
                    Component.empty()
                            .append(Component.literal(prefix).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(message).withStyle(ChatFormatting.AQUA))
            );
        }
    }

    private static boolean containsNormalized(String text, String expected) {
        return normalize(text).contains(normalize(expected));
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return stripFormatting(text)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("[", "")
                .replace("]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("§.", "");
    }

    private enum State {
        IDLE,
        OPEN_ROOT_MENU,
        WAIT_FOR_SCREEN_CLOSE,
        CLICK_EQUIPMENT_CATEGORY,
        CLICK_EQUIPMENT_CATEGORY_FOR_CLEAR,
        CLICK_STRENGTHEN_MENU,
        CLICK_CLEAR_MENU,
        READY_TO_INSERT_HELD,
        WAIT_FOR_INSERT,
        READY_TO_DESTROY_ITEM,
        WAIT_FOR_DESTROY_ITEM,
        READY_TO_REMOVE_STRENGTHENED_ITEM,
        WAIT_FOR_REMOVE_STRENGTHENED_ITEM,
        READY_TO_INSERT_CLEAR_HELD,
        WAIT_FOR_CLEAR_INSERT,
        READY_TO_CLEAR,
        WAIT_FOR_CLEAR_RESULT,
        WAIT_FOR_CLEAR_DONE,
        READY_TO_REMOVE_CLEARED_ITEM,
        WAIT_FOR_REMOVE_CLEARED_ITEM,
        OPEN_STRENGTHEN_AFTER_CLEAR,
        READY_TO_ROLL,
        WAIT_FOR_RESULT
    }

    private record AttributeValue(String name, int value) {
    }

    private record Evaluation(boolean keep, int damage, int defense, String focusName, int focusValue,
                              String reason, Map<String, Integer> values, List<AttributeValue> orderedValues) {
        static Evaluation empty() {
            return new Evaluation(false, 0, 0, "", 0, "未读取", Map.of(), List.of());
        }

        String summary() {
            return "伤害 " + damage
                    + " / 防御 " + defense
                    + " / 侧重 " + (focusName == null || focusName.isBlank() ? "-" : focusName + " " + focusValue)
                    + " / 词条 " + values;
        }
    }

    record AnalysisSnapshot(String itemName, String itemKey, String statsKey,
                            int totalDamage, int totalDefense,
                            int trueDamage, int meleeDamage, int physicalDamage,
                            int rangedDamage, int critDamage, int lightningDamage,
                            int pvpDamage, int pveDamage,
                            String reason, Map<String, Integer> values) {
        static AnalysisSnapshot empty() {
            return new AnalysisSnapshot("-", "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "未读取", Map.of());
        }

        static AnalysisSnapshot from(ItemStack stack, Evaluation evaluation) {
            Map<String, Integer> values = evaluation.values();
            String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getHoverName().getString();
            String statsKey = values.toString();
            return new AnalysisSnapshot(
                    stack.getHoverName().getString(),
                    itemKey,
                    statsKey,
                    evaluation.damage(),
                    evaluation.defense(),
                    values.getOrDefault("真实伤害", 0),
                    values.getOrDefault("近战伤害", 0),
                    values.getOrDefault("物理伤害", 0),
                    values.getOrDefault("远程伤害", 0),
                    values.getOrDefault("暴击伤害", 0),
                    values.getOrDefault("雷击伤害", 0),
                    values.getOrDefault("PVP伤害", 0),
                    values.getOrDefault("PVE伤害", 0),
                    evaluation.reason(),
                    values
            );
        }
    }
}
