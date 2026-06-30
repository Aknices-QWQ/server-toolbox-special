package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class AutoVillagerTradeController {
    private static final String COMMAND = "autovtrade";
    private static final String CHAT_MODULE = "自动村民交易";
    private static final int TRADE_DELAY_TICKS = 8;
    private static final int INTERACT_INTERVAL_TICKS = 18;
    private static final int PROGRESS_MESSAGE_TICKS = 20 * 8;
    private static final int COLLISION_REVERSE_TICKS = 8;
    private static final int REACH_DISTANCE_TICKS = 4;
    private static final double VILLAGER_SCAN_RANGE = 4.5D;
    private static final double INTERACT_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final int RESULT_SLOT_INDEX = 2;

    private static boolean enabled;
    private static boolean movingRight = true;
    private static int tradeDelayTicks;
    private static int interactCooldownTicks;
    private static int progressMessageCooldownTicks;
    private static int collisionTicks;
    private static float travelYaw;
    private static long pumpkinTrades;
    private static long glassTrades;

    private AutoVillagerTradeController() {
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
                            stop(command.getSource().getClient(), "自动村民交易已停止。");
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        if (!enabled) {
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoVillagerTrade()) {
            stop(client, "自动村民交易已被全局设置屏蔽。");
            return;
        }

        if (tradeDelayTicks > 0) {
            tradeDelayTicks--;
        }
        if (interactCooldownTicks > 0) {
            interactCooldownTicks--;
        }
        if (progressMessageCooldownTicks > 0) {
            progressMessageCooldownTicks--;
        }

        if (!isClientReady(client)) {
            releaseMovement(client);
            return;
        }

        if (client.screen instanceof ChatScreen) {
            releaseMovement(client);
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof MerchantMenu menu) {
            releaseMovement(client);
            tickMerchantScreen(client, screen, menu);
            return;
        }

        if (client.screen != null) {
            releaseMovement(client);
            return;
        }

        tickWalkAndInteract(client);
    }

    static void handleSessionReset(Minecraft client) {
        if (enabled) {
            stop(client, null);
        }
    }

    private static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, "自动村民交易已停止。");
        } else {
            start(client);
        }
    }

    private static void start(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoVillagerTrade()) {
            sendMessage(client, "自动村民交易已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动村民交易。");
            return;
        }

        enabled = true;
        movingRight = true;
        tradeDelayTicks = 0;
        interactCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        collisionTicks = 0;
        travelYaw = client.player.getYRot();
        pumpkinTrades = 0L;
        glassTrades = 0L;
        sendMessage(client, "自动村民交易已启动：有南瓜就换绿宝石，再用绿宝石换玻璃，并左右来回扫村民。");
    }

    private static void stop(Minecraft client, String message) {
        closeMerchantScreen(client);
        releaseMovement(client);
        enabled = false;
        tradeDelayTicks = 0;
        interactCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        collisionTicks = 0;
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static void tickMerchantScreen(Minecraft client, AbstractContainerScreen<?> screen, MerchantMenu menu) {
        if (tradeDelayTicks > 0) {
            return;
        }

        TradeTarget target = findTrade(menu, Items.PUMPKIN, Items.EMERALD)
                .or(() -> findTrade(menu, Items.EMERALD, Items.GLASS))
                .orElse(null);
        if (target == null) {
            closeMerchantScreen(client);
            tradeDelayTicks = TRADE_DELAY_TICKS;
            return;
        }

        if (!hasEnoughInventoryItems(client, target.payment(), target.paymentCount())) {
            closeMerchantScreen(client);
            tradeDelayTicks = TRADE_DELAY_TICKS;
            return;
        }

        selectTrade(client, menu, target.offerIndex());
        quickMoveResult(client, screen);
        if (target.result().is(Items.EMERALD)) {
            pumpkinTrades++;
        } else if (target.result().is(Items.GLASS)) {
            glassTrades++;
        }
        tradeDelayTicks = TRADE_DELAY_TICKS;
    }

    private static Optional<TradeTarget> findTrade(MerchantMenu menu, net.minecraft.world.item.Item payment,
                                                   net.minecraft.world.item.Item result) {
        MerchantOffers offers = menu.getOffers();
        for (int index = 0; index < offers.size(); index++) {
            MerchantOffer offer = offers.get(index);
            if (offer == null || offer.isOutOfStock()) {
                continue;
            }

            ItemStack costA = offer.getCostA();
            ItemStack costB = offer.getCostB();
            ItemStack tradeResult = offer.getResult();
            if (tradeResult.is(result) && paysOnlyWith(costA, costB, payment)) {
                return Optional.of(new TradeTarget(index, payment, totalPaymentCount(costA, costB, payment), tradeResult));
            }
        }
        return Optional.empty();
    }

    private static boolean paysOnlyWith(ItemStack costA, ItemStack costB, net.minecraft.world.item.Item payment) {
        boolean hasPayment = false;
        if (!costA.isEmpty()) {
            if (!costA.is(payment)) {
                return false;
            }
            hasPayment = true;
        }
        if (!costB.isEmpty()) {
            if (!costB.is(payment)) {
                return false;
            }
            hasPayment = true;
        }
        return hasPayment;
    }

    private static int totalPaymentCount(ItemStack costA, ItemStack costB, net.minecraft.world.item.Item payment) {
        int count = 0;
        if (!costA.isEmpty() && costA.is(payment)) {
            count += costA.getCount();
        }
        if (!costB.isEmpty() && costB.is(payment)) {
            count += costB.getCount();
        }
        return count;
    }

    private static boolean hasEnoughInventoryItems(Minecraft client, net.minecraft.world.item.Item item, int required) {
        int count = 0;
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
                if (count >= required) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void selectTrade(Minecraft client, MerchantMenu menu, int offerIndex) {
        menu.setSelectionHint(offerIndex);
        menu.tryMoveItems(offerIndex);
        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSelectTradePacket(offerIndex));
        }
    }

    private static void quickMoveResult(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client.gameMode == null || client.player == null || screen.getMenu().slots.size() <= RESULT_SLOT_INDEX) {
            return;
        }

        Slot resultSlot = screen.getMenu().slots.get(RESULT_SLOT_INDEX);
        if (resultSlot == null || !resultSlot.hasItem()) {
            return;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                RESULT_SLOT_INDEX,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );
    }

    private static void tickWalkAndInteract(Minecraft client) {
        applySideWalk(client);
        handleWallBounce(client);

        if (interactCooldownTicks > 0) {
            return;
        }

        Optional<Entity> villager = findNearestVillager(client);
        if (villager.isEmpty()) {
            sendThrottledProgress(client, "附近没有可交互的村民，继续左右搜索。", PROGRESS_MESSAGE_TICKS);
            interactCooldownTicks = INTERACT_INTERVAL_TICKS;
            return;
        }

        Entity target = villager.get();
        if (client.player.distanceToSqr(target) <= INTERACT_DISTANCE_SQUARED) {
            client.gameMode.interact(client.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
            client.player.swing(InteractionHand.MAIN_HAND);
            interactCooldownTicks = INTERACT_INTERVAL_TICKS;
        }
    }

    private static Optional<Entity> findNearestVillager(Minecraft client) {
        LocalPlayer player = client.player;
        AABB box = player.getBoundingBox().inflate(VILLAGER_SCAN_RANGE, REACH_DISTANCE_TICKS, VILLAGER_SCAN_RANGE);
        List<Entity> entities = client.level.getEntities(player, box, entity ->
                EntitySelector.ENTITY_STILL_ALIVE.test(entity)
                        && (entity.getType() == EntityType.VILLAGER || entity.getType() == EntityType.WANDERING_TRADER)
                        && player.distanceToSqr(entity) <= INTERACT_DISTANCE_SQUARED);
        return entities.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr));
    }

    private static void applySideWalk(Minecraft client) {
        client.player.setYRot(travelYaw);
        client.player.setYHeadRot(travelYaw);
        client.player.setYBodyRot(travelYaw);
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(!movingRight);
        client.options.keyRight.setDown(movingRight);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void handleWallBounce(Minecraft client) {
        if (client.player.horizontalCollision) {
            collisionTicks++;
            if (collisionTicks >= COLLISION_REVERSE_TICKS) {
                movingRight = !movingRight;
                collisionTicks = 0;
            }
        } else {
            collisionTicks = 0;
        }
    }

    private static void closeMerchantScreen(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof MerchantMenu) {
            client.player.closeContainer();
            client.setScreen(null);
        }
    }

    private static void releaseMovement(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.gameMode != null && client.getConnection() != null;
    }

    private static void sendStatus(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("启用 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(enabled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 方向 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(movingRight ? "右" : "左").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" | 南瓜换绿宝石 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(pumpkinTrades)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 绿宝石换玻璃 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(glassTrades)).withStyle(ChatFormatting.GREEN));
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

    private record TradeTarget(int offerIndex, net.minecraft.world.item.Item payment, int paymentCount, ItemStack result) {
    }
}
