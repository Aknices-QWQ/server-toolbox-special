package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class AutoBoatDispenserController {
    private static final String COMMAND = "autoboat1";
    private static final String CHAT_MODULE = "自动补船";
    private static final int DISPENSER_SLOT_COUNT = 9;
    private static final int SCAN_RADIUS = 5;
    private static final int VERTICAL_SCAN_RADIUS = 3;
    private static final int OPEN_TIMEOUT_TICKS = 20 * 4;
    private static final int TARGET_COOLDOWN_TICKS = 20 * 2;
    private static final int PROGRESS_MESSAGE_TICKS = 20 * 8;
    private static final double REACH_DISTANCE_SQUARED = 4.5D * 4.5D;

    private static boolean enabled;
    private static State state = State.IDLE;
    private static int delayTicks;
    private static int timeoutTicks;
    private static int progressMessageCooldownTicks;
    private static long tickCounter;
    private static long filledCount;
    private static long skippedHasBoatCount;
    private static long skippedFullCount;
    private static BlockPos activeTarget;
    private static final Map<Long, Long> cooldownUntilTick = new HashMap<>();

    private AutoBoatDispenserController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal(COMMAND)
                .executes(command -> {
                    toggle(command.getSource().getClient());
                    return 1;
                })
                .then(ClientCommands.literal("start")
                        .executes(command -> {
                            start(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "自动补船已停止。");
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        tickCounter++;
        pruneCooldowns();
        if (!enabled) {
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoBoat()) {
            stop(client, "自动补船已被全局设置屏蔽。");
            return;
        }

        if (progressMessageCooldownTicks > 0) {
            progressMessageCooldownTicks--;
        }

        if (!isClientReady(client)) {
            clearActive();
            return;
        }

        if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (state) {
            case IDLE -> tickIdle(client);
            case WAITING_FOR_SCREEN -> tickWaitingForScreen(client);
        }
    }

    static void handleSessionReset(Minecraft client) {
        if (enabled) {
            stop(client, null);
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    static String getStateName() {
        return state.name();
    }

    static void setEnabled(Minecraft client, boolean value) {
        if (enabled == value) {
            return;
        }

        if (value) {
            start(client);
        } else {
            stop(client, "自动补船已停止。");
        }
    }

    private static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, "自动补船已停止。");
        } else {
            start(client);
        }
    }

    private static void start(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoBoat()) {
            sendMessage(client, "自动补船已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        AutoNetherWartController.handleSessionReset(client);
        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动补船。");
            return;
        }

        enabled = true;
        state = State.IDLE;
        delayTicks = 0;
        timeoutTicks = 0;
        progressMessageCooldownTicks = 0;
        filledCount = 0L;
        skippedHasBoatCount = 0L;
        skippedFullCount = 0L;
        clearActive();
        sendMessage(client, "自动补船已启动：附近发射器里已有船会跳过，没有船就补 1 艘。");
    }

    private static void stop(Minecraft client, String message) {
        closeContainer(client);
        enabled = false;
        state = State.IDLE;
        delayTicks = 0;
        timeoutTicks = 0;
        progressMessageCooldownTicks = 0;
        clearActive();
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static void tickIdle(Minecraft client) {
        Optional<BlockPos> target = findNearestDispenser(client);
        if (target.isEmpty()) {
            sendThrottledProgress(client, "附近没有够得到的发射器。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        activeTarget = target.get();
        openDispenser(client, activeTarget);
        state = State.WAITING_FOR_SCREEN;
        timeoutTicks = OPEN_TIMEOUT_TICKS;
        delayTicks = 0;
    }

    private static void tickWaitingForScreen(Minecraft client) {
        if (timeoutTicks-- <= 0) {
            cooldown(activeTarget, TARGET_COOLDOWN_TICKS);
            closeContainer(client);
            sendThrottledProgress(client, "发射器界面没有及时打开，先跳过这个目标。", PROGRESS_MESSAGE_TICKS);
            clearActive();
            state = State.IDLE;
            return;
        }

        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !(screen.getMenu() instanceof DispenserMenu)) {
            return;
        }

        if (hasBoatInDispenser(screen)) {
            skippedHasBoatCount++;
            finishTarget(client, TARGET_COOLDOWN_TICKS);
            return;
        }

        int dispenserSlot = findEmptyDispenserSlot(screen);
        if (dispenserSlot < 0) {
            skippedFullCount++;
            finishTarget(client, TARGET_COOLDOWN_TICKS);
            return;
        }

        int boatSlot = findBoatInventorySlot(screen);
        if (boatSlot < 0) {
            stop(client, "背包里没有船，自动补船已停止。");
            return;
        }

        placeOneBoatNow(client, boatSlot, dispenserSlot);
        filledCount++;
        finishTarget(client, TARGET_COOLDOWN_TICKS);
    }

    private static Optional<BlockPos> findNearestDispenser(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int y = -VERTICAL_SCAN_RADIUS; y <= VERTICAL_SCAN_RADIUS; y++) {
            for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (isCoolingDown(pos)) {
                        continue;
                    }

                    BlockState state = client.level.getBlockState(pos);
                    if (!state.is(Blocks.DISPENSER)) {
                        continue;
                    }

                    double distance = client.player.getEyePosition().distanceToSqr(pos.getCenter());
                    if (distance > REACH_DISTANCE_SQUARED || distance >= bestDistance) {
                        continue;
                    }

                    best = pos.immutable();
                    bestDistance = distance;
                }
            }
        }

        return Optional.ofNullable(best);
    }

    private static void openDispenser(Minecraft client, BlockPos pos) {
        Vec3 hit = pos.getCenter();
        Direction side = Direction.getApproximateNearest(
                client.player.getX() - hit.x,
                client.player.getEyeY() - hit.y,
                client.player.getZ() - hit.z
        );
        BlockHitResult hitResult = new BlockHitResult(hit, side, pos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean hasBoatInDispenser(AbstractContainerScreen<?> screen) {
        for (int slotIndex = 0; slotIndex < Math.min(DISPENSER_SLOT_COUNT, screen.getMenu().slots.size()); slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot != null && slot.hasItem() && isBoatItem(slot.getItem())) {
                return true;
            }
        }
        return false;
    }

    private static int findEmptyDispenserSlot(AbstractContainerScreen<?> screen) {
        for (int slotIndex = 0; slotIndex < Math.min(DISPENSER_SLOT_COUNT, screen.getMenu().slots.size()); slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot != null && !slot.hasItem()) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static int findBoatInventorySlot(AbstractContainerScreen<?> screen) {
        for (int slotIndex = DISPENSER_SLOT_COUNT; slotIndex < screen.getMenu().slots.size(); slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot != null && slot.hasItem() && isBoatItem(slot.getItem())) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean isBoatItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.endsWith("_boat") || id.endsWith("_raft");
    }

    private static void clickSlot(Minecraft client, int slotIndex, int button) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || slotIndex < 0) {
            return;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotIndex,
                button,
                ContainerInput.PICKUP,
                client.player
        );
    }

    private static void placeOneBoatNow(Minecraft client, int boatSlot, int dispenserSlot) {
        clickSlot(client, boatSlot, 0);
        clickSlot(client, dispenserSlot, 1);
        clickSlot(client, boatSlot, 0);
    }

    private static void finishTarget(Minecraft client, int cooldownTicks) {
        cooldown(activeTarget, cooldownTicks);
        closeContainer(client);
        clearActive();
        state = State.IDLE;
        delayTicks = 0;
    }

    private static void closeContainer(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
            client.setScreen(null);
        }
    }

    private static void clearActive() {
        activeTarget = null;
    }

    private static void cooldown(BlockPos pos, int ticks) {
        if (pos != null) {
            cooldownUntilTick.put(pos.asLong(), tickCounter + ticks);
        }
    }

    private static boolean isCoolingDown(BlockPos pos) {
        Long until = cooldownUntilTick.get(pos.asLong());
        return until != null && until > tickCounter;
    }

    private static void pruneCooldowns() {
        cooldownUntilTick.entrySet().removeIf(entry -> entry.getValue() <= tickCounter);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.gameMode != null && client.getConnection() != null;
    }

    private static void sendStatus(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("启用 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(enabled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 状态 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(state.name()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" | 目标 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(activeTarget == null ? "-" : formatBlockPos(activeTarget)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 已补 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(filledCount)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 已有船跳过 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(skippedHasBoatCount)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 满格跳过 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(skippedFullCount)).withStyle(ChatFormatting.RED));
        sendMessage(client, message);
    }

    private static void sendThrottledProgress(Minecraft client, String message, int cooldown) {
        if (progressMessageCooldownTicks > 0) {
            return;
        }

        progressMessageCooldownTicks = cooldown;
        sendMessage(client, message);
    }

    private static void sendMessage(Minecraft client, String message) {
        sendMessage(client, Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    private static void sendMessage(Minecraft client, Component message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message.getString());
        if (client != null && client.player != null) {
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

    private enum State {
        IDLE,
        WAITING_FOR_SCREEN
    }
}
