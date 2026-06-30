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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

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
    private static final Pattern BACKPACK_NAME_PATTERN = Pattern.compile("背包(\\d+)");
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
    private static boolean waitingAfterDeath;
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
            case CLICK_BACKPACK_FOR_STORE -> tickClickBackpackForStore(client);
            case STORE_ORES_IN_BACKPACK -> tickStoreOresInBackpack(client);
            case VERIFY_BACKPACK_STORE -> tickVerifyBackpackStore(client);
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
        resetStoreState();
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
        if (message != null) {
            sendMessage(client, message);
            if (notify) {
                sendFeishu(client, message);
            }
        }
    }

    private static void tickPreparePickaxe(Minecraft client) {
        if (selectBestPickaxe(client)) {
            if (mode == MineMode.DIAMOND) {
                state = State.OPEN_RESOURCE_MENU;
            } else {
                state = State.OPEN_NETHER_MENU;
            }
            delayTicks = MENU_DELAY_TICKS;
            return;
        }
        sendMessage(client, "背包没有合格镐子，正在打开 /ec。请手动准备精准采集/效率5/耐久3镐子。");
        client.getConnection().sendCommand("ec");
        stop(client, "未找到合格镐子，自动挖矿已暂停。", true);
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
        state = State.OPEN_EC_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "背包接近满，正在打开 /ec 存入目标矿物。当前 " + oreCount + " 个。");
    }

    private static void tickOpenEcForStore(Minecraft client) {
        closeScreenIfNeeded(client);
        client.getConnection().sendCommand("ec");
        state = State.CLICK_BACKPACK_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickClickBackpackForStore(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                stop(client, "等待 /ec 背包菜单超时，自动挖矿已停止。", true);
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
        clickContainerSlot(client, screen, backpack.slotIndex, 0, ContainerInput.PICKUP);
        state = State.STORE_ORES_IN_BACKPACK;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickStoreOresInBackpack(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (timeoutTicks <= 0) {
                retryNextBackpack(client, "等待背包" + storeCurrentBackpackIndex + "打开超时");
            }
            return;
        }
        quickMoveTargetOresIntoOpenContainer(client, screen);
        state = State.VERIFY_BACKPACK_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void tickVerifyBackpackStore(Minecraft client) {
        int after = countTargetOre(client);
        if (after < storeOreCountBefore) {
            int moved = storeOreCountBefore - after;
            pointStoredOreCount += moved;
            lastOreCount = after;
            closeScreenIfNeeded(client);
            sendMessage(client, "已通过 /ec 存入 背包" + storeCurrentBackpackIndex + "：" + moved + " 个目标矿物，继续挖矿。");
            resetStoreState();
            state = State.START_BARITONE;
            delayTicks = MENU_DELAY_TICKS;
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }
        retryNextBackpack(client, "背包" + storeCurrentBackpackIndex + " 未接收目标矿物");
    }

    private static void retryNextBackpack(Minecraft client, String reason) {
        closeScreenIfNeeded(client);
        if (storeAttempts >= MAX_BACKPACK_INDEX || storeBackpackNextIndex > MAX_BACKPACK_INDEX) {
            stop(client, reason + "，已尝试到 背包" + Math.min(storeBackpackNextIndex - 1, MAX_BACKPACK_INDEX) + "，自动挖矿已停止。", true);
            return;
        }
        sendMessage(client, reason + "，尝试下一个背包。");
        state = State.OPEN_EC_FOR_STORE;
        delayTicks = MENU_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
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

    private static int parsePositiveInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void resetStoreState() {
        storeOreCountBefore = 0;
        storeBackpackNextIndex = 1;
        storeCurrentBackpackIndex = 0;
        storeAttempts = 0;
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
        CLICK_BACKPACK_FOR_STORE,
        STORE_ORES_IN_BACKPACK,
        VERIFY_BACKPACK_STORE,
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
