package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class AutoDragonController {
    private static final String COMMAND = "autodragon";
    private static final String CHAT_MODULE = "自动打龙";
    private static final int SCAN_RADIUS = 48;
    private static final int PORTAL_SCAN_RADIUS = 28;
    private static final int ACTION_DELAY_TICKS = 8;
    private static final int CRYSTAL_PLACE_DELAY_TICKS = 12;
    private static final int FIREWORK_COOLDOWN_TICKS = 20 * 10;
    private static final int COMBAT_FIREWORK_COOLDOWN_TICKS = 24;
    private static final int DRAGON_GONE_CONFIRM_TICKS = 80;
    private static final int DROP_COLLECTION_TIMEOUT_TICKS = 20 * 35;
    private static final int SHULKER_STORE_TIMEOUT_TICKS = 20 * 12;
    private static final int WAIT_FOR_DRAGON_TIMEOUT_TICKS = 20 * 180;
    private static final int RETURN_COMMAND_COOLDOWN_TICKS = 20 * 5;
    private static final int TAKEOFF_TIMEOUT_TICKS = 20 * 12;
    private static final int TAKEOFF_FIREWORK_COOLDOWN_TICKS = 20 * 6;
    private static final int EMERGENCY_FIREWORK_RETRY_TICKS = 20 * 5;
    private static final int CRYSTAL_NAVIGATION_TIMEOUT_TICKS = 20 * 25;
    private static final int SHIP_SCAN_INTERVAL_TICKS = 20 * 2;
    private static final int SHIP_RETURN_COMMAND_COOLDOWN_TICKS = 20 * 5;
    private static final int SHIP_HEAD_MINE_TIMEOUT_TICKS = 20 * 8;
    private static final int SHIP_HEAD_MINE_PREPARE_TICKS = 3;
    private static final int LAND_AFTER_DRAGON_TIMEOUT_TICKS = 20 * 25;
    private static final int SHULKER_PICKUP_TIMEOUT_TICKS = 20 * 8;
    private static final int PIT_DIG_TIMEOUT_TICKS = 20 * 6;
    private static final int AERIAL_TEAR_SEARCH_TIMEOUT_TICKS = 20 * 45;
    private static final int GROUND_TEAR_SEARCH_BEFORE_TAKEOFF_TICKS = 20 * 8;
    private static final int TAKEOFF_IMMEDIATE_FIREWORK_TICKS = 1;
    private static final int OBSTACLE_ESCAPE_TICKS = 12;
    private static final int OBSTACLE_STUCK_CONFIRM_TICKS = 10;
    private static final double CRYSTAL_PRESENT_DISTANCE_SQUARED = 6.25D;
    private static final double DRAGON_SEARCH_DISTANCE_SQUARED = 512.0D * 512.0D;
    private static final double MELEE_REACH = 3.0D;
    private static final double MELEE_REACH_SQUARED = MELEE_REACH * MELEE_REACH;
    private static final double MELEE_APPROACH_DISTANCE = 5.0D;
    private static final double MELEE_APPROACH_DISTANCE_SQUARED = MELEE_APPROACH_DISTANCE * MELEE_APPROACH_DISTANCE;
    private static final double DROP_PICKUP_DISTANCE_SQUARED = 3.5D * 3.5D;
    private static final double SHULKER_MIN_PLACE_DISTANCE_SQUARED = 2.0D * 2.0D;
    private static final double SHULKER_IDEAL_PLACE_DISTANCE_SQUARED = 3.0D * 3.0D;
    private static final double CRYSTAL_INTERACT_DISTANCE_SQUARED = 16.0D;
    private static final double CRYSTAL_STAND_REACHED_DISTANCE_SQUARED = 1.6D;
    private static final double SHIP_INTERACT_DISTANCE_SQUARED = 18.0D;
    private static final double PORTAL_AVOID_RADIUS = 6.25D;
    private static final double PORTAL_BYPASS_RADIUS = 8.0D;
    private static final int LOW_ALTITUDE_FIREWORK_BLOCKS = 12;
    private static final int CRITICAL_ALTITUDE_FIREWORK_BLOCKS = 7;
    private static final double ELYTRA_SEARCH_ALTITUDE = 94.0D;
    private static final double DRAGON_POOL_X = 0.0D;
    private static final double DRAGON_POOL_Z = 0.0D;
    private static final ElytraFlightController.Profile ELYTRA_FLIGHT_PROFILE =
            new ElytraFlightController.Profile(LOW_ALTITUDE_FIREWORK_BLOCKS, CRITICAL_ALTITUDE_FIREWORK_BLOCKS, 5.5F, 2.0F);

    private static boolean enabled;
    private static boolean builtInAuraActive;
    private static DragonState state = DragonState.IDLE;
    private static long dragonTickCounter;
    private static int delayTicks;
    private static int stateTimeoutTicks;
    private static int fireworkCooldownTicks;
    private static int missingDragonTicks;
    private static int returnCommandCooldownTicks;
    private static int progressMessageCooldownTicks;
    private static int takeoffTicks;
    private static int takeoffFireworkCooldownTicks;
    private static int takeoffFailureCount;
    private static TakeoffPhase takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
    private static boolean takeoffImmediateFireworkUsed;
    private static double lastDragonDistance = -1.0D;
    private static Vec3 lastFlightPos = Vec3.ZERO;
    private static int flightStuckTicks;
    private static int obstacleEscapeTicks;
    private static float obstacleEscapeYaw;
    private static boolean returnToEndArmed;
    private static boolean shipMode;
    private static boolean shipVoidDeathArmed;
    private static int lockedDragonId = -1;
    private static int shipSearchAngle;
    private static int shipScanCooldownTicks;
    private static int shipReturnCommandCooldownTicks;
    private static int shipHeadMineTicks;
    private static int shipHeadMinePrepareTicks;
    private static boolean shipHeadMiningActive;
    private static BlockPos activeMineTarget;
    private static boolean dragonMiningActive;
    private static int dragonMiningTicks;
    private static int groundStuckTicks;
    private static int aerialTearSearchAngle;
    private static BlockPos manualPortalCenter;
    private static BlockPos activePortalCenter;
    private static BlockPos pendingCrystalBase;
    private static BlockPos pendingCrystalStandPos;
    private static BlockPos activeShulkerPos;
    private static BlockPos activeShipTarget;
    private static BlockPos takeoffStandPos;
    private static ShipObjective shipObjective = ShipObjective.SEARCH;

    private AutoDragonController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(COMMAND)
                .executes(command -> {
                    Minecraft client = command.getSource().getClient();
                    toggle(client);
                    return 1;
                })
                .then(ClientCommandManager.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("setportal")
                        .executes(command -> {
                            setPortalCenter(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("ship")
                        .executes(command -> {
                            startShipHunt(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "自动打龙已经停下来了。");
                            return 1;
                        })));
    }

    static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, "自动打龙已经停下来了。");
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoDragon()) {
            sendMessage(client, "自动打龙已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动打龙。");
            return;
        }

        if (client.level.dimension() != Level.END) {
            sendMessage(client, "自动打龙只会在末地启动。");
            return;
        }

        enabled = true;
        ScreenProbeClient.stopAutoSellForExternalAutomation(client);
        state = DragonState.SCAN;
        delayTicks = 0;
        stateTimeoutTicks = 0;
        fireworkCooldownTicks = 0;
        missingDragonTicks = 0;
        returnCommandCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        takeoffTicks = 0;
        takeoffFireworkCooldownTicks = 0;
        takeoffFailureCount = 0;
        takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
        takeoffImmediateFireworkUsed = false;
        resetFlightObstacleEscape(client);
        lastDragonDistance = -1.0D;
        returnToEndArmed = false;
        lockedDragonId = -1;
        activePortalCenter = null;
        pendingCrystalBase = null;
        pendingCrystalStandPos = null;
        activeShulkerPos = null;
        aerialTearSearchAngle = 0;
        sendMessage(client, "自动打龙启动：自动复活、追龙、条件烟花飞行、内建合法光环、龙泪拾取和死亡回末地都已接管。");
    }

    private static void startShipHunt(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoDragon()) {
            sendMessage(client, "自动打龙/找船已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (enabled) {
            stop(client, null);
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动找船。");
            return;
        }

        if (client.level.dimension() != Level.END) {
            sendMessage(client, "找龙头鞘翅只会在末地启动。");
            return;
        }

        enabled = true;
        shipMode = true;
        shipVoidDeathArmed = false;
        shipSearchAngle = 0;
        shipScanCooldownTicks = 0;
        shipReturnCommandCooldownTicks = 0;
        shipHeadMineTicks = 0;
        shipHeadMinePrepareTicks = 0;
        shipHeadMiningActive = false;
        activeShipTarget = null;
        shipObjective = ShipObjective.SEARCH;
        state = DragonState.SHIP_SEARCH;
        delayTicks = 0;
        fireworkCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        ScreenProbeClient.stopAutoSellForExternalAutomation(client);
        sendMessage(client, "自动找船启动：会在末地外岛飞行搜索末地船，获取龙头和鞘翅。虚空死亡会提示手动接管。");
    }

    static void tick(Minecraft client) {
        if (!enabled) {
            return;
        }
        if (!ScreenProbeGlobalConfig.get(client).enableAutoDragon()) {
            stop(client, "自动打龙已被全局设置屏蔽。");
            return;
        }
        dragonTickCounter++;

        if (progressMessageCooldownTicks > 0) {
            progressMessageCooldownTicks--;
        }
        if (returnCommandCooldownTicks > 0) {
            returnCommandCooldownTicks--;
        }
        if (takeoffFireworkCooldownTicks > 0) {
            takeoffFireworkCooldownTicks--;
        }
        if (shipReturnCommandCooldownTicks > 0) {
            shipReturnCommandCooldownTicks--;
        }

        if (!isClientReady(client)) {
            releaseMovementKeys(client);
            return;
        }

        if (handleDeathAndReturn(client)) {
            return;
        }

        if (shipMode && client.player.getY() < -32.0D) {
            shipVoidDeathArmed = true;
            sendThrottledProgress(client, "正在坠入虚空，若死亡将停止并提示手动接管。", 20 * 2);
        }

        if (client.level.dimension() != Level.END) {
            tickReturnToEnd(client);
            return;
        }
        returnToEndArmed = false;

        if (client.screen != null) {
            releaseMovementKeys(client);
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (fireworkCooldownTicks > 0) {
            fireworkCooldownTicks--;
        }

        switch (state) {
            case IDLE -> state = DragonState.SCAN;
            case SCAN -> tickScan(client);
            case PLACE_CRYSTALS -> tickPlaceCrystals(client);
            case NAVIGATE_TO_CRYSTAL -> tickNavigateToCrystal(client);
            case WAIT_FOR_DRAGON -> tickWaitForDragon(client);
            case PREPARE_FLIGHT -> tickPrepareFlight(client);
            case COMBAT -> tickCombat(client);
            case LAND_AFTER_DRAGON -> tickLandAfterDragon(client);
            case COLLECT_DROPS -> tickCollectDrops(client);
            case AERIAL_TEAR_SEARCH -> tickAerialTearSearch(client);
            case STORE_DROPS -> tickStoreDrops(client);
            case PICKUP_SHULKER -> tickPickupStoredShulker(client);
            case RETURNING_TO_END -> tickReturnToEnd(client);
            case SHIP_SEARCH -> tickShipSearch(client);
            case SHIP_APPROACH -> tickShipApproach(client);
            case SHIP_MINE_HEAD -> tickShipMineHead(client);
        }
    }

    static void handleSessionReset(Minecraft client) {
        if (enabled || builtInAuraActive) {
            stop(client, null);
        }
    }

    private static void setPortalCenter(Minecraft client) {
        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能记录龙池中心。");
            return;
        }

        manualPortalCenter = client.player.blockPosition().immutable();
        activePortalCenter = manualPortalCenter;
        sendMessage(client, Component.empty()
                .append(Component.literal("已记录龙池中心：").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(formatBlockPos(manualPortalCenter)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
    }

    private static void tickScan(Minecraft client) {
        Optional<EnderDragon> dragon = findNearestDragon(client);
        if (dragon.isPresent()) {
            beginCombat(client);
            return;
        }

        Optional<BlockPos> portalCenter = resolvePortalCenter(client);
        if (portalCenter.isEmpty()) {
            sendMessage(client, "没找到末地龙池。站到龙池附近执行 /autodragon setportal 再启动会更稳。");
            delayTicks = 20 * 5;
            return;
        }

        activePortalCenter = portalCenter.get();
        state = DragonState.PLACE_CRYSTALS;
        delayTicks = ACTION_DELAY_TICKS;
    }

    private static void tickPlaceCrystals(Minecraft client) {
        if (findNearestDragon(client).isPresent()) {
            beginCombat(client);
            return;
        }

        if (activePortalCenter == null) {
            state = DragonState.SCAN;
            return;
        }

        Optional<BlockPos> nextCrystalBase = findMissingCrystalBase(client);
        if (nextCrystalBase.isEmpty()) {
            sendThrottledProgress(client, "水晶已经放齐，正在等末影龙复活。", 20 * 10);
            state = DragonState.WAIT_FOR_DRAGON;
            stateTimeoutTicks = WAIT_FOR_DRAGON_TIMEOUT_TICKS;
            delayTicks = 20;
            return;
        }

        pendingCrystalBase = nextCrystalBase.get();
        Optional<BlockPos> standPos = findCrystalStandPos(client, pendingCrystalBase);
        if (standPos.isEmpty()) {
            sendMessage(client, "找不到能靠近水晶基座的位置，跳过这个基座：" + formatBlockPos(pendingCrystalBase));
            delayTicks = 20;
            return;
        }

        pendingCrystalStandPos = standPos.get();
        state = DragonState.NAVIGATE_TO_CRYSTAL;
        stateTimeoutTicks = CRYSTAL_NAVIGATION_TIMEOUT_TICKS;
        sendMessage(client, "正在走到水晶放置点：" + formatBlockPos(pendingCrystalBase));
    }

    private static void tickNavigateToCrystal(Minecraft client) {
        if (findNearestDragon(client).isPresent()) {
            releaseMovementKeys(client);
            beginCombat(client);
            return;
        }

        if (pendingCrystalBase == null) {
            releaseMovementKeys(client);
            state = DragonState.PLACE_CRYSTALS;
            return;
        }

        if (hasCrystalNear(client, pendingCrystalBase.above().getCenter())) {
            releaseMovementKeys(client);
            state = DragonState.PLACE_CRYSTALS;
            delayTicks = ACTION_DELAY_TICKS;
            return;
        }

        if (pendingCrystalStandPos == null) {
            Optional<BlockPos> standPos = findCrystalStandPos(client, pendingCrystalBase);
            if (standPos.isEmpty()) {
                releaseMovementKeys(client);
                pendingCrystalBase = null;
                state = DragonState.PLACE_CRYSTALS;
                delayTicks = 20;
                return;
            }
            pendingCrystalStandPos = standPos.get();
        }

        if (stateTimeoutTicks-- <= 0) {
            releaseMovementKeys(client);
            sendMessage(client, "走到水晶点超时，重新扫描龙池。");
            pendingCrystalBase = null;
            pendingCrystalStandPos = null;
            state = DragonState.PLACE_CRYSTALS;
            delayTicks = 20;
            return;
        }

        double baseDistanceSqr = client.player.distanceToSqr(pendingCrystalBase.getCenter());
        double standDistanceSqr = client.player.distanceToSqr(pendingCrystalStandPos.getCenter());
        if (baseDistanceSqr <= CRYSTAL_INTERACT_DISTANCE_SQUARED && standDistanceSqr <= CRYSTAL_STAND_REACHED_DISTANCE_SQUARED) {
            releaseMovementKeys(client);
            if (!selectOrMoveToHotbar(client, stack -> stack.is(Items.END_CRYSTAL), "末影水晶")) {
                sendMessage(client, "背包里没有末影水晶，自动复活先等一下。");
                delayTicks = 20 * 3;
                state = DragonState.PLACE_CRYSTALS;
                return;
            }

            placeCrystal(client, pendingCrystalBase);
            sendMessage(client, "已靠近并放置复活水晶：" + formatBlockPos(pendingCrystalBase));
            pendingCrystalBase = null;
            pendingCrystalStandPos = null;
            state = DragonState.PLACE_CRYSTALS;
            delayTicks = CRYSTAL_PLACE_DELAY_TICKS;
            return;
        }

        walkTowardBlock(client, pendingCrystalStandPos);
        sendThrottledProgress(client, "正在靠近水晶基座，距离 " + formatDistance(Math.sqrt(baseDistanceSqr)) + " 格。", 20 * 4);
    }

    private static void tickWaitForDragon(Minecraft client) {
        Optional<EnderDragon> dragon = findNearestDragon(client);
        if (dragon.isPresent()) {
            beginCombat(client);
            return;
        }

        if (stateTimeoutTicks-- <= 0) {
            state = DragonState.SCAN;
            delayTicks = 20;
            return;
        }

        releaseMovementKeys(client);
    }

    private static void tickPrepareFlight(Minecraft client) {
        Optional<EnderDragon> dragon = findTrackedDragon(client);
        if (dragon.isEmpty()) {
            finishCombatCycle(client);
            return;
        }

        if (!ensureElytraReady(client)) {
            sendMessage(client, "没有穿上鞘翅，自动打龙先等一下。");
            delayTicks = 20 * 3;
            return;
        }

        LocalPlayer player = client.player;
        GroundMovementController.smoothFaceEntity(client, dragon.get(), 0.0D, 8.0F, 5.0F);
        if (!player.isFallFlying()) {
            GroundMovementController.smoothLook(client, player.getYRot(), -8.0F, 6.0F, 2.5F);
        }
        ensureSwordSelected(client);
        ensureOffhandFireworkStock(client);
        client.options.keySprint.setDown(true);
        client.options.keyUp.setDown(true);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);

        if (takeoffStandPos != null && player.onGround()
                && player.distanceToSqr(takeoffStandPos.getCenter()) > CRYSTAL_STAND_REACHED_DISTANCE_SQUARED) {
            walkTowardBlock(client, takeoffStandPos);
            sendThrottledProgress(client, "正在移动到更适合起飞的位置。", 20 * 4);
            return;
        }

        takeoffTicks++;

        if (player.onGround() && player.isFallFlying()) {
            resetTakeoffAttempt(client, "检测到贴地飞行姿态，已重置后重新起飞。");
            return;
        }

        if (takeoffPhase == TakeoffPhase.DOUBLE_JUMP) {
            boolean firstTap = takeoffTicks >= 1 && takeoffTicks <= 2;
            boolean secondTap = takeoffTicks >= 6 && takeoffTicks <= 8;
            client.options.keyJump.setDown(firstTap || secondTap);
            if (takeoffTicks >= 9) {
                client.options.keyJump.setDown(false);
                takeoffPhase = TakeoffPhase.GLIDE;
                takeoffTicks = 0;
                sendThrottledProgress(client, "第二次空格后稍等，正在尝试展开鞘翅。", 20 * 3);
            } else {
                sendThrottledProgress(client, "正在从地面起飞：两次空格间隔后起跳。", 20 * 3);
            }
        } else if (takeoffPhase == TakeoffPhase.GLIDE) {
            client.options.keyJump.setDown(false);
            GroundMovementController.smoothLook(client, player.getYRot(), -4.0F, 5.0F, 2.0F);
            if (!player.onGround() && takeoffTicks >= 2 && takeoffTicks % 2 == 0) {
                sendStartFallFlying(client);
            }
            if (player.isFallFlying() && !player.onGround()) {
                takeoffPhase = TakeoffPhase.BOOST;
                takeoffTicks = 0;
                takeoffImmediateFireworkUsed = false;
                sendThrottledProgress(client, "鞘翅已展开，准备使用烟花拉起。", 20 * 3);
            }
        } else if (takeoffPhase == TakeoffPhase.BOOST) {
            client.options.keyJump.setDown(false);
            Vec3 boostTarget = dragon.get().position().add(0.0D, Math.max(3.0D, dragon.get().getBbHeight() * 0.35D), 0.0D);
            ElytraFlightController.steerToward(client, boostTarget, false, true, ELYTRA_FLIGHT_PROFILE);
            if (!takeoffImmediateFireworkUsed
                    && player.isFallFlying()
                    && !player.onGround()
                    && takeoffTicks >= TAKEOFF_IMMEDIATE_FIREWORK_TICKS
                    && useOffhandFirework(client)) {
                takeoffImmediateFireworkUsed = true;
                takeoffFireworkCooldownTicks = TAKEOFF_FIREWORK_COOLDOWN_TICKS;
                fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
                sendMessage(client, "起飞后立即使用一发烟花拉速度。");
            }
            if (player.isFallFlying()
                    && !player.onGround()
                    && takeoffTicks >= 8
                    && takeoffFireworkCooldownTicks <= 0
                    && useOffhandFirework(client)) {
                takeoffFireworkCooldownTicks = player.getDeltaMovement().horizontalDistanceSqr() < 0.08D
                        ? EMERGENCY_FIREWORK_RETRY_TICKS
                        : TAKEOFF_FIREWORK_COOLDOWN_TICKS;
                fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
                sendMessage(client, "使用一发烟花完成起飞。");
            }
        }

        if (player.isFallFlying() && !player.onGround() && player.getDeltaMovement().lengthSqr() > 0.04D) {
            enableBuiltInAura();
            state = DragonState.COMBAT;
            sendMessage(client, "已起飞，内建合法光环开启，开始贴近追龙。");
            delayTicks = ACTION_DELAY_TICKS;
            takeoffTicks = 0;
            takeoffFailureCount = 0;
            takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
            takeoffStandPos = null;
            return;
        }

        if (takeoffTicks > TAKEOFF_TIMEOUT_TICKS) {
            resetTakeoffAttempt(client, "起飞暂时失败，重试一次。");
        }
    }

    private static void resetTakeoffAttempt(Minecraft client, String message) {
        client.options.keyJump.setDown(false);
        if (client.player.isFallFlying()) {
            client.player.stopFallFlying();
        }
        takeoffTicks = 0;
        takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
        delayTicks = ACTION_DELAY_TICKS;
        takeoffFailureCount++;
        if (takeoffFailureCount >= 2) {
            takeoffStandPos = findBetterTakeoffStand(client).orElse(null);
            if (takeoffStandPos != null) {
                sendMessage(client, message + " 已选择附近高点重新起飞：" + formatBlockPos(takeoffStandPos));
                return;
            }
        }
        sendMessage(client, message);
    }

    private static void sendStartFallFlying(Minecraft client) {
        client.player.tryToStartFallFlying();
        client.player.connection.send(new ServerboundPlayerCommandPacket(
                client.player,
                ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
    }

    private static Optional<BlockPos> findBetterTakeoffStand(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = 4; dy >= -1; dy--) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (isSafeStandingPos(client, pos)) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }

        return candidates.stream()
                .min(Comparator
                        .comparingInt((BlockPos pos) -> -takeoffDropScore(client, pos))
                        .thenComparingInt(pos -> -pos.getY())
                        .thenComparingDouble(pos -> pos.getCenter().distanceToSqr(client.player.position())));
    }

    private static int takeoffDropScore(Minecraft client, BlockPos pos) {
        int bestDrop = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos edge = pos.relative(direction);
            if (!client.level.getBlockState(edge).isAir() || !client.level.getBlockState(edge.above()).isAir()) {
                continue;
            }
            int drop = 0;
            for (int depth = 1; depth <= 8; depth++) {
                BlockPos below = edge.below(depth);
                if (client.level.getBlockState(below).blocksMotion() || !client.level.getFluidState(below).isEmpty()) {
                    break;
                }
                drop = depth;
            }
            bestDrop = Math.max(bestDrop, drop);
        }
        return bestDrop;
    }

    private static void tickCombat(Minecraft client) {
        Optional<EnderDragon> dragon = findTrackedDragon(client);
        if (dragon.isEmpty()) {
            missingDragonTicks++;
            releaseMovementKeys(client);
            if (missingDragonTicks >= DRAGON_GONE_CONFIRM_TICKS) {
                beginLandingAfterDragon(client);
            }
            return;
        }

        missingDragonTicks = 0;
        if (ensureExpectedFlight(client, "追龙过程中落到地面，重新调用起飞逻辑。")) {
            return;
        }
        enableBuiltInAura();
        ensureElytraReady(client);
        ensureSwordSelected(client);
        ensureOffhandFireworkStock(client);
        if (handleFlightObstacleEscape(client)) {
            useEmergencyFireworkIfNeeded(client);
            return;
        }
        steerTowardDragon(client, dragon.get());
        tickLegalDragonAura(client, dragon.get());
        useFireworkIfNeeded(client, dragon.get());
    }

    private static void beginCombat(Minecraft client) {
        state = DragonState.PREPARE_FLIGHT;
        delayTicks = ACTION_DELAY_TICKS;
        missingDragonTicks = 0;
        fireworkCooldownTicks = 0;
        takeoffTicks = 0;
        takeoffFireworkCooldownTicks = 0;
        takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
        takeoffImmediateFireworkUsed = false;
        resetFlightObstacleEscape(client);
        lastDragonDistance = -1.0D;
        findNearestDragon(client).ifPresent(dragon -> lockedDragonId = dragon.getId());
        sendMessage(client, "检测到末影龙，准备起飞、切快捷栏1的剑并启用内建合法光环。");
    }

    private static void beginDropCollection(Minecraft client) {
        disableBuiltInAura();
        releaseMovementKeys(client);
        state = DragonState.COLLECT_DROPS;
        stateTimeoutTicks = DROP_COLLECTION_TIMEOUT_TICKS;
        missingDragonTicks = 0;
        lockedDragonId = -1;
        resetFlightObstacleEscape(client);
        aerialTearSearchAngle = 0;
        sendMessage(client, "末影龙目标消失，内建合法光环已关闭，开始搜索并拾取龙泪。");
    }

    private static void beginLandingAfterDragon(Minecraft client) {
        disableBuiltInAura();
        releaseMovementKeys(client);
        state = DragonState.LAND_AFTER_DRAGON;
        stateTimeoutTicks = LAND_AFTER_DRAGON_TIMEOUT_TICKS;
        missingDragonTicks = 0;
        lockedDragonId = -1;
        resetFlightObstacleEscape(client);
        sendMessage(client, "检测到末影龙死亡，正在先拉回平稳地面。");
    }

    private static void tickLandAfterDragon(Minecraft client) {
        LocalPlayer player = client.player;
        int groundDistance = GroundMovementController.distanceToGround(client);
        if (player.onGround() || (!player.isFallFlying() && groundDistance <= 1)) {
            player.stopFallFlying();
            releaseMovementKeys(client);
            beginDropCollection(client);
            return;
        }

        if (stateTimeoutTicks-- <= 0) {
            player.stopFallFlying();
            releaseMovementKeys(client);
            sendMessage(client, "降落等待超时，先开始搜索龙泪。");
            beginDropCollection(client);
            return;
        }

        BlockPos landingPos = findNearestLandingPos(client).orElse(player.blockPosition());
        Vec3 target = landingPos.getCenter();
        if (player.isFallFlying()) {
            float pitch = groundDistance <= 5 ? -10.0F : groundDistance <= 12 ? 4.0F : 16.0F;
            GroundMovementController.smoothFacePoint(client, target, 8.0F, 4.0F);
            GroundMovementController.smoothLook(client, player.getYRot(), pitch, 5.0F, 3.0F);
            GroundMovementController.steerForward(client);
        } else {
            walkTowardPoint(client, target);
        }
        sendThrottledProgress(client, "末影龙已死亡，正在降落到平稳地面。", 20 * 3);
    }

    private static void tickCollectDrops(Minecraft client) {
        Optional<ItemEntity> tear = findNearestDragonTearDrop(client);
        if (tear.isPresent()) {
            ItemEntity drop = tear.get();
            navigateToDragonTear(client, drop);
            if (client.player.distanceToSqr(drop) <= DROP_PICKUP_DISTANCE_SQUARED) {
                sendThrottledProgress(client, "正在拾取龙泪。", 20 * 3);
            } else {
                sendThrottledProgress(client, "发现龙泪，正在寻路过去：" + formatDistance(client.player.distanceTo(drop)) + " 格。", 20 * 5);
            }
            return;
        }

        releaseMovementKeys(client);
        if (countDragonTearsInInventory(client) > 0 && hasShulkerBoxInInventory(client)) {
            beginStoreDrops(client);
            return;
        }

        if (stateTimeoutTicks > DROP_COLLECTION_TIMEOUT_TICKS - GROUND_TEAR_SEARCH_BEFORE_TAKEOFF_TICKS) {
            stateTimeoutTicks--;
            sendThrottledProgress(client, "暂时没看到龙泪，继续扫描掉落物。", 20 * 8);
            return;
        }

        beginAerialTearSearch(client, "地面附近没看到龙泪，自动起飞回 0,0 龙池上空继续寻找。");
    }

    private static void beginAerialTearSearch(Minecraft client, String message) {
        if (countDragonTearsInInventory(client) > 0 && hasShulkerBoxInInventory(client)) {
            beginStoreDrops(client);
            return;
        }

        state = DragonState.AERIAL_TEAR_SEARCH;
        stateTimeoutTicks = AERIAL_TEAR_SEARCH_TIMEOUT_TICKS;
        takeoffTicks = 0;
        takeoffFireworkCooldownTicks = 0;
        takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
        takeoffImmediateFireworkUsed = false;
        aerialTearSearchAngle = 0;
        resetFlightObstacleEscape(client);
        sendMessage(client, message);
    }

    private static void tickAerialTearSearch(Minecraft client) {
        Optional<ItemEntity> tear = findNearestDragonTearDrop(client);
        if (tear.isPresent()) {
            navigateToDragonTear(client, tear.get());
            state = DragonState.COLLECT_DROPS;
            stateTimeoutTicks = GROUND_TEAR_SEARCH_BEFORE_TAKEOFF_TICKS;
            sendThrottledProgress(client, "空中发现龙泪，正在靠近拾取。", 20 * 3);
            return;
        }

        if (countDragonTearsInInventory(client) > 0 && hasShulkerBoxInInventory(client)) {
            beginStoreDrops(client);
            return;
        }

        if (stateTimeoutTicks-- <= 0) {
            reportDragonTearStorage(client);
            finishCombatCycle(client);
            return;
        }

        if (ensureExpectedFlight(client, "找龙泪过程中落到地面，重新调用起飞逻辑。")) {
            return;
        }

        ensureElytraReady(client);
        ensureOffhandFireworkStock(client);
        if (handleFlightObstacleEscape(client)) {
            useEmergencyFireworkIfNeeded(client);
            return;
        }
        flyDragonTearSearchPattern(client);
        sendThrottledProgress(client, "正在 0,0 龙池附近空中搜索龙泪。", 20 * 5);
    }

    private static void beginStoreDrops(Minecraft client) {
        state = DragonState.STORE_DROPS;
        stateTimeoutTicks = SHULKER_STORE_TIMEOUT_TICKS;
        activeShulkerPos = null;
        releaseMovementKeys(client);
        sendMessage(client, "检测到背包有龙泪和潜影盒，开始模拟手动放盒存入。");
    }

    private static void tickStoreDrops(Minecraft client) {
        releaseMovementKeys(client);
        if (countDragonTearsInInventory(client) <= 0) {
            sendMessage(client, "背包里已经没有待存的龙泪。");
            finishCombatCycle(client);
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?> containerScreen) {
            int moved = quickMoveDragonTearsIntoOpenContainer(client, containerScreen);
            client.player.closeContainer();
            int remaining = countDragonTearsInInventory(client);
            if (moved > 0 && remaining <= 0) {
                sendMessage(client, "已确认把 " + moved + " 个龙泪存进潜影盒，背包龙泪已清空。");
                beginPickupStoredShulker(client);
            } else if (moved > 0) {
                sendMessage(client, "已移动 " + moved + " 个龙泪，但背包仍有 " + remaining + " 个，继续尝试存入，不会提前挖盒。");
                delayTicks = ACTION_DELAY_TICKS;
            } else {
                sendMessage(client, "潜影盒已打开，但没有可移动的龙泪或盒子已满。");
                finishCombatCycle(client);
            }
            return;
        }

        if (activeShulkerPos != null && client.level.getBlockState(activeShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            openPlacedShulker(client, activeShulkerPos);
            delayTicks = ACTION_DELAY_TICKS;
            return;
        }

        Optional<BlockPos> placePos = findShulkerPlacementPos(client);
        if (placePos.isEmpty()) {
            sendMessage(client, "附近没有找到安全位置放潜影盒，龙泪先留在背包里。");
            finishCombatCycle(client);
            return;
        }

        if (!selectOrMoveToHotbar(client, AutoDragonController::isShulkerBoxStack, "潜影盒")) {
            sendMessage(client, "背包里没有潜影盒，龙泪先留在背包里。");
            finishCombatCycle(client);
            return;
        }

        activeShulkerPos = placePos.get();
        placeShulker(client, activeShulkerPos);
        sendMessage(client, "已放置潜影盒，准备打开存龙泪：" + formatBlockPos(activeShulkerPos));
        delayTicks = ACTION_DELAY_TICKS * 2;

        if (stateTimeoutTicks-- <= 0) {
            sendMessage(client, "存盒等待超时，龙泪先留在背包里。");
            finishCombatCycle(client);
        }
    }

    private static void beginPickupStoredShulker(Minecraft client) {
        if (activeShulkerPos == null) {
            finishCombatCycle(client);
            return;
        }
        state = DragonState.PICKUP_SHULKER;
        stateTimeoutTicks = SHULKER_PICKUP_TIMEOUT_TICKS;
        clearDragonMining(client);
        sendMessage(client, "龙泪已放入潜影盒，开始自动挖回潜影盒。");
    }

    private static void tickPickupStoredShulker(Minecraft client) {
        releaseMovementKeys(client);
        if (activeShulkerPos == null || !(client.level.getBlockState(activeShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            clearDragonMining(client);
            sendMessage(client, "潜影盒已挖掉，龙泪存盒流程完成。");
            finishCombatCycle(client);
            return;
        }

        if (stateTimeoutTicks-- <= 0) {
            clearDragonMining(client);
            sendMessage(client, "挖回潜影盒超时，请手动确认盒子是否掉落。");
            finishCombatCycle(client);
            return;
        }

        if (!ensurePickaxeSelected(client)) {
            sendThrottledProgress(client, "没有找到镐子，无法自动挖回潜影盒。", 20 * 4);
            return;
        }

        mineBlock(client, activeShulkerPos);
        sendThrottledProgress(client, "正在挖回装有龙泪的潜影盒。", 20 * 3);
    }

    private static void finishCombatCycle(Minecraft client) {
        disableBuiltInAura();
        releaseMovementKeys(client);
        clearDragonMining(client);
        state = DragonState.SCAN;
        delayTicks = 20 * 6;
        missingDragonTicks = 0;
        lockedDragonId = -1;
        activeShulkerPos = null;
        aerialTearSearchAngle = 0;
        resetFlightObstacleEscape(client);
        sendMessage(client, "龙泪拾取阶段结束，准备下一轮复活。");
    }

    private static void tickShipSearch(Minecraft client) {
        if (hasShipLoot(client)) {
            stop(client, "背包里已经检测到龙头和鞘翅，自动找船完成。");
            return;
        }

        if (ensureExpectedFlight(client, "找船搜索过程中落到地面，重新调用起飞逻辑。")) {
            return;
        }

        Optional<ShipTarget> target = findNearestShipTarget(client);
        if (target.isPresent()) {
            ShipTarget shipTarget = target.get();
            activeShipTarget = shipTarget.pos();
            shipObjective = shipTarget.objective();
            state = DragonState.SHIP_APPROACH;
            sendMessage(client, "发现末地船目标：" + shipObjective.label() + " " + formatBlockPos(activeShipTarget));
            return;
        }

        ensureElytraReady(client);
        ensureOffhandFireworkStock(client);
        flyShipSearchPattern(client);
        sendThrottledProgress(client, "正在末地外岛搜索末地船。", 20 * 8);
    }

    private static boolean ensureExpectedFlight(Minecraft client, String message) {
        LocalPlayer player = client.player;
        if (player.isFallFlying() && !player.onGround()) {
            return false;
        }

        if (state == DragonState.COMBAT) {
            state = DragonState.PREPARE_FLIGHT;
            takeoffTicks = 0;
            takeoffFireworkCooldownTicks = 0;
            takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
            takeoffImmediateFireworkUsed = false;
            delayTicks = ACTION_DELAY_TICKS;
            sendThrottledProgress(client, message, 20 * 3);
            return true;
        }

        if (state == DragonState.AERIAL_TEAR_SEARCH) {
            Vec3 target = dragonPoolSearchTarget(client);
            performUtilityTakeoff(client, target, message);
            return true;
        }

        if (state == DragonState.SHIP_SEARCH || state == DragonState.SHIP_APPROACH) {
            Vec3 target = activeShipTarget != null ? activeShipTarget.getCenter() : client.player.position().add(32.0D, 18.0D, 0.0D);
            performUtilityTakeoff(client, target, message);
            return true;
        }

        return false;
    }

    private static void performUtilityTakeoff(Minecraft client, Vec3 target, String message) {
        ensureElytraReady(client);
        ensureOffhandFireworkStock(client);
        ensureSwordSelected(client);

        LocalPlayer player = client.player;
        takeoffTicks++;
        GroundMovementController.smoothFacePoint(client, target, 8.0F, 4.0F);
        client.options.keySprint.setDown(true);
        client.options.keyUp.setDown(true);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);

        if (player.onGround() && player.isFallFlying()) {
            player.stopFallFlying();
            takeoffTicks = 0;
            takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
            sendThrottledProgress(client, message, 20 * 3);
            return;
        }

        if (takeoffPhase == TakeoffPhase.DOUBLE_JUMP) {
            boolean firstTap = takeoffTicks >= 1 && takeoffTicks <= 2;
            boolean secondTap = takeoffTicks >= 6 && takeoffTicks <= 8;
            client.options.keyJump.setDown(firstTap || secondTap);
            if (takeoffTicks >= 9) {
                client.options.keyJump.setDown(false);
                takeoffPhase = TakeoffPhase.GLIDE;
                takeoffTicks = 0;
            }
        } else if (takeoffPhase == TakeoffPhase.GLIDE) {
            client.options.keyJump.setDown(false);
            GroundMovementController.smoothLook(client, player.getYRot(), -4.0F, 5.0F, 2.0F);
            if (!player.onGround() && takeoffTicks >= 2 && takeoffTicks % 2 == 0) {
                sendStartFallFlying(client);
            }
            if (player.isFallFlying() && !player.onGround()) {
                takeoffPhase = TakeoffPhase.BOOST;
                takeoffTicks = 0;
                takeoffImmediateFireworkUsed = false;
            }
        } else if (takeoffPhase == TakeoffPhase.BOOST) {
            client.options.keyJump.setDown(false);
            ElytraFlightController.steerToward(client, target, false, true, ELYTRA_FLIGHT_PROFILE);
            if (!takeoffImmediateFireworkUsed
                    && player.isFallFlying()
                    && !player.onGround()
                    && takeoffTicks >= TAKEOFF_IMMEDIATE_FIREWORK_TICKS
                    && useOffhandFirework(client)) {
                takeoffImmediateFireworkUsed = true;
                takeoffFireworkCooldownTicks = TAKEOFF_FIREWORK_COOLDOWN_TICKS;
                fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
                sendThrottledProgress(client, "起飞后使用一发烟花拉起搜索。", 20 * 3);
            }
        }

        if (takeoffTicks > TAKEOFF_TIMEOUT_TICKS) {
            takeoffTicks = 0;
            takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
            takeoffImmediateFireworkUsed = false;
            sendThrottledProgress(client, "起飞暂时失败，继续重试。", 20 * 3);
        } else {
            sendThrottledProgress(client, message, 20 * 3);
        }
    }

    private static void tickShipApproach(Minecraft client) {
        if (hasShipLoot(client)) {
            stop(client, "龙头和鞘翅已经到手，自动找船完成。");
            return;
        }

        Optional<ShipTarget> freshTarget = findNearestShipTarget(client);
        if (freshTarget.isPresent()) {
            activeShipTarget = freshTarget.get().pos();
            shipObjective = freshTarget.get().objective();
        }

        if (activeShipTarget == null) {
            state = DragonState.SHIP_SEARCH;
            return;
        }

        if (ensureExpectedFlight(client, "找船靠近过程中落到地面，重新调用起飞逻辑。")) {
            return;
        }

        ensureElytraReady(client);
        ensureOffhandFireworkStock(client);
        Vec3 target = activeShipTarget.getCenter();
        if (client.player.isFallFlying() && !client.player.onGround()) {
            ElytraFlightController.steerToward(client, target, false, false, ELYTRA_FLIGHT_PROFILE);
            useShipFireworkIfNeeded(client, target);
        } else {
            walkTowardPoint(client, target);
        }

        if (client.player.distanceToSqr(target) <= SHIP_INTERACT_DISTANCE_SQUARED) {
            releaseMovementKeys(client);
            collectShipTarget(client, activeShipTarget, shipObjective);
        } else {
            sendThrottledProgress(client, "正在靠近末地船目标：" + shipObjective.label() + "，距离 " + formatDistance(client.player.distanceToSqr(target) == 0.0D ? 0.0D : Math.sqrt(client.player.distanceToSqr(target))) + " 格。", 20 * 5);
        }
    }

    private static void flyShipSearchPattern(Minecraft client) {
        LocalPlayer player = client.player;
        if (!player.isFallFlying()) {
            if (player.onGround()) {
                client.options.keyJump.setDown(dragonTickCounter % 10 < 4);
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
            } else {
                client.options.keyJump.setDown(false);
                if (player.getDeltaMovement().y < -0.08D && dragonTickCounter % 10 == 0) {
                    sendStartFallFlying(client);
                }
            }
            return;
        }

        double angle = Math.toRadians(shipSearchAngle);
        int ring = 96 + (shipSearchAngle / 360) * 48;
        shipSearchAngle = (shipSearchAngle + 3) % 1440;
        double altitudeOffset = Math.max(6.0D, ELYTRA_SEARCH_ALTITUDE - player.getY());
        Vec3 target = player.position().add(Math.cos(angle) * ring, altitudeOffset, Math.sin(angle) * ring);
        ElytraFlightController.steerToward(client, target, false, true, ELYTRA_FLIGHT_PROFILE);
        if (fireworkCooldownTicks <= 0 && ElytraFlightController.shouldUseTravelFirework(client, target, 80.0D, ELYTRA_FLIGHT_PROFILE)) {
            if (useOffhandFirework(client)) {
                fireworkCooldownTicks = FIREWORK_COOLDOWN_TICKS;
                sendMessage(client, "搜索末地船时使用烟花保持高度。");
            }
        }
    }

    private static void flyDragonTearSearchPattern(Minecraft client) {
        LocalPlayer player = client.player;
        if (!player.isFallFlying()) {
            performUtilityTakeoff(client, dragonPoolSearchTarget(client), "正在重新起飞搜索龙泪。");
            return;
        }

        Vec3 target = dragonPoolSearchTarget(client);
        ElytraFlightController.steerToward(client, target, false, true, ELYTRA_FLIGHT_PROFILE);
        if (fireworkCooldownTicks <= 0) {
            double distanceToPool = horizontalDistance(player.position(), new Vec3(DRAGON_POOL_X + 0.5D, player.getY(), DRAGON_POOL_Z + 0.5D));
            boolean tooLow = GroundMovementController.distanceToGround(client) <= LOW_ALTITUDE_FIREWORK_BLOCKS;
            boolean farOrSlow = distanceToPool > 42.0D || player.getDeltaMovement().horizontalDistanceSqr() < 0.08D;
            if ((tooLow || farOrSlow) && useOffhandFirework(client)) {
                fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
                sendThrottledProgress(client, "搜索龙泪时满足加速条件，使用一发烟花。", 20 * 3);
            }
        }
    }

    private static Vec3 dragonPoolSearchTarget(Minecraft client) {
        LocalPlayer player = client.player;
        double angle = Math.toRadians(aerialTearSearchAngle);
        aerialTearSearchAngle = (aerialTearSearchAngle + 5) % 360;
        double radius = 18.0D + ((dragonTickCounter / 120) % 3) * 10.0D;
        double altitude = Math.max(76.0D, Math.min(112.0D, player.getY() + 10.0D));
        return new Vec3(
                DRAGON_POOL_X + 0.5D + Math.cos(angle) * radius,
                altitude,
                DRAGON_POOL_Z + 0.5D + Math.sin(angle) * radius
        );
    }

    private static void useShipFireworkIfNeeded(Minecraft client, Vec3 target) {
        if (fireworkCooldownTicks > 0 || !client.player.isFallFlying()) {
            return;
        }

        double distance = client.player.position().distanceTo(target);
        if (ElytraFlightController.shouldUseTravelFirework(client, target, 72.0D, ELYTRA_FLIGHT_PROFILE)
                || (client.player.getY() < target.y - 18.0D && distance > 42.0D)) {
            if (useOffhandFirework(client)) {
                fireworkCooldownTicks = FIREWORK_COOLDOWN_TICKS;
                sendMessage(client, "靠近末地船时使用烟花调整高度。");
            }
        }
    }

    private static void collectShipTarget(Minecraft client, BlockPos targetPos, ShipObjective objective) {
        if (objective == ShipObjective.ELYTRA_FRAME) {
            findNearestElytraFrame(client).ifPresent(frame -> {
                GroundMovementController.facePoint(client, frame.position());
                client.gameMode.attack(client.player, frame);
                client.player.swing(InteractionHand.MAIN_HAND);
                sendMessage(client, "已尝试取下鞘翅展示框。");
            });
            delayTicks = ACTION_DELAY_TICKS;
            return;
        }

        if (objective == ShipObjective.DRAGON_HEAD) {
            beginShipHeadMining(client, targetPos);
            return;
        }

        sendMessage(client, "已靠近末地船，继续搜索龙头和鞘翅。");
        state = DragonState.SHIP_SEARCH;
        delayTicks = ACTION_DELAY_TICKS;
    }

    private static void beginShipHeadMining(Minecraft client, BlockPos targetPos) {
        activeShipTarget = targetPos;
        shipObjective = ShipObjective.DRAGON_HEAD;
        shipHeadMineTicks = 0;
        shipHeadMinePrepareTicks = 0;
        shipHeadMiningActive = false;
        state = DragonState.SHIP_MINE_HEAD;
        releaseMovementKeys(client);
        sendMessage(client, "已靠近龙头，准备稳定采集。");
    }

    private static void tickShipMineHead(Minecraft client) {
        releaseMovementKeys(client);
        if (activeShipTarget == null) {
            finishShipHeadMining(client, "龙头目标丢失，继续搜索。");
            return;
        }

        if (!client.level.getBlockState(activeShipTarget).is(Blocks.DRAGON_HEAD)) {
            finishShipHeadMining(client, "龙头已采集，继续搜索鞘翅或其他目标。");
            return;
        }

        if (client.player.distanceToSqr(activeShipTarget.getCenter()) > SHIP_INTERACT_DISTANCE_SQUARED) {
            shipHeadMiningActive = false;
            shipHeadMinePrepareTicks = 0;
            state = DragonState.SHIP_APPROACH;
            sendThrottledProgress(client, "龙头距离变远，重新靠近。", 20 * 4);
            return;
        }

        shipHeadMineTicks++;
        GroundMovementController.smoothFacePoint(client, activeShipTarget.getCenter(), 7.0F, 4.0F);
        if (shipHeadMinePrepareTicks < SHIP_HEAD_MINE_PREPARE_TICKS) {
            shipHeadMinePrepareTicks++;
            sendThrottledProgress(client, "正在对准龙头，准备采集。", 20 * 4);
            return;
        }

        if (!shipHeadMiningActive) {
            client.gameMode.startDestroyBlock(activeShipTarget, Direction.UP);
            shipHeadMiningActive = true;
        }
        client.gameMode.continueDestroyBlock(activeShipTarget, Direction.UP);
        client.player.swing(InteractionHand.MAIN_HAND);

        if (shipHeadMineTicks > SHIP_HEAD_MINE_TIMEOUT_TICKS) {
            finishShipHeadMining(client, "采集龙头等待超时，继续搜索。");
        }
    }

    private static void finishShipHeadMining(Minecraft client, String message) {
        shipHeadMineTicks = 0;
        shipHeadMinePrepareTicks = 0;
        shipHeadMiningActive = false;
        activeShipTarget = null;
        shipObjective = ShipObjective.SEARCH;
        state = DragonState.SHIP_SEARCH;
        delayTicks = ACTION_DELAY_TICKS;
        sendMessage(client, message);
    }

    private static Optional<BlockPos> resolvePortalCenter(Minecraft client) {
        if (manualPortalCenter != null) {
            return Optional.of(manualPortalCenter);
        }

        BlockPos playerPos = client.player.blockPosition();
        Optional<BlockPos> endPortalCenter = findNearestBlockClusterCenter(client, playerPos, Blocks.END_PORTAL);
        if (endPortalCenter.isPresent()) {
            return endPortalCenter;
        }

        Optional<BlockPos> bedrockCenter = findNearestBlockClusterCenter(client, playerPos, Blocks.BEDROCK);
        if (bedrockCenter.isPresent()) {
            return bedrockCenter;
        }

        return Optional.of(BlockPos.containing(DRAGON_POOL_X, playerPos.getY(), DRAGON_POOL_Z));
    }

    private static Optional<BlockPos> findNearestBlockClusterCenter(Minecraft client, BlockPos origin, Block block) {
        List<BlockPos> matches = new ArrayList<>();
        for (int dx = -PORTAL_SCAN_RADIUS; dx <= PORTAL_SCAN_RADIUS; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -PORTAL_SCAN_RADIUS; dz <= PORTAL_SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (client.level.getBlockState(pos).getBlock() == block) {
                        matches.add(pos.immutable());
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        double averageX = 0.0D;
        double averageY = 0.0D;
        double averageZ = 0.0D;
        for (BlockPos pos : matches) {
            averageX += pos.getX();
            averageY += pos.getY();
            averageZ += pos.getZ();
        }
        int size = matches.size();
        return Optional.of(BlockPos.containing(averageX / size, averageY / size, averageZ / size));
    }

    private static Optional<BlockPos> findMissingCrystalBase(Minecraft client) {
        List<BlockPos> bases = findCrystalBases(client);
        for (BlockPos base : bases) {
            if (!hasCrystalNear(client, base.above().getCenter())) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    private static List<BlockPos> findCrystalBases(Minecraft client) {
        if (activePortalCenter == null) {
            return List.of();
        }

        Map<Direction, BlockPos> bestByDirection = new EnumMap<>(Direction.class);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -3; dy <= 5; dy++) {
                    BlockPos pos = activePortalCenter.offset(dx, dy, dz);
                    if (client.level.getBlockState(pos).getBlock() != Blocks.BEDROCK) {
                        continue;
                    }
                    if (!client.level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }

                    Direction direction = cardinalDirection(dx, dz);
                    if (direction == null) {
                        continue;
                    }

                    BlockPos previous = bestByDirection.get(direction);
                    if (previous == null || horizontalDistanceSqr(pos, activePortalCenter) < horizontalDistanceSqr(previous, activePortalCenter)) {
                        bestByDirection.put(direction, pos.immutable());
                    }
                }
            }
        }

        List<BlockPos> bases = new ArrayList<>();
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            BlockPos pos = bestByDirection.get(direction);
            if (pos != null) {
                bases.add(pos);
            }
        }
        return bases;
    }

    private static Direction cardinalDirection(int dx, int dz) {
        double distanceSqr = dx * dx + dz * dz;
        if (distanceSqr < 4.0D || distanceSqr > 25.0D) {
            return null;
        }

        if (Math.abs(dx) <= 1 && Math.abs(dz) >= 2) {
            return dz < 0 ? Direction.NORTH : Direction.SOUTH;
        }
        if (Math.abs(dz) <= 1 && Math.abs(dx) >= 2) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        return null;
    }

    private static boolean hasCrystalNear(Minecraft client, Vec3 target) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof EndCrystal && !entity.isRemoved() && entity.position().distanceToSqr(target) <= CRYSTAL_PRESENT_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private static void placeCrystal(Minecraft client, BlockPos basePos) {
        GroundMovementController.faceBlockCenter(client, basePos);
        BlockHitResult hitResult = new BlockHitResult(basePos.getCenter().add(0.0D, 0.5D, 0.0D), Direction.UP, basePos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static Optional<BlockPos> findCrystalStandPos(Minecraft client, BlockPos basePos) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos candidate = basePos.offset(dx, dy, dz);
                        if (isSafeStandingPos(client, candidate)
                                && !isInsidePortalAvoidance(candidate.getCenter())
                                && isOutsideOfPortalBase(basePos, candidate)
                                && candidate.getCenter().distanceToSqr(basePos.getCenter()) <= CRYSTAL_INTERACT_DISTANCE_SQUARED) {
                            candidates.add(candidate.immutable());
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }

        return candidates.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> Math.abs(PORTAL_BYPASS_RADIUS - horizontalDistance(pos.getCenter(), activePortalCenter.getCenter())))
                        .thenComparingDouble(pos -> client.player.distanceToSqr(pos.getCenter()))
                        .thenComparingDouble(pos -> pos.getCenter().distanceToSqr(basePos.getCenter())));
    }

    private static boolean isOutsideOfPortalBase(BlockPos basePos, BlockPos candidate) {
        if (activePortalCenter == null) {
            return true;
        }
        Vec3 center = activePortalCenter.getCenter();
        Vec3 baseVector = new Vec3(basePos.getX() + 0.5D - center.x, 0.0D, basePos.getZ() + 0.5D - center.z);
        Vec3 candidateVector = new Vec3(candidate.getX() + 0.5D - center.x, 0.0D, candidate.getZ() + 0.5D - center.z);
        if (baseVector.lengthSqr() < 1.0E-4D || candidateVector.lengthSqr() < 1.0E-4D) {
            return false;
        }
        return candidateVector.normalize().dot(baseVector.normalize()) > 0.25D
                && candidateVector.lengthSqr() >= baseVector.lengthSqr();
    }

    private static boolean isSafeStandingPos(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).isAir()
                && client.level.getBlockState(pos.above()).isAir()
                && client.level.getFluidState(pos).isEmpty()
                && client.level.getFluidState(pos.above()).isEmpty()
                && client.level.getBlockState(pos.below()).blocksMotion();
    }

    private static void walkTowardBlock(Minecraft client, BlockPos targetPos) {
        Vec3 target = avoidPortalForGroundPath(client, targetPos.getCenter());
        if (handlePitDigging(client, target)) {
            return;
        }
        GroundMovementController.walkToward(client, target, 0.35D, 3.0D,
                targetPos.getY() > client.player.blockPosition().getY(), true, 9.0F);
    }

    private static Optional<BlockPos> findShulkerPlacementPos(Minecraft client) {
        BlockPos playerPos = client.player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 2; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        if (client.player.distanceToSqr(pos.getCenter()) >= SHULKER_MIN_PLACE_DISTANCE_SQUARED
                                && canPlaceShulkerAt(client, pos)) {
                            candidates.add(pos.immutable());
                        }
                    }
                }
            }
            if (radius >= 3 && !candidates.isEmpty()) {
                break;
            }
        }

        return candidates.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> Math.abs(client.player.distanceToSqr(pos.getCenter()) - SHULKER_IDEAL_PLACE_DISTANCE_SQUARED))
                        .thenComparingDouble(pos -> client.player.distanceToSqr(pos.getCenter())));
    }

    private static Optional<BlockPos> findNearestLandingPos(Minecraft client) {
        BlockPos playerPos = client.player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 0; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 4; dy >= -48; dy--) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        if (isSafeStandingPos(client, pos)) {
                            candidates.add(pos.immutable());
                            break;
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }

        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> client.player.distanceToSqr(pos.getCenter())));
    }

    private static boolean canPlaceShulkerAt(Minecraft client, BlockPos pos) {
        if (client.player.getEyePosition().distanceToSqr(pos.getCenter()) > CRYSTAL_INTERACT_DISTANCE_SQUARED) {
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

    private static boolean handlePitDigging(Minecraft client, Vec3 target) {
        LocalPlayer player = client.player;
        if (player.isFallFlying() || player.onGround() && !player.horizontalCollision && groundStuckTicks <= 0) {
            clearDragonMining(client);
            return false;
        }

        double horizontalSpeed = player.getDeltaMovement().horizontalDistanceSqr();
        if (player.horizontalCollision || horizontalSpeed < 0.001D) {
            groundStuckTicks++;
        } else {
            groundStuckTicks = 0;
            clearDragonMining(client);
            return false;
        }

        if (groundStuckTicks < 12 && activeMineTarget == null) {
            return false;
        }

        BlockPos targetBlock = activeMineTarget != null ? activeMineTarget : findPitEscapeMineTarget(client, target).orElse(null);
        if (targetBlock == null) {
            return false;
        }

        if (!ensurePickaxeSelected(client)) {
            sendThrottledProgress(client, "疑似掉坑，但背包没有镐子可挖出路。", 20 * 4);
            return false;
        }

        mineBlock(client, targetBlock);
        sendThrottledProgress(client, "疑似掉坑，正在用镐挖出路。", 20 * 3);
        return true;
    }

    private static Optional<BlockPos> findPitEscapeMineTarget(Minecraft client, Vec3 target) {
        LocalPlayer player = client.player;
        BlockPos playerPos = player.blockPosition();
        Vec3 direction = new Vec3(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (direction.lengthSqr() < 1.0E-4D) {
            direction = Vec3.directionFromRotation(0.0F, player.getYRot());
        } else {
            direction = direction.normalize();
        }

        List<BlockPos> candidates = List.of(
                playerPos.above(2),
                playerPos.above(),
                BlockPos.containing(player.getX() + direction.x, player.getY() + 1.0D, player.getZ() + direction.z),
                BlockPos.containing(player.getX() + direction.x, player.getY(), player.getZ() + direction.z)
        );
        for (BlockPos candidate : candidates) {
            if (isDragonMineable(client, candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean ensurePickaxeSelected(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPickaxeStack(stack)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }

        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPickaxeStack(stack)) {
                int hotbarSlot = findReplaceableHotbarSlot(client);
                moveInventorySlotToHotbar(client, slot, hotbarSlot);
                inventory.setSelectedSlot(hotbarSlot);
                delayTicks = ACTION_DELAY_TICKS;
                return true;
            }
        }
        return false;
    }

    private static boolean isPickaxeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase();
        String name = normalizeName(stack.getHoverName().getString());
        return id.endsWith("_pickaxe") || id.contains("pickaxe") || name.contains("镐");
    }

    private static boolean isDragonMineable(Minecraft client, BlockPos pos) {
        if (pos == null || client.player.getEyePosition().distanceToSqr(pos.getCenter()) > 25.0D) {
            return false;
        }
        if (client.level.getFluidState(pos).isEmpty()) {
            Block block = client.level.getBlockState(pos).getBlock();
            return !client.level.getBlockState(pos).isAir()
                    && block != Blocks.BEDROCK
                    && block != Blocks.END_PORTAL
                    && block != Blocks.END_PORTAL_FRAME
                    && block != Blocks.BARRIER
                    && block != Blocks.COMMAND_BLOCK
                    && block != Blocks.CHAIN_COMMAND_BLOCK
                    && block != Blocks.REPEATING_COMMAND_BLOCK;
        }
        return false;
    }

    private static void mineBlock(Minecraft client, BlockPos pos) {
        if (!pos.equals(activeMineTarget)) {
            activeMineTarget = pos.immutable();
            dragonMiningActive = false;
            dragonMiningTicks = 0;
        }
        Direction side = Direction.getApproximateNearest(
                client.player.getX() - (pos.getX() + 0.5D),
                client.player.getEyeY() - (pos.getY() + 0.5D),
                client.player.getZ() - (pos.getZ() + 0.5D)
        );
        GroundMovementController.smoothFacePoint(client, pos.getCenter(), 10.0F, 6.0F);
        if (!dragonMiningActive) {
            client.gameMode.startDestroyBlock(pos, side);
            dragonMiningActive = true;
        }
        client.gameMode.continueDestroyBlock(pos, side);
        client.player.swing(InteractionHand.MAIN_HAND);
        dragonMiningTicks++;
        if (!isDragonMineable(client, pos) || dragonMiningTicks > PIT_DIG_TIMEOUT_TICKS) {
            clearDragonMining(client);
        }
    }

    private static void clearDragonMining(Minecraft client) {
        activeMineTarget = null;
        dragonMiningActive = false;
        dragonMiningTicks = 0;
        groundStuckTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyAttack.setDown(false);
        }
    }

    private static void steerTowardDragon(Minecraft client, EnderDragon dragon) {
        LocalPlayer player = client.player;
        Entity targetEntity = nearestDragonHitbox(client, dragon).orElse(dragon);
        Vec3 targetPoint = targetEntity.getBoundingBox().getCenter();
        double hitboxDistanceSqr = distanceToHitboxSqr(player.getEyePosition(), targetEntity);
        int groundDistance = GroundMovementController.distanceToGround(client);

        if (hitboxDistanceSqr > MELEE_REACH_SQUARED) {
            Vec3 velocity = targetEntity.getDeltaMovement();
            double leadTicks = hitboxDistanceSqr > 36.0D ? 8.0D : 3.0D;
            targetPoint = targetPoint.add(velocity.scale(leadTicks));
        }

        if (player.isFallFlying() && groundDistance <= LOW_ALTITUDE_FIREWORK_BLOCKS + 4) {
            double lift = groundDistance <= CRITICAL_ALTITUDE_FIREWORK_BLOCKS + 2 ? 20.0D : 12.0D;
            targetPoint = targetPoint.add(0.0D, lift, 0.0D);
            sendThrottledProgress(client, "高度过低，正在抬头修正飞行姿态。", 20 * 3);
        }

        boolean attackable = hitboxDistanceSqr <= MELEE_APPROACH_DISTANCE_SQUARED;
        ElytraFlightController.steerToward(client, targetPoint, attackable, false, ELYTRA_FLIGHT_PROFILE);
        sendThrottledProgress(client, "贴近末影龙攻击范围中，距碰撞箱 "
                + formatDistance(Math.sqrt(hitboxDistanceSqr)) + " 格。", 20 * 5);
    }

    private static void navigateToDragonTear(Minecraft client, ItemEntity drop) {
        if (client.player.isFallFlying() && !client.player.onGround()) {
            GroundMovementController.steerToward(client, drop.position(), false, 8.0F, 5.5F);
            return;
        }

        client.player.stopFallFlying();
        walkTowardPoint(client, drop.position());
    }

    private static boolean handleFlightObstacleEscape(Minecraft client) {
        LocalPlayer player = client.player;
        if (!player.isFallFlying() || player.onGround()) {
            resetFlightObstacleEscape(client);
            return false;
        }

        Vec3 currentPos = player.position();
        if (lastFlightPos == Vec3.ZERO) {
            lastFlightPos = currentPos;
            return false;
        }

        if (obstacleEscapeTicks > 0) {
            obstacleEscapeTicks--;
            GroundMovementController.smoothLook(client, obstacleEscapeYaw, -22.0F, 12.0F, 5.5F);
            GroundMovementController.steerForward(client);
            lastFlightPos = currentPos;
            sendThrottledProgress(client, "疑似卡住黑曜石柱，正在转向脱困。", 20 * 2);
            return true;
        }

        double dx = currentPos.x - lastFlightPos.x;
        double dy = currentPos.y - lastFlightPos.y;
        double dz = currentPos.z - lastFlightPos.z;
        double horizontalMoveSqr = dx * dx + dz * dz;
        boolean barelyMoved = horizontalMoveSqr < 0.0025D && Math.abs(dy) < 0.04D;
        boolean colliding = player.horizontalCollision || player.verticalCollision;
        if ((barelyMoved || colliding) && player.getDeltaMovement().horizontalDistanceSqr() < 0.015D) {
            flightStuckTicks++;
        } else {
            flightStuckTicks = 0;
        }
        lastFlightPos = currentPos;

        if (flightStuckTicks < OBSTACLE_STUCK_CONFIRM_TICKS) {
            return false;
        }

        flightStuckTicks = 0;
        obstacleEscapeTicks = OBSTACLE_ESCAPE_TICKS;
        obstacleEscapeYaw = player.getYRot() + (dragonTickCounter % 2 == 0 ? 95.0F : -95.0F);
        GroundMovementController.smoothLook(client, obstacleEscapeYaw, -22.0F, 12.0F, 5.5F);
        GroundMovementController.steerForward(client);
        sendMessage(client, "检测到飞行位置几乎不变，疑似卡柱，正在转头脱困。");
        return true;
    }

    private static void resetFlightObstacleEscape(Minecraft client) {
        lastFlightPos = client != null && client.player != null ? client.player.position() : Vec3.ZERO;
        flightStuckTicks = 0;
        obstacleEscapeTicks = 0;
        obstacleEscapeYaw = 0.0F;
    }

    private static void walkTowardPoint(Minecraft client, Vec3 target) {
        target = avoidPortalForGroundPath(client, target);
        if (handlePitDigging(client, target)) {
            return;
        }
        GroundMovementController.walkToward(client, target, 0.35D, 4.0D, false, true, 9.0F);
    }

    private static Vec3 avoidPortalForGroundPath(Minecraft client, Vec3 target) {
        if (activePortalCenter == null) {
            return target;
        }

        Vec3 portal = activePortalCenter.getCenter();
        Vec3 player = client.player.position();
        Vec3 safeTarget = pushOutsidePortal(target, portal);
        if (!lineCrossesPortal(player, safeTarget, portal)) {
            return safeTarget;
        }

        Vec3 fromPortal = new Vec3(player.x - portal.x, 0.0D, player.z - portal.z);
        if (fromPortal.lengthSqr() < 1.0E-4D) {
            fromPortal = new Vec3(safeTarget.x - portal.x, 0.0D, safeTarget.z - portal.z);
        }
        if (fromPortal.lengthSqr() < 1.0E-4D) {
            fromPortal = new Vec3(1.0D, 0.0D, 0.0D);
        }

        Vec3 radial = fromPortal.normalize();
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        Vec3 toTarget = new Vec3(safeTarget.x - player.x, 0.0D, safeTarget.z - player.z);
        if (tangent.dot(toTarget) < 0.0D) {
            tangent = tangent.scale(-1.0D);
        }

        Vec3 bypass = portal.add(radial.scale(PORTAL_BYPASS_RADIUS)).add(tangent.scale(3.0D));
        sendThrottledProgress(client, "正在绕开龙池传送门。", 20 * 5);
        return new Vec3(bypass.x, safeTarget.y, bypass.z);
    }

    private static Vec3 pushOutsidePortal(Vec3 target, Vec3 portal) {
        Vec3 offset = new Vec3(target.x - portal.x, 0.0D, target.z - portal.z);
        double distance = Math.sqrt(offset.lengthSqr());
        if (distance >= PORTAL_AVOID_RADIUS) {
            return target;
        }
        if (distance < 1.0E-4D) {
            offset = new Vec3(1.0D, 0.0D, 0.0D);
            distance = 1.0D;
        }
        Vec3 pushed = portal.add(offset.scale(PORTAL_BYPASS_RADIUS / distance));
        return new Vec3(pushed.x, target.y, pushed.z);
    }

    private static boolean isInsidePortalAvoidance(Vec3 pos) {
        return activePortalCenter != null
                && horizontalDistance(pos, activePortalCenter.getCenter()) < PORTAL_AVOID_RADIUS;
    }

    private static boolean lineCrossesPortal(Vec3 from, Vec3 to, Vec3 portal) {
        double ax = from.x;
        double az = from.z;
        double bx = to.x;
        double bz = to.z;
        double abx = bx - ax;
        double abz = bz - az;
        double lengthSqr = abx * abx + abz * abz;
        if (lengthSqr < 1.0E-4D) {
            return horizontalDistance(from, portal) < PORTAL_AVOID_RADIUS;
        }
        double t = ((portal.x - ax) * abx + (portal.z - az) * abz) / lengthSqr;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double closestX = ax + abx * t;
        double closestZ = az + abz * t;
        double dx = closestX - portal.x;
        double dz = closestZ - portal.z;
        return dx * dx + dz * dz < PORTAL_AVOID_RADIUS * PORTAL_AVOID_RADIUS;
    }

    private static boolean ensureElytraReady(Minecraft client) {
        ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA)) {
            return true;
        }

        if (!selectOrMoveToHotbar(client, stack -> stack.is(Items.ELYTRA), "鞘翅")) {
            return false;
        }

        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        client.player.swing(InteractionHand.MAIN_HAND);
        delayTicks = ACTION_DELAY_TICKS;
        return client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static void useFireworkIfNeeded(Minecraft client, EnderDragon dragon) {
        if (fireworkCooldownTicks > 0 || !client.player.isFallFlying()) {
            return;
        }

        if (useEmergencyFireworkIfNeeded(client)) {
            return;
        }

        Entity targetEntity = nearestDragonHitbox(client, dragon).orElse(dragon);
        double distance = Math.sqrt(distanceToHitboxSqr(client.player.getEyePosition(), targetEntity));
        double speedSqr = client.player.getDeltaMovement().lengthSqr();
        boolean closing = lastDragonDistance > 0.0D && distance < lastDragonDistance - 0.85D;
        lastDragonDistance = distance;

        boolean outsideAttackSetup = distance > 8.0D;
        boolean farFromDragon = distance > 30.0D;
        boolean notClosingEnough = distance > 14.0D && !closing;
        boolean tooSlowToClose = distance > 10.0D && speedSqr < 0.16D;
        boolean shouldBoost = outsideAttackSetup && (farFromDragon || notClosingEnough || tooSlowToClose);
        if (!shouldBoost) {
            return;
        }

        if (!useOffhandFirework(client)) {
            return;
        }

        fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
        sendMessage(client, "满足追龙加速条件，使用一发副手烟花。");
    }

    private static boolean useEmergencyFireworkIfNeeded(Minecraft client) {
        if (fireworkCooldownTicks > 0 || !client.player.isFallFlying()) {
            return false;
        }

        int groundDistance = GroundMovementController.distanceToGround(client);
        double verticalSpeed = client.player.getDeltaMovement().y;
        double predictedGroundDistance = groundDistance + Math.min(0.0D, verticalSpeed) * 10.0D;
        if (groundDistance <= CRITICAL_ALTITUDE_FIREWORK_BLOCKS - 1
                || predictedGroundDistance <= CRITICAL_ALTITUDE_FIREWORK_BLOCKS
                || (groundDistance <= LOW_ALTITUDE_FIREWORK_BLOCKS && verticalSpeed < -0.22D)) {
            if (useOffhandFirework(client)) {
                fireworkCooldownTicks = COMBAT_FIREWORK_COOLDOWN_TICKS;
                sendMessage(client, "高度过低，自动使用烟花拉起。");
                return true;
            }
        }
        return false;
    }

    private static boolean useOffhandFirework(Minecraft client) {
        if (!ensureOffhandFireworkStock(client)) {
            return false;
        }

        client.gameMode.useItem(client.player, InteractionHand.OFF_HAND);
        client.player.swing(InteractionHand.OFF_HAND);
        return true;
    }

    private static boolean ensureOffhandFireworkStock(Minecraft client) {
        if (client.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            return true;
        }

        int hotbarSlot = findHotbarItem(client, stack -> stack.is(Items.FIREWORK_ROCKET));
        if (hotbarSlot < 0) {
            if (!selectOrMoveToHotbar(client, stack -> stack.is(Items.FIREWORK_ROCKET), "烟花火箭")) {
                sendThrottledProgress(client, "背包里没有烟花火箭，暂时无法加速。", 20 * 8);
                return false;
            }
            hotbarSlot = client.player.getInventory().getSelectedSlot();
        }

        client.gameMode.handleInventoryMouseClick(
                client.player.inventoryMenu.containerId,
                InventoryMenu.SHIELD_SLOT,
                hotbarSlot,
                ClickType.SWAP,
                client.player
        );
        sendMessage(client, "已从热键栏补一组烟花到副手。");
        delayTicks = ACTION_DELAY_TICKS;
        return true;
    }

    private static void ensureSwordSelected(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        ItemStack firstSlot = inventory.getItem(0);
        if (!isSwordStack(firstSlot)) {
            sendThrottledProgress(client, "快捷栏1不是剑，自动近身攻击会等待你把剑放到1号位。", 20 * 8);
            return;
        }

        if (inventory.getSelectedSlot() != 0) {
            inventory.setSelectedSlot(0);
        }
    }

    private static void tickLegalDragonAura(Minecraft client, EnderDragon dragon) {
        if (!builtInAuraActive) {
            return;
        }
        if (!isSwordStack(client.player.getInventory().getItem(0))) {
            return;
        }
        if (client.player.getAttackStrengthScale(0.0F) < 0.92F) {
            return;
        }

        Entity target = nearestAttackableDragonPart(client, dragon).orElse(dragon);
        if (distanceToHitboxSqr(client.player.getEyePosition(), target) > MELEE_REACH_SQUARED) {
            return;
        }

        ensureSwordSelected(client);
        GroundMovementController.smoothFacePoint(client, target.getBoundingBox().getCenter(), 16.0F, 10.0F);
        client.gameMode.attack(client.player, target);
        client.player.swing(InteractionHand.MAIN_HAND);
        sendThrottledProgress(client, "内建合法光环已在近战范围内攻击末影龙。", 20 * 3);
    }

    private static Optional<Entity> nearestAttackableDragonPart(Minecraft client, EnderDragon dragon) {
        Optional<Entity> best = nearestDragonHitbox(client, dragon);
        if (best.isPresent() && distanceToHitboxSqr(client.player.getEyePosition(), best.get()) <= MELEE_REACH_SQUARED) {
            return best;
        }
        return distanceToHitboxSqr(client.player.getEyePosition(), dragon) <= MELEE_REACH_SQUARED
                ? Optional.of(dragon)
                : Optional.empty();
    }

    private static Optional<Entity> nearestDragonHitbox(Minecraft client, EnderDragon dragon) {
        Vec3 playerPos = client.player.position();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnderDragonPart part : client.level.dragonParts()) {
            if (part.isRemoved()) {
                continue;
            }
            double distance = distanceToHitboxSqr(playerPos, part);
            if (distance < bestDistance) {
                best = part;
                bestDistance = distance;
            }
        }

        double dragonDistance = distanceToHitboxSqr(playerPos, dragon);
        if (dragonDistance < bestDistance) {
            return Optional.of(dragon);
        }
        return Optional.ofNullable(best);
    }

    private static double distanceToHitboxSqr(Vec3 point, Entity entity) {
        AABB box = entity.getBoundingBox();
        double x = clamp(point.x, box.minX, box.maxX);
        double y = clamp(point.y, box.minY, box.maxY);
        double z = clamp(point.z, box.minZ, box.maxZ);
        double dx = point.x - x;
        double dy = point.y - y;
        double dz = point.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean selectHotbarItem(Minecraft client, Item item) {
        int slot = findHotbarItem(client, stack -> stack.is(item));
        if (slot >= 0) {
            client.player.getInventory().setSelectedSlot(slot);
            return true;
        }
        return false;
    }

    private static boolean selectOrMoveToHotbar(Minecraft client, java.util.function.Predicate<ItemStack> predicate, String label) {
        int hotbarSlot = findHotbarItem(client, predicate);
        if (hotbarSlot >= 0) {
            client.player.getInventory().setSelectedSlot(hotbarSlot);
            return true;
        }

        int inventorySlot = findInventoryItem(client, predicate);
        if (inventorySlot < 0) {
            return false;
        }

        int targetHotbarSlot = findReplaceableHotbarSlot(client);
        moveInventorySlotToHotbar(client, inventorySlot, targetHotbarSlot);
        client.player.getInventory().setSelectedSlot(targetHotbarSlot);
        sendMessage(client, "已从背包把 " + label + " 换到热键栏 " + (targetHotbarSlot + 1) + "。");
        delayTicks = ACTION_DELAY_TICKS;
        return true;
    }

    private static int findHotbarItem(Minecraft client, java.util.function.Predicate<ItemStack> predicate) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (predicate.test(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findInventoryItem(Minecraft client, java.util.function.Predicate<ItemStack> predicate) {
        Inventory inventory = client.player.getInventory();
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (predicate.test(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findReplaceableHotbarSlot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 1; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }

        for (int slot = 1; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isSwordStack(stack) && !stack.is(Items.FIREWORK_ROCKET) && !stack.is(Items.END_CRYSTAL)) {
                return slot;
            }
        }

        return Math.max(1, inventory.getSelectedSlot());
    }

    private static void moveInventorySlotToHotbar(Minecraft client, int inventorySlot, int hotbarSlot) {
        int menuSlot = inventorySlotToMenuSlot(inventorySlot);
        client.gameMode.handleInventoryMouseClick(
                client.player.inventoryMenu.containerId,
                menuSlot,
                hotbarSlot,
                ClickType.SWAP,
                client.player
        );
    }

    private static int inventorySlotToMenuSlot(int inventorySlot) {
        return Inventory.isHotbarSlot(inventorySlot)
                ? InventoryMenu.USE_ROW_SLOT_START + inventorySlot
                : inventorySlot;
    }

    private static boolean isSwordStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase();
        return id.endsWith("_sword") || id.contains(":sword") || id.contains("sword");
    }

    private static Optional<EnderDragon> findNearestDragon(Minecraft client) {
        if (!isClientReady(client)) {
            return Optional.empty();
        }

        Vec3 playerPos = client.player.position();
        return iterableToList(client.level.entitiesForRendering()).stream()
                .filter(entity -> entity instanceof EnderDragon)
                .map(entity -> (EnderDragon) entity)
                .filter(dragon -> dragon.isAlive() && !dragon.isRemoved())
                .filter(dragon -> dragon.distanceToSqr(playerPos) <= DRAGON_SEARCH_DISTANCE_SQUARED)
                .min(Comparator.comparingDouble(dragon -> dragon.distanceToSqr(playerPos)));
    }

    private static Optional<EnderDragon> findTrackedDragon(Minecraft client) {
        if (!isClientReady(client)) {
            return Optional.empty();
        }

        if (lockedDragonId >= 0) {
            Entity entity = client.level.getEntity(lockedDragonId);
            if (entity instanceof EnderDragon dragon && dragon.isAlive() && !dragon.isRemoved()) {
                return Optional.of(dragon);
            }
        }

        Optional<EnderDragon> nearest = findNearestDragon(client);
        nearest.ifPresent(dragon -> lockedDragonId = dragon.getId());
        return nearest;
    }

    private static List<Entity> iterableToList(Iterable<Entity> entities) {
        List<Entity> list = new ArrayList<>();
        for (Entity entity : entities) {
            list.add(entity);
        }
        return list;
    }

    private static Optional<ItemEntity> findNearestDragonTearDrop(Minecraft client) {
        Vec3 playerPos = client.player.position();
        return iterableToList(client.level.entitiesForRendering()).stream()
                .filter(entity -> entity instanceof ItemEntity)
                .map(entity -> (ItemEntity) entity)
                .filter(itemEntity -> !itemEntity.isRemoved())
                .filter(itemEntity -> isDragonTearStack(itemEntity.getItem()))
                .filter(itemEntity -> itemEntity.distanceToSqr(playerPos) <= SCAN_RADIUS * SCAN_RADIUS)
                .min(Comparator.comparingDouble(itemEntity -> itemEntity.distanceToSqr(playerPos)));
    }

    private static Optional<ShipTarget> findNearestShipTarget(Minecraft client) {
        if (shipScanCooldownTicks > 0) {
            shipScanCooldownTicks--;
        } else {
            shipScanCooldownTicks = SHIP_SCAN_INTERVAL_TICKS;
        }

        Optional<ItemFrame> elytraFrame = findNearestElytraFrame(client);
        if (elytraFrame.isPresent()) {
            return Optional.of(new ShipTarget(elytraFrame.get().blockPosition(), ShipObjective.ELYTRA_FRAME));
        }

        Optional<BlockPos> dragonHead = findNearestBlock(client, Blocks.DRAGON_HEAD, 96, 48);
        if (dragonHead.isPresent()) {
            return Optional.of(new ShipTarget(dragonHead.get(), ShipObjective.DRAGON_HEAD));
        }

        Optional<BlockPos> shipHint = findNearestShipBlock(client);
        return shipHint.map(pos -> new ShipTarget(pos, ShipObjective.SHIP_BODY));
    }

    private static Optional<ItemFrame> findNearestElytraFrame(Minecraft client) {
        Vec3 playerPos = client.player.position();
        return iterableToList(client.level.entitiesForRendering()).stream()
                .filter(entity -> entity instanceof ItemFrame)
                .map(entity -> (ItemFrame) entity)
                .filter(frame -> !frame.isRemoved() && frame.getItem().is(Items.ELYTRA))
                .min(Comparator.comparingDouble(frame -> frame.distanceToSqr(playerPos)));
    }

    private static Optional<BlockPos> findNearestShipBlock(Minecraft client) {
        List<Block> shipBlocks = List.of(Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_SLAB, Blocks.PURPUR_STAIRS, Blocks.END_ROD);
        BlockPos playerPos = client.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -96; dx <= 96; dx += 2) {
            for (int dy = -48; dy <= 48; dy += 2) {
                for (int dz = -96; dz <= 96; dz += 2) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (!shipBlocks.contains(client.level.getBlockState(pos).getBlock())) {
                        continue;
                    }
                    double score = pos.getCenter().distanceToSqr(client.player.position());
                    if (score < bestScore) {
                        best = pos.immutable();
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<BlockPos> findNearestBlock(Minecraft client, Block block, int horizontalRadius, int verticalRadius) {
        BlockPos playerPos = client.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (client.level.getBlockState(pos).getBlock() != block) {
                        continue;
                    }
                    double score = pos.getCenter().distanceToSqr(client.player.position());
                    if (score < bestScore) {
                        best = pos.immutable();
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean hasShipLoot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        boolean hasElytra = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        boolean hasDragonHead = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            hasElytra = hasElytra || stack.is(Items.ELYTRA);
            hasDragonHead = hasDragonHead || stack.is(Items.DRAGON_HEAD);
        }
        return hasElytra && hasDragonHead;
    }

    private static boolean isDragonTearStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String name = normalizeName(stack.getHoverName().getString());
        return name.equals("龙泪");
    }

    private static int quickMoveDragonTearsIntoOpenContainer(Minecraft client, AbstractContainerScreen<?> screen) {
        int movedCount = 0;
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = playerInventoryStart; slotIndex < slots.size(); slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (!isDragonTearStack(stack)) {
                continue;
            }

            movedCount += stack.getCount();
            client.gameMode.handleInventoryMouseClick(
                    screen.getMenu().containerId,
                    slotIndex,
                    0,
                    ClickType.QUICK_MOVE,
                    client.player
            );
        }
        return movedCount;
    }

    private static void reportDragonTearStorage(Minecraft client) {
        int tears = countDragonTearsInInventory(client);
        if (tears <= 0) {
            sendMessage(client, "没有在背包里检测到龙泪，可能已被服务器自动处理或没有掉落。");
            return;
        }

        if (hasInventoryFreeSlot(client)) {
            sendMessage(client, "已拾取龙泪 " + tears + " 个，背包还有空位。");
            return;
        }

        if (hasShulkerBoxInInventory(client)) {
            sendMessage(client, "已拾取龙泪 " + tears + " 个；检测到潜影盒，但这次没有成功打开存入，龙泪暂留背包。");
            return;
        }

        sendMessage(client, "已拾取龙泪 " + tears + " 个，但背包没有空位，也没有检测到潜影盒。");
    }

    private static int countDragonTearsInInventory(Minecraft client) {
        int count = 0;
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isDragonTearStack(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean hasInventoryFreeSlot(Minecraft client) {
        return client.player.getInventory().getFreeSlot() >= 0;
    }

    private static boolean hasShulkerBoxInInventory(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isShulkerBoxStack(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShulkerBoxStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return true;
        }

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase();
        String name = normalizeName(stack.getHoverName().getString());
        return id.contains("shulker_box") || name.contains("潜影盒") || name.contains("shulkerbox");
    }

    private static void enableBuiltInAura() {
        builtInAuraActive = true;
    }

    private static void disableBuiltInAura() {
        builtInAuraActive = false;
    }

    private static void stop(Minecraft client, String message) {
        disableBuiltInAura();
        releaseMovementKeys(client);
        enabled = false;
        state = DragonState.IDLE;
        delayTicks = 0;
        stateTimeoutTicks = 0;
        fireworkCooldownTicks = 0;
        missingDragonTicks = 0;
        returnCommandCooldownTicks = 0;
        progressMessageCooldownTicks = 0;
        takeoffTicks = 0;
        takeoffFireworkCooldownTicks = 0;
        takeoffFailureCount = 0;
        takeoffPhase = TakeoffPhase.DOUBLE_JUMP;
        takeoffImmediateFireworkUsed = false;
        lastDragonDistance = -1.0D;
        resetFlightObstacleEscape(client);
        returnToEndArmed = false;
        shipMode = false;
        shipVoidDeathArmed = false;
        lockedDragonId = -1;
        shipSearchAngle = 0;
        shipScanCooldownTicks = 0;
        shipReturnCommandCooldownTicks = 0;
        shipHeadMineTicks = 0;
        shipHeadMinePrepareTicks = 0;
        shipHeadMiningActive = false;
        activePortalCenter = null;
        pendingCrystalBase = null;
        pendingCrystalStandPos = null;
        activeShulkerPos = null;
        activeShipTarget = null;
        takeoffStandPos = null;
        aerialTearSearchAngle = 0;
        clearDragonMining(client);
        shipObjective = ShipObjective.SEARCH;
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static void releaseMovementKeys(Minecraft client) {
        GroundMovementController.release(client);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.getConnection() != null && client.gameMode != null;
    }

    private static boolean handleDeathAndReturn(Minecraft client) {
        if (!client.player.isDeadOrDying() && !(client.screen instanceof DeathScreen)) {
            return false;
        }

        disableBuiltInAura();
        releaseMovementKeys(client);
        if (shipMode) {
            boolean voidDeath = shipVoidDeathArmed || client.player.getY() < -32.0D;
            shipVoidDeathArmed = false;
            if (voidDeath) {
                stop(client, "找船时掉入虚空死亡，已停止。请手动接管避免循环死亡。");
                if (client.screen instanceof DeathScreen || client.player.isDeadOrDying()) {
                    client.player.respawn();
                    client.setScreen(null);
                }
                return true;
            }

            if (client.screen instanceof DeathScreen || client.player.isDeadOrDying()) {
                client.player.respawn();
                client.setScreen(null);
            }
            state = DragonState.SHIP_SEARCH;
            if (shipReturnCommandCooldownTicks <= 0) {
                client.getConnection().sendCommand("back");
                shipReturnCommandCooldownTicks = SHIP_RETURN_COMMAND_COOLDOWN_TICKS;
                sendMessage(client, "找船时死亡，已发送 /back 回死亡点继续。");
            }
            return true;
        }

        returnToEndArmed = true;
        state = DragonState.RETURNING_TO_END;
        if (client.screen instanceof DeathScreen || client.player.isDeadOrDying()) {
            sendThrottledProgress(client, "检测到死亡，正在复活并准备 /res tp end。", 20 * 3);
            client.player.respawn();
            client.setScreen(null);
        }
        return true;
    }

    private static void tickReturnToEnd(Minecraft client) {
        disableBuiltInAura();
        releaseMovementKeys(client);
        state = DragonState.RETURNING_TO_END;

        if (client.level.dimension() == Level.END) {
            sendMessage(client, "已回到末地，继续追踪末影龙。");
            returnToEndArmed = false;
            state = DragonState.SCAN;
            delayTicks = 20;
            return;
        }

        if (!returnToEndArmed && client.level.dimension() != Level.END) {
            returnToEndArmed = true;
        }

        if (returnCommandCooldownTicks > 0) {
            return;
        }

        client.getConnection().sendCommand("res tp end");
        returnCommandCooldownTicks = RETURN_COMMAND_COOLDOWN_TICKS;
        sendMessage(client, "已发送 /res tp end，等待服务器传送到末地。");
    }

    private static double horizontalDistanceSqr(BlockPos left, BlockPos right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static double horizontalDistance(Vec3 left, Vec3 right) {
        double dx = left.x - right.x;
        double dz = left.z - right.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void sendStatus(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("状态 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(state.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" | 启用 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(enabled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 内建光环 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(builtInAuraActive)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 龙池 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(activePortalCenter == null ? "-" : formatBlockPos(activePortalCenter)).withStyle(ChatFormatting.LIGHT_PURPLE));
        sendMessage(client, message);
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

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String formatDistance(double distance) {
        return Integer.toString((int) Math.round(distance));
    }

    private static String normalizeName(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replaceAll("§.", "").replaceAll("\\s+", "").toLowerCase();
    }

    private enum DragonState {
        IDLE,
        SCAN,
        PLACE_CRYSTALS,
        NAVIGATE_TO_CRYSTAL,
        WAIT_FOR_DRAGON,
        PREPARE_FLIGHT,
        COMBAT,
        LAND_AFTER_DRAGON,
        COLLECT_DROPS,
        AERIAL_TEAR_SEARCH,
        STORE_DROPS,
        PICKUP_SHULKER,
        RETURNING_TO_END,
        SHIP_SEARCH,
        SHIP_APPROACH,
        SHIP_MINE_HEAD
    }

    private enum TakeoffPhase {
        DOUBLE_JUMP,
        GLIDE,
        BOOST
    }

    private enum ShipObjective {
        SEARCH("搜索"),
        SHIP_BODY("船体"),
        DRAGON_HEAD("龙头"),
        ELYTRA_FRAME("鞘翅");

        private final String label;

        ShipObjective(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private record ShipTarget(BlockPos pos, ShipObjective objective) {
    }
}


