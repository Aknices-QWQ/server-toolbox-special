package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class AutoChunkMinerController {
    private static final String COMMAND = "autochunk";
    private static final String CHAT_MODULE = "自动挖区块";
    private static final int ACTION_DELAY_TICKS = 3;
    private static final int TARGET_RESCAN_TICKS = 5;
    private static final int MINE_PREPARE_TICKS = 1;
    private static final int BRIDGE_PLACE_DELAY_TICKS = 2;
    private static final int BRIDGE_PREPARE_TICKS = 2;
    private static final int BRIDGE_PLACE_COOLDOWN_TICKS = 6;
    private static final int PROGRESS_MESSAGE_TICKS = 20 * 5;
    private static final double REACH_DISTANCE_SQUARED = 4.6D * 4.6D;
    private static final double BRIDGE_REACH_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.7D * 0.7D;

    private static boolean enabled;
    private static int chunkX;
    private static int chunkZ;
    private static int minX;
    private static int maxX;
    private static int minZ;
    private static int maxZ;
    private static int startY;
    private static int bottomY;
    private static int layerTopY;
    private static int layerBottomY;
    private static int waypointIndex;
    private static int delayTicks;
    private static int targetRescanTicks;
    private static int bridgePrepareTicks;
    private static int bridgePlaceCooldownTicks;
    private static int progressMessageCooldownTicks;
    private static long minedAttempts;
    private static BlockPos activeTarget;
    private static BlockPos miningTarget;
    private static int miningPrepareTicks;
    private static BlockPos pendingBridgePlacePos;
    private static BlockPos pendingBridgeAnchorPos;
    private static boolean miningActive;
    private static final Set<Long> bridgeBlocks = new HashSet<>();

    private AutoChunkMinerController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(COMMAND)
                .executes(command -> {
                    toggle(command.getSource().getClient());
                    return 1;
                })
                .then(ClientCommandManager.literal("start")
                        .executes(command -> {
                            start(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "自动挖区块已停止。");
                            return 1;
                        }))
                .then(ClientCommandManager.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        if (!enabled) {
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoChunk()) {
            stop(client, "自动挖区块已被全局设置屏蔽。");
            return;
        }

        if (progressMessageCooldownTicks > 0) {
            progressMessageCooldownTicks--;
        }
        if (bridgePlaceCooldownTicks > 0) {
            bridgePlaceCooldownTicks--;
        }

        if (!isClientReady(client)) {
            releaseMovementKeys(client);
            clearPendingBridge();
            return;
        }

        if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
            releaseMovementKeys(client);
            clearPendingBridge();
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (!ensurePickaxeSelected(client)) {
            releaseMovementKeys(client);
            sendThrottledProgress(client, "快捷栏里没有镐子，自动挖区块暂停。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        if (handlePendingBridge(client)) {
            return;
        }

        if (activeTarget != null) {
            if (!isMineable(client, activeTarget)) {
                finishMiningTarget(client);
            } else if (!isReachableMineTarget(client, activeTarget)) {
                stopHoldingAttack(client);
                activeTarget = null;
                targetRescanTicks = 0;
            } else {
                releaseWalkKeys(client);
                holdMineBlock(client, activeTarget);
                targetRescanTicks = TARGET_RESCAN_TICKS;
                sendThrottledProgress(client, "正在挖脚下区块：" + formatChunk() + "，目标 " + formatBlockPos(activeTarget) + "。", PROGRESS_MESSAGE_TICKS);
                return;
            }
        }

        if (targetRescanTicks > 0) {
            targetRescanTicks--;
        }

        if (activeTarget == null && targetRescanTicks <= 0) {
            stopHoldingAttack(client);
            activeTarget = findReachableMineTarget(client).orElse(null);
            targetRescanTicks = TARGET_RESCAN_TICKS;
        }

        if (activeTarget != null) {
            releaseWalkKeys(client);
            holdMineBlock(client, activeTarget);
            sendThrottledProgress(client, "正在挖脚下区块：" + formatChunk() + "，目标 " + formatBlockPos(activeTarget) + "。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        if (!hasMineableBlocksInCurrentLayer(client)) {
            if (!advanceToNextLayer(client)) {
                stop(client, "脚下区块从启动高度往下已经没有可挖方块了。");
                return;
            }
            activeTarget = null;
            sendMessage(client, "当前层已清完，下降到下一层：" + formatLayer() + "。");
            delayTicks = ACTION_DELAY_TICKS;
            return;
        }

        moveToNextWaypoint(client);
        stopHoldingAttack(client);
        sendThrottledProgress(client, "附近够不到方块，正在区块内蛇形走位找下一处可挖点。", PROGRESS_MESSAGE_TICKS);
    }

    static void handleSessionReset(Minecraft client) {
        if (enabled) {
            stop(client, null);
        }
    }

    private static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, "自动挖区块已停止。");
        } else {
            start(client);
        }
    }

    private static void start(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoChunk()) {
            sendMessage(client, "自动挖区块已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动挖区块。");
            return;
        }

        LocalPlayer player = client.player;
        BlockPos origin = player.blockPosition();
        chunkX = Math.floorDiv(origin.getX(), 16);
        chunkZ = Math.floorDiv(origin.getZ(), 16);
        minX = chunkX * 16;
        minZ = chunkZ * 16;
        maxX = minX + 15;
        maxZ = minZ + 15;
        startY = origin.getY();
        bottomY = client.level.getMinY();
        layerTopY = startY;
        layerBottomY = startY;
        waypointIndex = localIndex(origin);
        delayTicks = 0;
        targetRescanTicks = 0;
        bridgePrepareTicks = 0;
        bridgePlaceCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        minedAttempts = 0L;
        activeTarget = null;
        miningTarget = null;
        miningPrepareTicks = 0;
        pendingBridgePlacePos = null;
        pendingBridgeAnchorPos = null;
        miningActive = false;
        bridgeBlocks.clear();
        enabled = true;
        sendMessage(client, "自动挖区块启动：锁定脚下区块 " + formatChunk()
                + "，范围 X " + minX + ".." + maxX + "，Z " + minZ + ".." + maxZ
                + "，一层一层往下挖，当前 " + formatLayer() + "。");
    }

    private static void stop(Minecraft client, String message) {
        releaseMovementKeys(client);
        enabled = false;
        activeTarget = null;
        miningTarget = null;
        miningPrepareTicks = 0;
        pendingBridgePlacePos = null;
        pendingBridgeAnchorPos = null;
        miningActive = false;
        bridgeBlocks.clear();
        delayTicks = 0;
        targetRescanTicks = 0;
        bridgePrepareTicks = 0;
        bridgePlaceCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static void sendStatus(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("启用 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(enabled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 区块 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(enabled ? formatChunk() : "-").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" | 起始Y ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(enabled ? Integer.toString(startY) : "-").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 当前层 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(enabled ? formatLayer() : "-").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 目标 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(activeTarget == null ? "-" : formatBlockPos(activeTarget)).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" | 当前层剩余 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(enabled && isClientReady(client) ? Integer.toString(countMineableBlocksInCurrentLayer(client)) : "-").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 挖掘尝试 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(minedAttempts)).withStyle(ChatFormatting.YELLOW));
        sendMessage(client, message);
    }

    private static Optional<BlockPos> findReachableMineTarget(Minecraft client) {
        LocalPlayer player = client.player;
        BlockPos playerPos = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = layerTopY; y >= layerBottomY; y--) {
            for (int x = Math.max(minX, playerPos.getX() - 5); x <= Math.min(maxX, playerPos.getX() + 5); x++) {
                for (int z = Math.max(minZ, playerPos.getZ() - 5); z <= Math.min(maxZ, playerPos.getZ() + 5); z++) {
                    mutable.set(x, y, z);
                    if (!isMineable(client, mutable)) {
                        continue;
                    }
                    Vec3 center = mutable.getCenter();
                    double distanceSqr = eye.distanceToSqr(center);
                    if (distanceSqr > REACH_DISTANCE_SQUARED) {
                        continue;
                    }
                    double score = distanceSqr
                            + Math.abs(y - playerPos.getY()) * 0.18D
                            + horizontalDistanceSqr(center, player.position()) * 0.10D;
                    if (score < bestScore) {
                        best = mutable.immutable();
                        bestScore = score;
                    }
                }
            }
        }

        return Optional.ofNullable(best);
    }

    private static boolean isReachableMineTarget(Minecraft client, BlockPos pos) {
        return isInsideLockedChunk(pos)
                && pos.getY() <= layerTopY
                && pos.getY() >= layerBottomY
                && isMineable(client, pos)
                && client.player.getEyePosition().distanceToSqr(pos.getCenter()) <= REACH_DISTANCE_SQUARED;
    }

    private static boolean hasMineableBlocksInCurrentLayer(Minecraft client) {
        return countMineableBlocksInCurrentLayer(client) > 0;
    }

    private static int countMineableBlocksInCurrentLayer(Minecraft client) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int y = layerTopY; y >= layerBottomY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (isMineable(client, mutable)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean advanceToNextLayer(Minecraft client) {
        int nextLayer = layerTopY - 1;
        while (nextLayer >= bottomY) {
            layerTopY = nextLayer;
            layerBottomY = nextLayer;
            if (hasMineableBlocksInCurrentLayer(client)) {
                return true;
            }
            nextLayer--;
        }
        return false;
    }

    private static boolean isMineable(Minecraft client, BlockPos pos) {
        if (!isInsideLockedChunk(pos)) {
            return false;
        }
        if (bridgeBlocks.contains(pos.asLong())) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || !client.level.getFluidState(pos).isEmpty()) {
            return false;
        }

        Block block = state.getBlock();
        return block != Blocks.BEDROCK
                && block != Blocks.END_PORTAL
                && block != Blocks.END_PORTAL_FRAME
                && block != Blocks.BARRIER
                && block != Blocks.COMMAND_BLOCK
                && block != Blocks.CHAIN_COMMAND_BLOCK
                && block != Blocks.REPEATING_COMMAND_BLOCK;
    }

    private static void holdMineBlock(Minecraft client, BlockPos pos) {
        facePoint(client, pos.getCenter());
        if (!pos.equals(miningTarget)) {
            miningTarget = pos.immutable();
            miningPrepareTicks = 0;
            miningActive = false;
        }

        if (miningPrepareTicks < MINE_PREPARE_TICKS) {
            miningPrepareTicks++;
            return;
        }

        Direction side = Direction.getApproximateNearest(
                client.player.getX() - (pos.getX() + 0.5D),
                client.player.getEyeY() - (pos.getY() + 0.5D),
                client.player.getZ() - (pos.getZ() + 0.5D)
        );

        if (!miningActive) {
            client.gameMode.startDestroyBlock(pos, side);
            miningActive = true;
        }
        client.gameMode.continueDestroyBlock(pos, side);
        client.player.swing(InteractionHand.MAIN_HAND);
        minedAttempts++;
    }

    private static void finishMiningTarget(Minecraft client) {
        stopHoldingAttack(client);
        activeTarget = null;
        targetRescanTicks = 0;
    }

    private static void stopHoldingAttack(Minecraft client) {
        miningActive = false;
        miningTarget = null;
        miningPrepareTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyAttack.setDown(false);
        }
    }

    private static void moveToNextWaypoint(Minecraft client) {
        LocalPlayer player = client.player;
        Vec3 target = waypointCenter(waypointIndex, player.getY());
        if (player.position().distanceToSqr(target) <= WAYPOINT_REACHED_DISTANCE_SQUARED) {
            waypointIndex = (waypointIndex + 1) % 256;
            target = waypointCenter(waypointIndex, player.getY());
        }

        BridgePlan bridgePlan = planBridgeToward(client, target);
        if (bridgePlan == BridgePlan.QUEUED) {
            releaseWalkKeys(client);
            sendThrottledProgress(client, "前方不可达，准备自动补一格路。", PROGRESS_MESSAGE_TICKS);
            return;
        }
        if (bridgePlan == BridgePlan.BLOCKED) {
            releaseWalkKeys(client);
            sendThrottledProgress(client, "前方不可达，但当前没有可用方块或支撑点，已停止向前硬走。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        GroundMovementController.walkToward(client, target, 0.15D, 0.0D, false, false, 8.0F);
    }

    private static boolean handlePendingBridge(Minecraft client) {
        if (pendingBridgePlacePos == null || pendingBridgeAnchorPos == null) {
            return false;
        }

        releaseWalkKeys(client);
        stopHoldingAttack(client);

        if (bridgePlaceCooldownTicks > 0) {
            return true;
        }

        if (!canPlaceBridgeAt(client, pendingBridgePlacePos)
                || !client.level.getBlockState(pendingBridgeAnchorPos).blocksMotion()
                || client.player.getEyePosition().distanceToSqr(pendingBridgeAnchorPos.getCenter()) > BRIDGE_REACH_DISTANCE_SQUARED
                || !selectBridgeBlock(client)) {
            clearPendingBridge();
            return false;
        }

        Vec3 hit = bridgeHitPoint(pendingBridgePlacePos, pendingBridgeAnchorPos);
        smoothFacePoint(client, hit, 7.0F, 4.0F);
        if (bridgePrepareTicks < BRIDGE_PREPARE_TICKS) {
            bridgePrepareTicks++;
            return true;
        }

        placeBridgeBlock(client, pendingBridgePlacePos, pendingBridgeAnchorPos);
        bridgeBlocks.add(pendingBridgePlacePos.asLong());
        bridgePlaceCooldownTicks = BRIDGE_PLACE_COOLDOWN_TICKS;
        delayTicks = BRIDGE_PLACE_DELAY_TICKS;
        sendThrottledProgress(client, "前方不可达，已自动补一格路。", PROGRESS_MESSAGE_TICKS);
        clearPendingBridge();
        return true;
    }

    private static void clearPendingBridge() {
        pendingBridgePlacePos = null;
        pendingBridgeAnchorPos = null;
        bridgePrepareTicks = 0;
    }

    private static BridgePlan planBridgeToward(Minecraft client, Vec3 target) {
        if (bridgePlaceCooldownTicks > 0 || pendingBridgePlacePos != null) {
            return BridgePlan.BLOCKED;
        }

        LocalPlayer player = client.player;
        Vec3 delta = new Vec3(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (delta.lengthSqr() < 0.08D) {
            return BridgePlan.NONE;
        }

        Vec3 step = player.position().add(delta.normalize().scale(0.85D));
        BlockPos standPos = BlockPos.containing(step.x, player.getY(), step.z);
        if (!isInsideLockedChunk(standPos) || isSafeStandPosition(client, standPos)) {
            return BridgePlan.NONE;
        }

        BlockPos supportPos = standPos.below();
        if (!canPlaceBridgeAt(client, supportPos) || !selectBridgeBlock(client)) {
            return BridgePlan.BLOCKED;
        }

        Optional<BlockPos> anchorPos = findBridgeAnchor(client, supportPos);
        if (anchorPos.isEmpty()) {
            return BridgePlan.BLOCKED;
        }

        pendingBridgePlacePos = supportPos;
        pendingBridgeAnchorPos = anchorPos.get();
        bridgePrepareTicks = 0;
        return BridgePlan.QUEUED;
    }

    private static boolean isSafeStandPosition(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos.below()).blocksMotion()
                && client.level.getFluidState(pos.below()).isEmpty()
                && !client.level.getBlockState(pos).blocksMotion()
                && !client.level.getBlockState(pos.above()).blocksMotion()
                && client.level.getFluidState(pos).isEmpty()
                && client.level.getFluidState(pos.above()).isEmpty();
    }

    private static boolean canPlaceBridgeAt(Minecraft client, BlockPos pos) {
        return isInsideLockedChunk(pos)
                && client.level.getBlockState(pos).isAir()
                && client.level.getFluidState(pos).isEmpty()
                && !client.level.getBlockState(pos.above()).blocksMotion();
    }

    private static Optional<BlockPos> findBridgeAnchor(Minecraft client, BlockPos placePos) {
        for (Direction direction : Direction.values()) {
            BlockPos anchor = placePos.relative(direction);
            if (client.level.getBlockState(anchor).blocksMotion() && client.level.getFluidState(anchor).isEmpty()) {
                return Optional.of(anchor);
            }
        }
        return Optional.empty();
    }

    private static boolean selectBridgeBlock(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isBridgeBlock(stack)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private static boolean isBridgeBlock(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().blocksMotion();
    }

    private static void placeBridgeBlock(Minecraft client, BlockPos placePos, BlockPos anchorPos) {
        Vec3 hit = bridgeHitPoint(placePos, anchorPos);
        Direction side = bridgePlaceSide(placePos, anchorPos);
        facePoint(client, hit);
        BlockHitResult hitResult = new BlockHitResult(hit, side, anchorPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static Vec3 bridgeHitPoint(BlockPos placePos, BlockPos anchorPos) {
        Direction side = bridgePlaceSide(placePos, anchorPos);
        return anchorPos.getCenter().add(
                side.getStepX() * 0.5D,
                side.getStepY() * 0.5D,
                side.getStepZ() * 0.5D
        );
    }

    private static Direction bridgePlaceSide(BlockPos placePos, BlockPos anchorPos) {
        Direction side = Direction.getNearest(
                placePos.getX() - anchorPos.getX(),
                placePos.getY() - anchorPos.getY(),
                placePos.getZ() - anchorPos.getZ(),
                Direction.UP
        );
        return side;
    }

    private static Vec3 waypointCenter(int index, double y) {
        int row = Math.floorMod(index, 256) / 16;
        int column = Math.floorMod(index, 16);
        int xOffset = row % 2 == 0 ? column : 15 - column;
        return new Vec3(minX + xOffset + 0.5D, y, minZ + row + 0.5D);
    }

    private static int localIndex(BlockPos pos) {
        int localX = Math.floorMod(pos.getX(), 16);
        int localZ = Math.floorMod(pos.getZ(), 16);
        return localZ * 16 + (localZ % 2 == 0 ? localX : 15 - localX);
    }

    private static boolean ensurePickaxeSelected(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        if (isPickaxe(inventory.getSelectedItem())) {
            return true;
        }

        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPickaxe(stack)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private static boolean isPickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase();
        return id.endsWith("_pickaxe") || id.contains("pickaxe") || id.contains("镐");
    }

    private static boolean isInsideLockedChunk(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private static void releaseWalkKeys(Minecraft client) {
        GroundMovementController.release(client);
    }

    private static void releaseMovementKeys(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }
        releaseWalkKeys(client);
    }

    private static void facePoint(Minecraft client, Vec3 target) {
        LocalPlayer player = client.player;
        Vec3 eyePos = player.getEyePosition();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDistance, 1.0E-4D)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
    }

    private static void smoothFacePoint(Minecraft client, Vec3 target, float maxYawStep, float maxPitchStep) {
        LocalPlayer player = client.player;
        Vec3 eyePos = player.getEyePosition();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDistance, 1.0E-4D)));
        float yaw = approachAngle(player.getYRot(), targetYaw, maxYawStep);
        float pitch = approachAngle(player.getXRot(), targetPitch, maxPitchStep);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
    }

    private static float approachAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    private static double horizontalDistanceSqr(Vec3 left, Vec3 right) {
        double dx = left.x - right.x;
        double dz = left.z - right.z;
        return dx * dx + dz * dz;
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.gameMode != null && client.getConnection() != null;
    }

    private static void sendMessage(Minecraft client, String message) {
        sendMessage(client, Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    private static void sendThrottledProgress(Minecraft client, String message, int cooldownTicks) {
        if (progressMessageCooldownTicks > 0) {
            return;
        }
        progressMessageCooldownTicks = Math.max(1, cooldownTicks);
        sendMessage(client, message);
    }

    private static void sendMessage(Minecraft client, Component message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message.getString());
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.empty()
                            .append(Component.literal(prefix).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                            .append(message)
            , false);
        }
    }

    private static String formatChunk() {
        return chunkX + "," + chunkZ;
    }

    private static String formatLayer() {
        return "Y " + layerTopY;
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private enum BridgePlan {
        NONE,
        QUEUED,
        BLOCKED
    }
}


