package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutoMineController {
    private static final String COMMAND = "automine";
    private static final String CHAT_MODULE = "自动挖矿";
    private static final int TIMEOUT_TICKS = 20 * 45;
    private static final int MENU_DELAY_TICKS = 12;
    private static final int BARITONE_REISSUE_TICKS = 20 * 20;
    private static final int REPORT_MIN_TICKS = 20 * 60;
    private static final int INVENTORY_FULL_THRESHOLD_EMPTY_SLOTS = 3;
    private static final int PICKAXE_REPAIR_DAMAGE_THRESHOLD = 250;
    private static final int MAX_BACKPACK_INDEX = 100;
    private static final int STORE_SHULKER_TIMEOUT_TICKS = 20 * 12;
    private static final Pattern BACKPACK_NAME_PATTERN = Pattern.compile("背包(\\d+)");
    private static final Pattern TEMP_BACKPACK_NAME_PATTERN = Pattern.compile("临时背包(\\d+)");
    private static final Set<String> DIAMOND_KEEP_IDS = Set.of(
            "minecraft:diamond_ore",
            "minecraft:deepslate_diamond_ore",
            "minecraft:diamond"
    );
    private static final Set<String> DEBRIS_KEEP_IDS = Set.of(
            "minecraft:ancient_debris",
            "minecraft:netherite_scrap"
    );

    private static boolean active;
    private static MineMode mode = MineMode.DIAMOND;
    private static State state = State.IDLE;
    private static int delayTicks;
    private static int timeoutTicks;
    private static int baritoneCooldownTicks;
    private static int reportCooldownTicks;
    private static int reconnectDelayTicks;
    private static int currentNetherPoint;
    private static int pointStartOreCount;
    private static int pointStoredOreCount;
    private static int lastOreCount;
    private static int lavaDeathCount;
    private static int storeOreCountBefore;
    private static int storeBackpackNextIndex = 1;
    private static int storeCurrentBackpackIndex;
    private static int storeAttempts;
    private static int storeMovedAmount;
    private static int storePickupTimeoutTicks;
    private static int storeMiningTicks;
    private static int tempBackpackNextIndex = 1;
    private static int tempCurrentBackpackIndex;
    private static int tempMovedStacks;
    private static int tempAttempts;
    private static boolean waitingAfterDeath;
    private static boolean storeMiningActive;
    private static boolean temporaryInventoryPrepared;
    private static State nextStateAfterTemporaryPrepare = State.IDLE;
    private static BlockPos storeShulkerPos;
    private static BlockPos storeMineTarget;
    private static String lastDeathMessage = "";

    private AutoMineController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal(COMMAND)
                .executes(command -> {
                    sendStatus(command.getSource().getClient());
                    return 1;
                })
                .then(ClientCommands.literal("diamond")
                        .executes(command -> {
                            start(command.getSource().getClient(), MineMode.DIAMOND);
                            return 1;
                        }))
                .then(ClientCommands.literal("debris")
                        .executes(command -> {
                            start(command.getSource().getClient(), MineMode.DEBRIS);
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "自动挖矿已停止。", true);
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("clearpoints")
                        .executes(command -> {
                            AutoMineConfig.clearCompletedNetherPoints(command.getSource().getClient());
                            sendMessage(command.getSource().getClient(), "已清空下界地标完成标记。");
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        if (!active) {
            return;
        }
        if (!AutoMineConfig.get(client).enabled()) {
            stop(client, "自动挖矿已被配置关闭。", true);
            return;
        }
        if (!isClientReady(client)) {
            handleDisconnected(client);
            return;
        }
        if (handleDeath(client)) {
            return;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        if (timeoutTicks > 0) {
            timeoutTicks--;
        }
        if (baritoneCooldownTicks > 0) {
            baritoneCooldownTicks--;
        }
        if (reportCooldownTicks > 0) {
            reportCooldownTicks--;
        } else if (state == State.MINING) {
            reportProgress(client);
        }

        switch (state) {
            case PREPARE_PICKAXE -> tickPreparePickaxe(client);
            case OPEN_EC_FOR_TEMP_PREPARE -> tickOpenEcForTempPrepare(client);
            case TAKE_TEMP_BACKPACK_FROM_EC -> tickTakeTempBackpackFromEc(client);
            case PLACE_TEMP_BACKPACK -> tickPlaceTempBackpack(client);
            case OPEN_TEMP_BACKPACK -> tickOpenTempBackpack(client);
            case STORE_TEMP_INVENTORY -> tickStoreTempInventory(client);
            case PICKUP_TEMP_BACKPACK -> tickPickupTempBackpack(client);
            case OPEN_EC_FOR_TEMP_RETURN -> tickOpenEcForTempReturn(client);
            case RETURN_TEMP_BACKPACK_TO_EC -> tickReturnTempBackpackToEc(client);
            case OPEN_RESOURCE_MENU -> tickOpenResourceMenu(client);
            case CLICK_WORLD_TELEPORT -> tickClickMenu(client, "世界传送", State.CLICK_RESOURCE_WORLD);
            case CLICK_RESOURCE_WORLD -> tickClickMenu(client, "资源世界", State.RANDOM_TELEPORT_RESOURCE);
            case RANDOM_TELEPORT_RESOURCE -> tickRandomTeleportResource(client);
            case OPEN_NETHER_MENU -> tickOpenNetherMenu(client);
            case CLICK_SAVED_POINTS -> tickClickMenu(client, "保存地点", State.CLICK_NETHER_POINT);
            case CLICK_NETHER_POINT -> tickClickNetherPoint(client);
            case START_BARITONE -> startBaritoneMine(client);
            case MINING -> tickMining(client);
            case OPEN_EC_FOR_STORE -> tickOpenEcForStore(client);
            case TAKE_BACKPACK_FROM_EC -> tickTakeBackpackFromEc(client);
            case PLACE_BACKPACK_FOR_STORE -> tickPlaceBackpackForStore(client);
            case OPEN_PLACED_BACKPACK -> tickOpenPlacedBackpack(client);
            case STORE_ORES_IN_BACKPACK -> tickStoreOresInBackpack(client);
            case PICKUP_STORED_BACKPACK -> tickPickupStoredBackpack(client);
            case OPEN_EC_FOR_RETURN -> tickOpenEcForReturn(client);
            case RETURN_BACKPACK_TO_EC -> tickReturnBackpackToEc(client);
            case REPAIR_PICKAXE -> tickRepairPickaxe(client);
            case WAIT_AFTER_DEATH -> tickWaitAfterDeath(client);
            case IDLE -> {
            }
        }
    }

    static void handleServerMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String normalized = normalize(message);
        if (normalized.contains("熔岩") || normalized.contains("lava")) {
            lastDeathMessage = message;
        } else if (normalized.contains("死亡") || normalized.contains("死了") || normalized.contains("died")) {
            lastDeathMessage = message;
        }
    }

    static void handleSessionReset(Minecraft client) {
        if (!active) {
            return;
        }
        reconnectDelayTicks = 20 * 30;
        state = State.WAIT_AFTER_DEATH;
        sendMessage(client, "检测到重连/重新进服，等待 30 秒后恢复挖矿。");
    }

    static void clearCompletedNetherPoints(Minecraft client) {
        AutoMineConfig.clearCompletedNetherPoints(client);
        sendMessage(client, "已清空下界地标完成标记。");
    }

    private static void start(Minecraft client, MineMode requestedMode) {
        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动挖矿。");
            return;
        }
        active = true;
        mode = requestedMode;
        state = State.PREPARE_PICKAXE;
        delayTicks = 0;
        timeoutTicks = TIMEOUT_TICKS;
        baritoneCooldownTicks = 0;
        reportCooldownTicks = 0;
        reconnectDelayTicks = 0;
        waitingAfterDeath = false;
        lavaDeathCount = 0;
        lastDeathMessage = "";
        currentNetherPoint = mode == MineMode.DEBRIS ? nextNetherPoint(client) : 0;
        pointStartOreCount = countTargetOre(client);
        pointStoredOreCount = 0;
        lastOreCount = pointStartOreCount;
        temporaryInventoryPrepared = false;
        resetStoreState();
        resetTempState();
        nextStateAfterTemporaryPrepare = State.IDLE;
        sendMessage(client, "自动挖矿启动：" + mode.label() + "。");
        sendFeishu(client, "自动挖矿启动：" + mode.label());
    }

    private static void stop(Minecraft client, String message, boolean notify) {
        if (client != null && client.getConnection() != null) {
            sendBaritone(client, "#stop");
        }
        active = false;
        state = State.IDLE;
        delayTicks = 0;
        timeoutTicks = 0;
        baritoneCooldownTicks = 0;
        waitingAfterDeath = false;
        resetStoreState();
        resetTempState();
        if (message != null) {
            sendMessage(client, message);
            if (notify) {
                sendFeishu(client, message);
            }
        }
    }

    private static void tickPreparePickaxe(Minecraft client) {
        if (selectBestPickaxe(client)) {
            nextStateAfterTemporaryPrepare = mode == MineMode.DIAMOND ? State.OPEN_RESOURCE_MENU : State.OPEN_NETHER_MENU;
            state = needsTemporaryInventoryPrepare(client) ? State.OPEN_EC_FOR_TEMP_PREPARE : nextStateAfterTemporaryPrepare;
            delayTicks = MENU_DELAY_TICKS;
            return;
        }
        sendMessage(client, "背包没有合格镐子，正在打开 /ec。请手动准备精准采集/效率5/耐久3镐子。");
        client.getConnection().sendCommand("ec");
        stop(client, "未找到合格镐子，自动挖矿已暂停。", true);
    }

    private static void tickOpenEcForTempPrepare(Minecraft client) {
        if (!needsTemporaryInventoryPrepare(client)) {
            temporaryInventoryPrepared = true;
            state = nextStateAfterTemporaryPrepare;
            delayTicks = MENU_DELAY_TICKS;
            return;
        }
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("ec");
        state = State.TAKE_TEMP_BACKPACK_FROM_EC;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "开挖前整理背包：正在从 /ec 取出 临时背包。");
    }

    private static void tickTakeTempBackpackFromEc(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待 /ec 末影箱超时，无法存入临时背包。", true);
            }
            return;
        }
        BackpackSlot backpack = findTempBackpackSlot(screen, tempBackpackNextIndex);
        if (backpack == null) {
            stop(client, "没有在 /ec 中找到可用的 临时背包" + tempBackpackNextIndex + "+，自动挖矿已停止。", true);
            return;
        }
        tempCurrentBackpackIndex = backpack.number;
        tempBackpackNextIndex = backpack.number + 1;
        tempAttempts++;
        clickContainerSlot(client, screen, backpack.slotIndex, 0, ContainerInput.QUICK_MOVE);
        closeScreenIfNeeded(client);
        state = State.PLACE_TEMP_BACKPACK;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickPlaceTempBackpack(Minecraft client) {
        if (!hasTempBackpackShulkerInInventory(client, tempCurrentBackpackIndex)) {
            retryNextTempBackpack(client, "没有确认拿到 临时背包" + tempCurrentBackpackIndex);
            return;
        }
        Optional<BlockPos> placePos = findShulkerPlacementPos(client);
        if (placePos.isEmpty()) {
            stop(client, "附近没有可安全放置临时潜影盒的位置，自动挖矿已停止。", true);
            return;
        }
        if (!selectOrMoveToHotbar(client, stack -> isTempBackpackShulkerStack(stack, tempCurrentBackpackIndex))) {
            retryNextTempBackpack(client, "无法把 临时背包" + tempCurrentBackpackIndex + " 移到热键栏");
            return;
        }
        storeShulkerPos = placePos.get();
        placeShulker(client, storeShulkerPos);
        state = State.OPEN_TEMP_BACKPACK;
        delayTicks = MENU_DELAY_TICKS * 2;
        timeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
    }

    private static void tickOpenTempBackpack(Minecraft client) {
        if (storeShulkerPos == null) {
            retryNextTempBackpack(client, "临时潜影盒位置丢失");
            return;
        }
        if (client.level.getBlockState(storeShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            openPlacedShulker(client, storeShulkerPos);
            state = State.STORE_TEMP_INVENTORY;
            delayTicks = MENU_DELAY_TICKS;
            timeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
            return;
        }
        if (timeoutTicks <= 0) {
            retryNextTempBackpack(client, "临时背包" + tempCurrentBackpackIndex + " 未成功放置");
        }
    }

    private static void tickStoreTempInventory(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                retryCurrentTempBackpackPickup(client, "等待 临时背包" + tempCurrentBackpackIndex + " 打开超时");
            }
            return;
        }
        tempMovedStacks = quickMoveTemporaryInventoryIntoOpenContainer(client, screen);
        closeScreenIfNeeded(client);
        state = State.PICKUP_TEMP_BACKPACK;
        storePickupTimeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
        storeMiningActive = false;
        storeMiningTicks = 0;
        delayTicks = MENU_DELAY_TICKS;
    }

    private static void tickPickupTempBackpack(Minecraft client) {
        closeScreenIfNeeded(client);
        if (storeShulkerPos == null || !(client.level.getBlockState(storeShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            clearStoreMining(client);
            if (hasTempBackpackShulkerInInventory(client, tempCurrentBackpackIndex)) {
                state = State.OPEN_EC_FOR_TEMP_RETURN;
                delayTicks = MENU_DELAY_TICKS;
                timeoutTicks = TIMEOUT_TICKS;
                return;
            }
            if (storePickupTimeoutTicks-- <= 0) {
                stop(client, "临时背包" + tempCurrentBackpackIndex + " 已挖掉但未进入背包，请手动确认掉落物。", true);
            }
            return;
        }
        if (storePickupTimeoutTicks-- <= 0) {
            clearStoreMining(client);
            stop(client, "挖回 临时背包" + tempCurrentBackpackIndex + " 超时，请手动确认潜影盒位置。", true);
            return;
        }
        if (!selectBestPickaxe(client)) {
            stop(client, "没有合格镐子挖回 临时背包" + tempCurrentBackpackIndex + "，自动挖矿已停止。", true);
            return;
        }
        mineBlock(client, storeShulkerPos);
    }

    private static void tickOpenEcForTempReturn(Minecraft client) {
        if (!hasTempBackpackShulkerInInventory(client, tempCurrentBackpackIndex)) {
            stop(client, "未检测到需要放回 /ec 的 临时背包" + tempCurrentBackpackIndex + "，自动挖矿已停止。", true);
            return;
        }
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("ec");
        state = State.RETURN_TEMP_BACKPACK_TO_EC;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReturnTempBackpackToEc(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待 /ec 放回 临时背包" + tempCurrentBackpackIndex + " 超时，自动挖矿已停止。", true);
            }
            return;
        }
        int before = countTempBackpackShulkerInInventory(client, tempCurrentBackpackIndex);
        quickMoveTempBackpackShulkerIntoOpenContainer(client, screen, tempCurrentBackpackIndex);
        closeScreenIfNeeded(client);
        int after = countTempBackpackShulkerInInventory(client, tempCurrentBackpackIndex);
        if (after >= before) {
            stop(client, "没有确认 临时背包" + tempCurrentBackpackIndex + " 放回 /ec，自动挖矿已停止。", true);
            return;
        }
        if (needsTemporaryInventoryPrepare(client) && tempMovedStacks > 0) {
            sendMessage(client, "临时背包" + tempCurrentBackpackIndex + " 已放回 /ec，继续整理剩余物品。");
            resetTempPlacedState();
            state = State.OPEN_EC_FOR_TEMP_PREPARE;
        } else if (needsTemporaryInventoryPrepare(client)) {
            retryNextTempBackpack(client, "临时背包" + tempCurrentBackpackIndex + " 没有接收物品");
        } else {
            temporaryInventoryPrepared = true;
            sendMessage(client, "开挖前背包整理完成，已存入临时背包。");
            State next = nextStateAfterTemporaryPrepare;
            resetTempState();
            state = next;
        }
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickOpenResourceMenu(Minecraft client) {
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("cd");
        state = State.CLICK_WORLD_TELEPORT;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickOpenNetherMenu(Minecraft client) {
        closeScreenIfNeeded(client);
        currentNetherPoint = nextNetherPoint(client);
        if (currentNetherPoint < 0) {
            stop(client, "下界1-100 都已标记完成，自动残骸已停止。", true);
            return;
        }
        client.getConnection().sendCommand("cd");
        state = State.CLICK_SAVED_POINTS;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickClickMenu(Minecraft client, String label, State nextState) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待菜单超时：" + label, true);
            }
            return;
        }
        int slot = findSlotByText(screen, label);
        if (slot < 0) {
            if (timeoutTicks <= 0) {
                stop(client, "没有找到菜单按钮：" + label, true);
            }
            return;
        }
        clickSlot(client, screen, slot);
        state = nextState;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickRandomTeleportResource(Minecraft client) {
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand(AutoMineConfig.get(client).resourceRandomTeleportCommand());
        state = State.START_BARITONE;
        delayTicks = 20 * 8;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "已进入资源世界流程，执行随机传送后开始挖钻石。");
    }

    private static void tickClickNetherPoint(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待保存地点菜单超时。", true);
            }
            return;
        }
        String label = "下界" + currentNetherPoint;
        int slot = findSlotByText(screen, label);
        if (slot < 0) {
            AutoMineConfig.markNetherPointDone(client, currentNetherPoint);
            sendMessage(client, "没有找到 " + label + "，标记跳过。");
            state = State.OPEN_NETHER_MENU;
            delayTicks = MENU_DELAY_TICKS;
            return;
        }
        clickSlot(client, screen, slot);
        pointStartOreCount = countTargetOre(client);
        pointStoredOreCount = 0;
        state = State.START_BARITONE;
        delayTicks = 20 * 8;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "已传送到 " + label + "，准备挖残骸。");
    }

    private static void startBaritoneMine(Minecraft client) {
        closeScreenIfNeeded(client);
        if (!selectBestPickaxe(client)) {
            stop(client, "合格镐子丢失，自动挖矿已停止。", true);
            return;
        }
        sendBaritone(client, mineCommand());
        state = State.MINING;
        baritoneCooldownTicks = BARITONE_REISSUE_TICKS;
        reportCooldownTicks = Math.max(REPORT_MIN_TICKS, AutoMineConfig.get(client).reportIntervalSeconds() * 20);
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "已发送 Baritone 指令：" + mineCommand());
    }

    private static void tickMining(Minecraft client) {
        discardJunk(client);
        int oreCount = countTargetOre(client);
        if (oreCount != lastOreCount) {
            lastOreCount = oreCount;
        }
        if (mode == MineMode.DEBRIS) {
            int gained = pointStoredOreCount + oreCount - pointStartOreCount;
            int target = AutoMineConfig.get(client).netherTargetStacksPerPoint() * 64;
            if (gained >= target) {
                AutoMineConfig.markNetherPointDone(client, currentNetherPoint);
                sendFeishu(client, "下界" + currentNetherPoint + " 已挖够 " + gained + " 个，切换下一个地标。");
                state = State.OPEN_NETHER_MENU;
                delayTicks = MENU_DELAY_TICKS;
                return;
            }
        }
        if (emptyInventorySlots(client) <= INVENTORY_FULL_THRESHOLD_EMPTY_SLOTS) {
            beginBackpackStore(client, oreCount);
            return;
        }
        if (shouldRepairPickaxe(client)) {
            sendBaritone(client, "#stop");
            state = State.REPAIR_PICKAXE;
            delayTicks = MENU_DELAY_TICKS;
            return;
        }
        if (baritoneCooldownTicks <= 0) {
            sendBaritone(client, mineCommand());
            baritoneCooldownTicks = BARITONE_REISSUE_TICKS;
        }
    }

    private static void beginBackpackStore(Minecraft client, int oreCount) {
        sendBaritone(client, "#stop");
        if (oreCount <= 0) {
            stop(client, "背包接近满，但没有检测到可存入 /ec 的目标矿物，已停止。", true);
            return;
        }
        storeOreCountBefore = oreCount;
        storeBackpackNextIndex = 1;
        storeCurrentBackpackIndex = 0;
        storeAttempts = 0;
        storeMovedAmount = 0;
        state = State.OPEN_EC_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "背包接近满，正在打开 /ec 取出背包潜影盒存矿。当前 " + oreCount + " 个。");
    }

    private static void tickOpenEcForStore(Minecraft client) {
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("ec");
        state = State.TAKE_BACKPACK_FROM_EC;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickTakeBackpackFromEc(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待 /ec 末影箱超时，自动挖矿已停止。", true);
            }
            return;
        }
        BackpackSlot backpack = findBackpackSlot(screen, storeBackpackNextIndex);
        if (backpack == null) {
            stop(client, "没有在 /ec 中找到可用的 背包" + storeBackpackNextIndex + "+，自动挖矿已停止。", true);
            return;
        }
        storeCurrentBackpackIndex = backpack.number;
        storeBackpackNextIndex = backpack.number + 1;
        storeAttempts++;
        clickContainerSlot(client, screen, backpack.slotIndex, 0, ContainerInput.QUICK_MOVE);
        closeScreenIfNeeded(client);
        state = State.PLACE_BACKPACK_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "已从 /ec 取出 背包" + storeCurrentBackpackIndex + "，准备放置存矿。");
    }

    private static void tickPlaceBackpackForStore(Minecraft client) {
        if (!hasBackpackShulkerInInventory(client, storeCurrentBackpackIndex)) {
            retryNextBackpack(client, "没有确认拿到 背包" + storeCurrentBackpackIndex);
            return;
        }
        Optional<BlockPos> placePos = findShulkerPlacementPos(client);
        if (placePos.isEmpty()) {
            stop(client, "附近没有可安全放置潜影盒的位置，自动挖矿已停止。", true);
            return;
        }
        if (!selectOrMoveToHotbar(client, stack -> isBackpackShulkerStack(stack, storeCurrentBackpackIndex))) {
            retryNextBackpack(client, "无法把 背包" + storeCurrentBackpackIndex + " 移到热键栏");
            return;
        }
        storeShulkerPos = placePos.get();
        placeShulker(client, storeShulkerPos);
        state = State.OPEN_PLACED_BACKPACK;
        delayTicks = MENU_DELAY_TICKS * 2;
        timeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
    }

    private static void tickOpenPlacedBackpack(Minecraft client) {
        if (storeShulkerPos == null) {
            retryNextBackpack(client, "潜影盒位置丢失");
            return;
        }
        if (client.level.getBlockState(storeShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            openPlacedShulker(client, storeShulkerPos);
            state = State.STORE_ORES_IN_BACKPACK;
            delayTicks = MENU_DELAY_TICKS;
            timeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
            return;
        }
        if (timeoutTicks <= 0) {
            retryNextBackpack(client, "背包" + storeCurrentBackpackIndex + " 未成功放置");
        }
    }

    private static void tickStoreOresInBackpack(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                retryCurrentPlacedBackpackPickup(client, "等待背包" + storeCurrentBackpackIndex + "打开超时");
            }
            return;
        }
        int before = countTargetOre(client);
        quickMoveTargetOresIntoOpenContainer(client, screen);
        closeScreenIfNeeded(client);
        int after = countTargetOre(client);
        storeMovedAmount = Math.max(0, before - after);
        if (storeMovedAmount > 0) {
            pointStoredOreCount += storeMovedAmount;
            lastOreCount = after;
        }
        beginPickupStoredBackpack(client);
    }

    private static void beginPickupStoredBackpack(Minecraft client) {
        state = State.PICKUP_STORED_BACKPACK;
        storePickupTimeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
        storeMiningActive = false;
        storeMiningTicks = 0;
        delayTicks = MENU_DELAY_TICKS;
    }

    private static void tickPickupStoredBackpack(Minecraft client) {
        closeScreenIfNeeded(client);
        if (storeShulkerPos == null || !(client.level.getBlockState(storeShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            clearStoreMining(client);
            if (hasBackpackShulkerInInventory(client, storeCurrentBackpackIndex)) {
                state = State.OPEN_EC_FOR_RETURN;
                delayTicks = MENU_DELAY_TICKS;
                timeoutTicks = TIMEOUT_TICKS;
                return;
            }
            if (storePickupTimeoutTicks-- <= 0) {
                stop(client, "背包" + storeCurrentBackpackIndex + " 已挖掉但未进入背包，请手动确认掉落物。", true);
            }
            return;
        }
        if (storePickupTimeoutTicks-- <= 0) {
            clearStoreMining(client);
            stop(client, "挖回 背包" + storeCurrentBackpackIndex + " 超时，请手动确认潜影盒位置。", true);
            return;
        }
        if (!selectBestPickaxe(client)) {
            stop(client, "没有合格镐子挖回 背包" + storeCurrentBackpackIndex + "，自动挖矿已停止。", true);
            return;
        }
        mineBlock(client, storeShulkerPos);
    }

    private static void tickOpenEcForReturn(Minecraft client) {
        if (!hasBackpackShulkerInInventory(client, storeCurrentBackpackIndex)) {
            if (storeMovedAmount > 0) {
                sendMessage(client, "已存入 " + storeMovedAmount + " 个矿物，但未确认 背包" + storeCurrentBackpackIndex + " 回到背包，请手动检查。");
            }
            stop(client, "未检测到需要放回 /ec 的 背包" + storeCurrentBackpackIndex + "，自动挖矿已停止。", true);
            return;
        }
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("ec");
        state = State.RETURN_BACKPACK_TO_EC;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickReturnBackpackToEc(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待 /ec 放回 背包" + storeCurrentBackpackIndex + " 超时，自动挖矿已停止。", true);
            }
            return;
        }
        int before = countBackpackShulkerInInventory(client, storeCurrentBackpackIndex);
        quickMoveBackpackShulkerIntoOpenContainer(client, screen, storeCurrentBackpackIndex);
        closeScreenIfNeeded(client);
        int after = countBackpackShulkerInInventory(client, storeCurrentBackpackIndex);
        if (after < before) {
            if (storeMovedAmount > 0) {
                sendMessage(client, "已存入 背包" + storeCurrentBackpackIndex + "：" + storeMovedAmount + " 个目标矿物，并放回 /ec。");
                resetStoreState();
                state = State.START_BARITONE;
            } else {
                sendMessage(client, "背包" + storeCurrentBackpackIndex + " 没有接收矿物，已放回 /ec，尝试下一个背包。");
                resetPlacedStoreState();
                state = State.OPEN_EC_FOR_STORE;
            }
            delayTicks = MENU_DELAY_TICKS;
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }
        stop(client, "没有确认 背包" + storeCurrentBackpackIndex + " 放回 /ec，自动挖矿已停止。", true);
    }

    private static void retryNextBackpack(Minecraft client, String reason) {
        closeScreenIfNeeded(client);
        clearStoreMining(client);
        if (storeAttempts >= MAX_BACKPACK_INDEX || storeBackpackNextIndex > MAX_BACKPACK_INDEX) {
            stop(client, reason + "，已尝试到 背包" + Math.min(storeBackpackNextIndex - 1, MAX_BACKPACK_INDEX) + "，自动挖矿已停止。", true);
            return;
        }
        sendMessage(client, reason + "，尝试下一个背包。");
        state = State.OPEN_EC_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void retryCurrentPlacedBackpackPickup(Minecraft client, String reason) {
        sendMessage(client, reason + "，先挖回并放回 /ec。");
        storeMovedAmount = 0;
        beginPickupStoredBackpack(client);
    }

    private static void tickRepairPickaxe(Minecraft client) {
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("cd");
        sendMessage(client, "镐子耐久偏低：请在 /cd -> 装备属性 -> 耐久度修复 完成修复后重新启动。");
        stop(client, "已停止等待修复装备。", true);
    }

    private static boolean handleDeath(Minecraft client) {
        if (!client.player.isDeadOrDying() && !(client.screen instanceof DeathScreen)) {
            return false;
        }
        sendBaritone(client, "#stop");
        boolean lava = normalize(lastDeathMessage).contains("熔岩") || normalize(lastDeathMessage).contains("lava");
        if (client.screen instanceof DeathScreen || client.player.isDeadOrDying()) {
            client.player.respawn();
            client.setScreen(null);
        }
        waitingAfterDeath = true;
        state = State.WAIT_AFTER_DEATH;
        reconnectDelayTicks = 20 * 8;
        if (lava && mode == MineMode.DEBRIS) {
            lavaDeathCount++;
            AutoMineConfig.markNetherPointDone(client, currentNetherPoint);
            sendFeishu(client, "自动挖矿岩浆死亡，已标记下界" + currentNetherPoint + "完成并切换下一个地标。");
        } else {
            sendFeishu(client, "自动挖矿死亡，准备 /back 后恢复。原因：" + (lastDeathMessage.isBlank() ? "未知" : lastDeathMessage));
        }
        return true;
    }

    private static void tickWaitAfterDeath(Minecraft client) {
        if (reconnectDelayTicks > 0) {
            reconnectDelayTicks--;
            return;
        }
        if (waitingAfterDeath) {
            boolean lava = normalize(lastDeathMessage).contains("熔岩") || normalize(lastDeathMessage).contains("lava");
            if (lava && mode == MineMode.DEBRIS) {
                state = State.OPEN_NETHER_MENU;
            } else {
                client.getConnection().sendCommand("back");
                state = State.START_BARITONE;
                delayTicks = 20 * 8;
            }
            waitingAfterDeath = false;
            return;
        }
        state = State.START_BARITONE;
    }

    private static void handleDisconnected(Minecraft client) {
        sendBaritone(client, "#stop");
        reconnectDelayTicks = Math.max(reconnectDelayTicks, 20 * 30);
        state = State.WAIT_AFTER_DEATH;
    }

    private static boolean selectBestPickaxe(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int bestSlot = -1;
        int bestScore = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isPickaxe(stack)) {
                continue;
            }
            int score = pickaxeScore(client, stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0 || bestScore < 100) {
            return false;
        }
        if (bestSlot < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(bestSlot);
            return true;
        }
        int hotbar = findHotbarTargetSlot(inventory);
        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, bestSlot, hotbar, ContainerInput.SWAP, client.player);
        inventory.setSelectedSlot(hotbar);
        return true;
    }

    private static int pickaxeScore(Minecraft client, ItemStack stack) {
        String text = collectText(client, stack);
        int score = 0;
        if (contains(text, "精准采集") || contains(text, "silktouch")) score += 100;
        if (contains(text, "效率v") || contains(text, "效率5") || contains(text, "efficiencyv")) score += 20;
        if (contains(text, "耐久iii") || contains(text, "耐久3") || contains(text, "unbreakingiii")) score += 10;
        score += Math.max(0, 1000 - stack.getDamageValue()) / 100;
        return score;
    }

    private static boolean shouldRepairPickaxe(Minecraft client) {
        ItemStack stack = client.player.getMainHandItem();
        return isPickaxe(stack) && stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue() <= PICKAXE_REPAIR_DAMAGE_THRESHOLD;
    }

    private static void discardJunk(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || isKeepStack(stack)) {
                continue;
            }
            if (isJunkStack(stack)) {
                client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, slot, 1, ContainerInput.THROW, client.player);
            }
        }
    }

    private static boolean isKeepStack(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return isPickaxe(stack)
                || DIAMOND_KEEP_IDS.contains(id)
                || DEBRIS_KEEP_IDS.contains(id)
                || id.contains("shulker_box")
                || id.equals("minecraft:torch")
                || id.equals("minecraft:food");
    }

    private static boolean isJunkStack(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.contains("stone")
                || id.contains("cobblestone")
                || id.contains("diorite")
                || id.contains("andesite")
                || id.contains("granite")
                || id.contains("netherrack")
                || id.contains("basalt")
                || id.contains("blackstone")
                || id.contains("tuff")
                || id.contains("gravel")
                || id.contains("dirt");
    }

    private static int countTargetOre(Minecraft client) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (isTargetOreStack(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void reportProgress(Minecraft client) {
        int count = countTargetOre(client);
        String message = "自动挖矿播报：" + mode.label() + " 当前背包目标矿物 " + count + " 个。";
        if (mode == MineMode.DEBRIS) {
            message += " 地标：下界" + currentNetherPoint + "，本地标新增 " + Math.max(0, pointStoredOreCount + count - pointStartOreCount) + " 个。";
        }
        sendMessage(client, message);
        sendFeishu(client, message);
        reportCooldownTicks = Math.max(REPORT_MIN_TICKS, AutoMineConfig.get(client).reportIntervalSeconds() * 20);
    }

    private static int nextNetherPoint(Minecraft client) {
        Set<Integer> completed = AutoMineConfig.get(client).completedNetherPoints();
        for (int i = 1; i <= 100; i++) {
            if (!completed.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private static String mineCommand() {
        return mode == MineMode.DIAMOND ? "#mine diamond_ore deepslate_diamond_ore" : "#mine ancient_debris";
    }

    private static void sendBaritone(Minecraft client, String command) {
        if (client != null && client.player != null && client.getConnection() != null) {
            client.player.connection.sendChat(command);
        }
    }

    private static void sendFeishu(Minecraft client, String message) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (config.feishuNotificationEnabled()) {
            AutoStrengthenController.sendFeishuTextMessage(config, message);
        }
    }

    private static int findSlotByText(AbstractContainerScreen<?> screen, String expectedText) {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (contains(stack.getHoverName().getString(), expectedText) || contains(collectText(Minecraft.getInstance(), stack), expectedText)) {
                return slot.index;
            }
        }
        return -1;
    }

    private static void clickSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex) {
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slotIndex, 0, ContainerInput.PICKUP, client.player);
    }

    private static void clickContainerSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex, int button, ContainerInput input) {
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slotIndex, button, input, client.player);
    }

    private static void closeScreenIfNeeded(Minecraft client) {
        if (client.screen != null && client.player != null) {
            client.player.closeContainer();
            client.setScreen(null);
        }
    }

    private static int emptyInventorySlots(Minecraft client) {
        int empty = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (client.player.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static int findHotbarTargetSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return inventory.getSelectedSlot();
    }

    private static boolean isPickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.endsWith("_pickaxe") || normalize(stack.getHoverName().getString()).contains("镐");
    }

    private static boolean isTargetOreStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return (mode == MineMode.DIAMOND && DIAMOND_KEEP_IDS.contains(id))
                || (mode == MineMode.DEBRIS && DEBRIS_KEEP_IDS.contains(id));
    }

    private static boolean isBackpackShulkerStack(ItemStack stack, int number) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!id.contains("shulker_box")) {
            return false;
        }
        String text = normalize(collectText(Minecraft.getInstance(), stack));
        return text.contains("背包" + number) && !text.contains("临时背包");
    }

    private static boolean isTempBackpackShulkerStack(ItemStack stack, int number) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!id.contains("shulker_box")) {
            return false;
        }
        String text = normalize(collectText(Minecraft.getInstance(), stack));
        return text.contains("临时背包" + number);
    }

    private static boolean hasBackpackShulkerInInventory(Minecraft client, int number) {
        return countBackpackShulkerInInventory(client, number) > 0;
    }

    private static boolean hasTempBackpackShulkerInInventory(Minecraft client, int number) {
        return countTempBackpackShulkerInInventory(client, number) > 0;
    }

    private static int countBackpackShulkerInInventory(Minecraft client, int number) {
        int count = 0;
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isBackpackShulkerStack(inventory.getItem(slot), number)) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static int countTempBackpackShulkerInInventory(Minecraft client, int number) {
        int count = 0;
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isTempBackpackShulkerStack(inventory.getItem(slot), number)) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static int quickMoveTargetOresIntoOpenContainer(Minecraft client, AbstractContainerScreen<?> screen) {
        int moved = 0;
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = playerInventoryStart; slotIndex < slots.size(); slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!isTargetOreStack(stack)) {
                continue;
            }
            moved += stack.getCount();
            clickContainerSlot(client, screen, slotIndex, 0, ContainerInput.QUICK_MOVE);
        }
        return moved;
    }

    private static int quickMoveTemporaryInventoryIntoOpenContainer(Minecraft client, AbstractContainerScreen<?> screen) {
        int moved = 0;
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = playerInventoryStart; slotIndex < slots.size(); slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            if (!shouldMoveToTemporaryBackpack(slot.getItem())) {
                continue;
            }
            clickContainerSlot(client, screen, slotIndex, 0, ContainerInput.QUICK_MOVE);
            moved++;
        }
        return moved;
    }

    private static void quickMoveBackpackShulkerIntoOpenContainer(Minecraft client, AbstractContainerScreen<?> screen, int number) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = playerInventoryStart; slotIndex < slots.size(); slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            if (!isBackpackShulkerStack(slot.getItem(), number)) {
                continue;
            }
            clickContainerSlot(client, screen, slotIndex, 0, ContainerInput.QUICK_MOVE);
            return;
        }
    }

    private static void quickMoveTempBackpackShulkerIntoOpenContainer(Minecraft client, AbstractContainerScreen<?> screen, int number) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = playerInventoryStart; slotIndex < slots.size(); slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            if (!isTempBackpackShulkerStack(slot.getItem(), number)) {
                continue;
            }
            clickContainerSlot(client, screen, slotIndex, 0, ContainerInput.QUICK_MOVE);
            return;
        }
    }

    private static BackpackSlot findBackpackSlot(AbstractContainerScreen<?> screen, int minIndex) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        BackpackSlot best = null;
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            String text = normalize(collectText(Minecraft.getInstance(), slot.getItem()));
            if (text.contains("临时背包")) {
                continue;
            }
            Matcher matcher = BACKPACK_NAME_PATTERN.matcher(text);
            while (matcher.find()) {
                int number = parsePositiveInt(matcher.group(1));
                if (number < minIndex || number > MAX_BACKPACK_INDEX) {
                    continue;
                }
                if (best == null || number < best.number) {
                    best = new BackpackSlot(slotIndex, number);
                }
            }
        }
        return best;
    }

    private static BackpackSlot findTempBackpackSlot(AbstractContainerScreen<?> screen, int minIndex) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        BackpackSlot best = null;
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }
            String text = normalize(collectText(Minecraft.getInstance(), slot.getItem()));
            Matcher matcher = TEMP_BACKPACK_NAME_PATTERN.matcher(text);
            while (matcher.find()) {
                int number = parsePositiveInt(matcher.group(1));
                if (number < minIndex || number > MAX_BACKPACK_INDEX) {
                    continue;
                }
                if (best == null || number < best.number) {
                    best = new BackpackSlot(slotIndex, number);
                }
            }
        }
        return best;
    }

    private static boolean needsTemporaryInventoryPrepare(Minecraft client) {
        if (temporaryInventoryPrepared) {
            return false;
        }
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (shouldMoveToTemporaryBackpack(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldMoveToTemporaryBackpack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (isPickaxe(stack) || id.contains("shulker_box")) {
            return false;
        }
        if (id.equals("minecraft:torch") || id.contains("food") || id.contains("bread")
                || id.contains("beef") || id.contains("porkchop") || id.contains("chicken")
                || id.contains("mutton") || id.contains("potato") || id.contains("carrot")
                || id.contains("apple")) {
            return false;
        }
        return true;
    }

    private static void retryNextTempBackpack(Minecraft client, String reason) {
        closeScreenIfNeeded(client);
        clearStoreMining(client);
        if (tempAttempts >= MAX_BACKPACK_INDEX || tempBackpackNextIndex > MAX_BACKPACK_INDEX) {
            stop(client, reason + "，已尝试到 临时背包" + Math.min(tempBackpackNextIndex - 1, MAX_BACKPACK_INDEX) + "，自动挖矿已停止。", true);
            return;
        }
        sendMessage(client, reason + "，尝试下一个临时背包。");
        resetTempPlacedState();
        state = State.OPEN_EC_FOR_TEMP_PREPARE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void retryCurrentTempBackpackPickup(Minecraft client, String reason) {
        sendMessage(client, reason + "，先挖回并放回 /ec。");
        tempMovedStacks = 0;
        state = State.PICKUP_TEMP_BACKPACK;
        storePickupTimeoutTicks = STORE_SHULKER_TIMEOUT_TICKS;
        delayTicks = MENU_DELAY_TICKS;
    }

    private static int parsePositiveInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Optional<BlockPos> findShulkerPlacementPos(Minecraft client) {
        BlockPos base = client.player.blockPosition();
        for (BlockPos candidate : List.of(
                base.above(),
                base.relative(Direction.NORTH),
                base.relative(Direction.SOUTH),
                base.relative(Direction.EAST),
                base.relative(Direction.WEST),
                base.relative(Direction.NORTH).above(),
                base.relative(Direction.SOUTH).above(),
                base.relative(Direction.EAST).above(),
                base.relative(Direction.WEST).above()
        )) {
            if (canPlaceShulkerAt(client, candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean canPlaceShulkerAt(Minecraft client, BlockPos pos) {
        if (client.player.getEyePosition().distanceToSqr(pos.getCenter()) > 25.0D) {
            return false;
        }
        if (!client.level.getBlockState(pos).isAir() || !client.level.getFluidState(pos).isEmpty()) {
            return false;
        }
        if (!client.level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return client.level.getBlockState(pos.below()).blocksMotion();
    }

    private static void placeShulker(Minecraft client, BlockPos targetPos) {
        BlockPos supportPos = targetPos.below();
        GroundMovementController.faceBlockCenter(client, supportPos);
        BlockHitResult hitResult = new BlockHitResult(supportPos.getCenter().add(0.0D, 0.5D, 0.0D), Direction.UP, supportPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void openPlacedShulker(Minecraft client, BlockPos shulkerPos) {
        GroundMovementController.faceBlockCenter(client, shulkerPos);
        Vec3 center = shulkerPos.getCenter();
        Vec3 playerPos = client.player.position();
        Direction side = Direction.getApproximateNearest(
                playerPos.x - center.x,
                playerPos.y - center.y,
                playerPos.z - center.z
        );
        if (side == Direction.UP || side == Direction.DOWN) {
            side = Direction.NORTH;
        }
        BlockHitResult hitResult = new BlockHitResult(center, side, shulkerPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean selectOrMoveToHotbar(Minecraft client, Predicate<ItemStack> predicate) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (predicate.test(inventory.getItem(slot))) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (!predicate.test(inventory.getItem(slot))) {
                continue;
            }
            int hotbarSlot = findHotbarTargetSlot(inventory);
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, slot, hotbarSlot, ContainerInput.SWAP, client.player);
            inventory.setSelectedSlot(hotbarSlot);
            return true;
        }
        return false;
    }

    private static void mineBlock(Minecraft client, BlockPos pos) {
        if (!pos.equals(storeMineTarget)) {
            storeMineTarget = pos.immutable();
            storeMiningActive = false;
            storeMiningTicks = 0;
        }
        Direction side = Direction.getApproximateNearest(
                client.player.getX() - (pos.getX() + 0.5D),
                client.player.getEyeY() - (pos.getY() + 0.5D),
                client.player.getZ() - (pos.getZ() + 0.5D)
        );
        GroundMovementController.smoothFacePoint(client, pos.getCenter(), 10.0F, 6.0F);
        if (!storeMiningActive) {
            client.gameMode.startDestroyBlock(pos, side);
            storeMiningActive = true;
        }
        client.gameMode.continueDestroyBlock(pos, side);
        client.player.swing(InteractionHand.MAIN_HAND);
        storeMiningTicks++;
        if (!(client.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock) || storeMiningTicks > STORE_SHULKER_TIMEOUT_TICKS) {
            clearStoreMining(client);
        }
    }

    private static void clearStoreMining(Minecraft client) {
        storeMineTarget = null;
        storeMiningActive = false;
        storeMiningTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyAttack.setDown(false);
        }
    }

    private static void resetPlacedStoreState() {
        storeOreCountBefore = countTargetOre(Minecraft.getInstance());
        storeCurrentBackpackIndex = 0;
        storeMovedAmount = 0;
        storePickupTimeoutTicks = 0;
        storeShulkerPos = null;
        clearStoreMining(Minecraft.getInstance());
    }

    private static void resetStoreState() {
        storeOreCountBefore = 0;
        storeBackpackNextIndex = 1;
        storeCurrentBackpackIndex = 0;
        storeAttempts = 0;
        storeMovedAmount = 0;
        storePickupTimeoutTicks = 0;
        storeShulkerPos = null;
        clearStoreMining(Minecraft.getInstance());
    }

    private static void resetTempPlacedState() {
        tempCurrentBackpackIndex = 0;
        tempMovedStacks = 0;
        storePickupTimeoutTicks = 0;
        storeShulkerPos = null;
        clearStoreMining(Minecraft.getInstance());
    }

    private static void resetTempState() {
        tempBackpackNextIndex = 1;
        tempCurrentBackpackIndex = 0;
        tempMovedStacks = 0;
        tempAttempts = 0;
        nextStateAfterTemporaryPrepare = State.IDLE;
        resetTempPlacedState();
    }

    private static String collectText(Minecraft client, ItemStack stack) {
        StringBuilder builder = new StringBuilder(stack.getHoverName().getString());
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.ADVANCED)) {
                builder.append(' ').append(line.getString());
            }
        } catch (RuntimeException ignored) {
        }
        return builder.toString();
    }

    private static boolean contains(String text, String expected) {
        return normalize(text).contains(normalize(expected));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("§.", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.getConnection() != null && client.gameMode != null;
    }

    private static void sendStatus(Minecraft client) {
        sendMessage(client, "状态 " + state + " | 启用 " + active + " | 模式 " + mode.label()
                + " | 目标矿物 " + (isClientReady(client) ? countTargetOre(client) : 0)
                + (mode == MineMode.DEBRIS ? " | 下界" + currentNetherPoint : ""));
    }

    private static void sendMessage(Minecraft client, String message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.empty()
                    .append(Component.literal(prefix).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(message).withStyle(ChatFormatting.AQUA)));
        }
    }

    private enum MineMode {
        DIAMOND("钻石"),
        DEBRIS("远古残骸");

        private final String label;

        MineMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private enum State {
        IDLE,
        PREPARE_PICKAXE,
        OPEN_EC_FOR_TEMP_PREPARE,
        TAKE_TEMP_BACKPACK_FROM_EC,
        PLACE_TEMP_BACKPACK,
        OPEN_TEMP_BACKPACK,
        STORE_TEMP_INVENTORY,
        PICKUP_TEMP_BACKPACK,
        OPEN_EC_FOR_TEMP_RETURN,
        RETURN_TEMP_BACKPACK_TO_EC,
        OPEN_RESOURCE_MENU,
        CLICK_WORLD_TELEPORT,
        CLICK_RESOURCE_WORLD,
        RANDOM_TELEPORT_RESOURCE,
        OPEN_NETHER_MENU,
        CLICK_SAVED_POINTS,
        CLICK_NETHER_POINT,
        START_BARITONE,
        MINING,
        OPEN_EC_FOR_STORE,
        TAKE_BACKPACK_FROM_EC,
        PLACE_BACKPACK_FOR_STORE,
        OPEN_PLACED_BACKPACK,
        STORE_ORES_IN_BACKPACK,
        PICKUP_STORED_BACKPACK,
        OPEN_EC_FOR_RETURN,
        RETURN_BACKPACK_TO_EC,
        REPAIR_PICKAXE,
        WAIT_AFTER_DEATH
    }

    private static final class BackpackSlot {
        private final int slotIndex;
        private final int number;

        private BackpackSlot(int slotIndex, int number) {
            this.slotIndex = slotIndex;
            this.number = number;
        }
    }
}
