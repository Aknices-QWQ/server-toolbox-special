package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AutoNetherWartController {
    private static final String COMMAND = "autowart";
    private static final String CHAT_MODULE = "自动下界疣";
    private static final int DEFAULT_ACTIONS_PER_TICK = 3;
    private static final int DEFAULT_SCAN_RADIUS = 6;
    private static final int MAX_SCAN_RADIUS = 6;
    private static final int MAX_ACTIONS_PER_TICK = 5;
    private static final int PROGRESS_MESSAGE_TICKS = 20 * 8;
    private static final int REFILL_SCREEN_DELAY_TICKS = 6;
    private static final int REFILL_TIMEOUT_TICKS = 20 * 8;
    private static final int STORAGE_REFILL_STACKS_PER_RUN = 6;
    private static final int DANGER_COMMAND_COOLDOWN_TICKS = 20 * 15;
    private static final int START_TELEPORT_COOLDOWN_TICKS = 20 * 10;
    private static final int RESPAWN_RETURN_DELAY_TICKS = 20;
    private static final int MINECART_ACTION_COOLDOWN_TICKS = 10;
    private static final int DANGER_RETURN_DELAY_TICKS = 20 * 3;
    private static final double MINECART_SCAN_RANGE = 3.0D;
    private static final double MINECART_INTERACT_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final double REACH_DISTANCE_SQUARED = 6.0D * 6.0D;
    private static final double MINECART_DIRECTION_SPEED_SQUARED = 0.0004D;
    private static final float MINECART_VIEW_PITCH = 18.0F;

    private static boolean enabled;
    private static RefillState refillState = RefillState.NONE;
    private static int scanRadius = DEFAULT_SCAN_RADIUS;
    private static int actionsPerTick = DEFAULT_ACTIONS_PER_TICK;
    private static boolean autoWalk = false;
    private static boolean minecartForwardAssist = true;
    private static boolean autoRideMinecart = true;
    private static boolean autoPlaceMinecart = true;
    private static boolean autoClearNonPlayerMinecart = true;
    private static boolean autoRespawnReturn = true;
    private static boolean spectatorDangerDetection = false;
    private static int respawnReturnDelayTicksConfig = AutoNetherWartConfig.Config.defaults().respawnReturnDelayTicks();
    private static int dangerReturnDelayTicksConfig = AutoNetherWartConfig.Config.defaults().dangerReturnDelayTicks();
    private static int dangerCommandCooldownTicksConfig = AutoNetherWartConfig.Config.defaults().dangerCommandCooldownTicks();
    private static int startTeleportCooldownTicksConfig = AutoNetherWartConfig.Config.defaults().startTeleportCooldownTicks();
    private static int minecartActionCooldownTicksConfig = AutoNetherWartConfig.Config.defaults().minecartActionCooldownTicks();
    private static int refillScreenDelayTicksConfig = AutoNetherWartConfig.Config.defaults().refillScreenDelayTicks();
    private static int refillTimeoutTicksConfig = AutoNetherWartConfig.Config.defaults().refillTimeoutTicks();
    private static int storageRefillStacksPerRunConfig = AutoNetherWartConfig.Config.defaults().storageRefillStacksPerRun();
    private static String startTerritory = AutoNetherWartConfig.Config.defaults().startTerritory();
    private static String safeTerritory = AutoNetherWartConfig.Config.defaults().safeTerritory();
    private static String dangerPlayerName = AutoNetherWartConfig.Config.defaults().dangerPlayerName();
    private static String cropStorageCommand = AutoNetherWartConfig.Config.defaults().cropStorageCommand();
    private static int progressMessageCooldownTicks;
    private static int refillDelayTicks;
    private static int refillTimeoutTicks;
    private static int refillWartCountBefore;
    private static int refillMovedStackClicks;
    private static long harvestedCount;
    private static long plantedCount;
    private static long skippedPlantNoSeedCount;
    private static long storageRefillCount;
    private static int preferredSlot = -1;
    private static int dangerCommandCooldownTicks;
    private static int startTeleportCooldownTicks;
    private static int respawnReturnDelayTicks = -1;
    private static int minecartActionCooldownTicks;
    private static boolean deathHandled;
    private static boolean waitingForRespawnReturn;
    private static boolean dangerEscapeActive;
    private static int dangerReturnDelayTicks = -1;

    private AutoNetherWartController() {
    }

    static void initialize(Minecraft client) {
        applyConfig(client, AutoNetherWartConfig.get(client));
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
                            stop(command.getSource().getClient(), "自动下界疣已停止。");
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("width")
                        .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                                .executes(command -> {
                                    scanRadius = IntegerArgumentType.getInteger(command, "blocks");
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "自动下界疣可见扫描半径已设为 " + scanRadius + " 格。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("radius")
                        .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                                .executes(command -> {
                                    scanRadius = IntegerArgumentType.getInteger(command, "blocks");
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "自动下界疣可见扫描半径已设为 " + scanRadius + " 格。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("speed")
                        .then(ClientCommands.argument("actionsPerTick", IntegerArgumentType.integer(1, MAX_ACTIONS_PER_TICK))
                                .executes(command -> {
                                    actionsPerTick = IntegerArgumentType.getInteger(command, "actionsPerTick");
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "自动下界疣每 tick 操作数已设为 " + actionsPerTick + "。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("walk")
                        .then(ClientCommands.literal("on")
                                .executes(command -> {
                                    autoWalk = true;
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "自动下界疣已开启自动前进。");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("off")
                                .executes(command -> {
                                    autoWalk = false;
                                    saveConfig(command.getSource().getClient());
                                    releaseAutoWalk(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "自动下界疣已关闭自动前进。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("minecart")
                        .then(ClientCommands.literal("on")
                                .executes(command -> {
                                    minecartForwardAssist = true;
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "矿车防停已开启：乘坐矿车时会自动按住前进。");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("off")
                                .executes(command -> {
                                    minecartForwardAssist = false;
                                    saveConfig(command.getSource().getClient());
                                    releaseAutoWalk(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "矿车防停已关闭。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("spectator")
                        .then(ClientCommands.literal("on")
                                .executes(command -> {
                                    spectatorDangerDetection = true;
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "旁观玩家检测已开启。");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("off")
                                .executes(command -> {
                                    spectatorDangerDetection = false;
                                    saveConfig(command.getSource().getClient());
                                    sendMessage(command.getSource().getClient(), "旁观玩家检测已关闭。");
                                    return 1;
                                }))));
    }

    static void tick(Minecraft client) {
        if (!enabled) {
            return;
        }

        if (!ScreenProbeGlobalConfig.get(client).enableAutoWart()) {
            stop(client, "自动下界疣已被全局设置屏蔽。");
            return;
        }

        tickCooldowns();
        if (progressMessageCooldownTicks > 0) {
            progressMessageCooldownTicks--;
        }

        if (!isClientReady(client)) {
            releaseAutoWalk(client);
            return;
        }

        if (handleDeathAndRespawn(client)) {
            return;
        }

        if (detectDangerPlayersAndTeleport(client)) {
            return;
        }

        if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
            releaseAutoWalk(client);
            return;
        }

        if (refillState != RefillState.NONE) {
            tickRefill(client);
            return;
        }

        if (client.screen != null) {
            return;
        }

        handleMinecartAutomation(client);

        if (shouldHoldForward(client)) {
            alignViewToMinecartDirection(client);
            applyAutoWalk(client);
        } else {
            releaseAutoWalk(client);
        }

        List<Target> targets = findTargets(client);
        if (targets.isEmpty()) {
            sendThrottledProgress(client, "范围内没有可处理的成熟下界疣或空灵魂沙。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        int actions = 0;
        for (Target target : targets) {
            if (actions >= actionsPerTick) {
                break;
            }
            if (target.action() == Action.HARVEST) {
                harvest(client, target.pos());
                actions++;
                continue;
            }

            if (!ensureNetherWartReady(client)) {
                return;
            }
            plant(client, target.pos());
            actions++;
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

    static boolean isMinecartForwardAssistEnabled() {
        return minecartForwardAssist;
    }

    static boolean isSpectatorDangerDetectionEnabled() {
        return spectatorDangerDetection;
    }

    static int getScanRadius() {
        return scanRadius;
    }

    static int getActionsPerTick() {
        return actionsPerTick;
    }

    static String getRefillStateName() {
        return refillState.name();
    }

    static void setEnabled(Minecraft client, boolean value) {
        if (enabled == value) {
            return;
        }

        if (value) {
            start(client);
        } else {
            stop(client, "自动下界疣已停止。");
        }
    }

    static void setMinecartForwardAssist(Minecraft client, boolean value) {
        minecartForwardAssist = value;
        saveConfig(client);
        if (!value) {
            releaseAutoWalk(client);
        }
    }

    static void setSpectatorDangerDetection(boolean value) {
        spectatorDangerDetection = value;
        saveConfig(Minecraft.getInstance());
    }

    static void adjustScanRadius(int delta) {
        scanRadius = Math.max(1, Math.min(MAX_SCAN_RADIUS, scanRadius + delta));
        saveConfig(Minecraft.getInstance());
    }

    static void adjustActionsPerTick(int delta) {
        actionsPerTick = Math.max(1, Math.min(MAX_ACTIONS_PER_TICK, actionsPerTick + delta));
        saveConfig(Minecraft.getInstance());
    }

    static void applyConfig(Minecraft client, AutoNetherWartConfig.Config config) {
        AutoNetherWartConfig.Config clamped = config.clamped();
        enabled = clamped.enabled();
        scanRadius = clamped.scanRadius();
        actionsPerTick = clamped.actionsPerTick();
        autoWalk = clamped.autoWalk();
        minecartForwardAssist = clamped.minecartForwardAssist();
        autoRideMinecart = clamped.autoRideMinecart();
        autoPlaceMinecart = clamped.autoPlaceMinecart();
        autoClearNonPlayerMinecart = clamped.autoClearNonPlayerMinecart();
        autoRespawnReturn = clamped.autoRespawnReturn();
        spectatorDangerDetection = clamped.spectatorDangerDetection();
        respawnReturnDelayTicksConfig = clamped.respawnReturnDelayTicks();
        dangerReturnDelayTicksConfig = clamped.dangerReturnDelayTicks();
        dangerCommandCooldownTicksConfig = clamped.dangerCommandCooldownTicks();
        startTeleportCooldownTicksConfig = clamped.startTeleportCooldownTicks();
        minecartActionCooldownTicksConfig = clamped.minecartActionCooldownTicks();
        refillScreenDelayTicksConfig = clamped.refillScreenDelayTicks();
        refillTimeoutTicksConfig = clamped.refillTimeoutTicks();
        storageRefillStacksPerRunConfig = clamped.storageRefillStacksPerRun();
        startTerritory = clamped.startTerritory();
        safeTerritory = clamped.safeTerritory();
        dangerPlayerName = clamped.dangerPlayerName();
        cropStorageCommand = clamped.cropStorageCommand();
        if (!enabled) {
            releaseAutoWalk(client);
        }
    }

    static AutoNetherWartConfig.Config exportConfig() {
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
                respawnReturnDelayTicksConfig,
                dangerReturnDelayTicksConfig,
                dangerCommandCooldownTicksConfig,
                startTeleportCooldownTicksConfig,
                minecartActionCooldownTicksConfig,
                refillScreenDelayTicksConfig,
                refillTimeoutTicksConfig,
                storageRefillStacksPerRunConfig,
                startTerritory,
                safeTerritory,
                dangerPlayerName,
                cropStorageCommand
        );
    }

    private static void saveConfig(Minecraft client) {
        AutoNetherWartConfig.setAndSave(client, exportConfig());
    }

    private static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, "自动下界疣已停止。");
        } else {
            start(client);
        }
    }

    private static void start(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableAutoWart()) {
            sendMessage(client, "自动下界疣已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动自动下界疣。");
            return;
        }

        enabled = true;
        progressMessageCooldownTicks = 0;
        harvestedCount = 0L;
        plantedCount = 0L;
        skippedPlantNoSeedCount = 0L;
        storageRefillCount = 0L;
        refillState = RefillState.NONE;
        refillDelayTicks = 0;
        refillTimeoutTicks = 0;
        preferredSlot = client.player.getInventory().getSelectedSlot();
        dangerCommandCooldownTicks = 0;
        startTeleportCooldownTicks = startTeleportCooldownTicksConfig;
        respawnReturnDelayTicks = -1;
        minecartActionCooldownTicks = 0;
        deathHandled = false;
        waitingForRespawnReturn = false;
        dangerEscapeActive = false;
        dangerReturnDelayTicks = -1;
        releaseAutoWalk(client);
        saveConfig(client);
        sendCommand(client, "res tp " + startTerritory);
        sendMessage(client, "自动下界疣可见范围模式启动：周围半径 " + scanRadius
                + " 格，每 tick 最多 " + actionsPerTick + " 次操作。矿车防停 "
                + (minecartForwardAssist ? "开启" : "关闭") + "。已发送 /res tp " + startTerritory + "。");
    }

    private static void stop(Minecraft client, String message) {
        enabled = false;
        refillState = RefillState.NONE;
        progressMessageCooldownTicks = 0;
        refillDelayTicks = 0;
        refillTimeoutTicks = 0;
        dangerCommandCooldownTicks = 0;
        startTeleportCooldownTicks = 0;
        respawnReturnDelayTicks = -1;
        minecartActionCooldownTicks = 0;
        deathHandled = false;
        waitingForRespawnReturn = false;
        dangerEscapeActive = false;
        dangerReturnDelayTicks = -1;
        releaseAutoWalk(client);
        restorePreferredSlot(client);
        closeContainer(client);
        saveConfig(client);
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static void sendStatus(Minecraft client) {
        Component message = Component.empty()
                .append(Component.literal("启用 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(enabled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 半径 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(scanRadius)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" | 操作/tick ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Integer.toString(actionsPerTick)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 自动前进 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(autoWalk)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 矿车防停 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(minecartForwardAssist)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 旁观检测 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Boolean.toString(spectatorDangerDetection)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" | 收割 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(harvestedCount)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 补种 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(plantedCount)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | 缺种 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(skippedPlantNoSeedCount)).withStyle(ChatFormatting.RED))
                .append(Component.literal(" | 仓库补货 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(Long.toString(storageRefillCount)).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" | 补货状态 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(refillState.name()).withStyle(ChatFormatting.YELLOW));
        sendMessage(client, message);
    }

    private static void tickCooldowns() {
        if (dangerCommandCooldownTicks > 0) {
            dangerCommandCooldownTicks--;
        }
        if (startTeleportCooldownTicks > 0) {
            startTeleportCooldownTicks--;
        }
        if (minecartActionCooldownTicks > 0) {
            minecartActionCooldownTicks--;
        }
    }

    private static boolean handleDeathAndRespawn(Minecraft client) {
        if (client.player.isDeadOrDying() || client.screen instanceof DeathScreen) {
            releaseAutoWalk(client);
            if (!autoRespawnReturn) {
                return false;
            }
            if (!deathHandled) {
                deathHandled = true;
                waitingForRespawnReturn = true;
                respawnReturnDelayTicks = -1;
            }
            client.player.respawn();
            if (client.screen instanceof DeathScreen) {
                client.setScreen(null);
            }
            return true;
        }

        if (waitingForRespawnReturn) {
            if (respawnReturnDelayTicks < 0) {
                respawnReturnDelayTicks = respawnReturnDelayTicksConfig;
                return true;
            }

            if (respawnReturnDelayTicks-- > 0) {
                return true;
            }

            sendCommand(client, "res tp " + startTerritory);
            waitingForRespawnReturn = false;
            deathHandled = false;
            startTeleportCooldownTicks = startTeleportCooldownTicksConfig;
            sendThrottledProgress(client, "已自动重生，并发送 /res tp " + startTerritory + "。", PROGRESS_MESSAGE_TICKS);
            return true;
        }

        deathHandled = false;
        return false;
    }

    private static boolean detectDangerPlayersAndTeleport(Minecraft client) {
        String reason = dangerReason(client);
        if (reason != null) {
            dangerReturnDelayTicks = -1;
            if (dangerEscapeActive || dangerCommandCooldownTicks > 0 || startTeleportCooldownTicks > 0) {
                return dangerEscapeActive;
            }

            dangerEscapeActive = true;
            releaseAutoWalk(client);
            closeContainer(client);
            sendCommand(client, "res tp " + safeTerritory);
            dangerCommandCooldownTicks = dangerCommandCooldownTicksConfig;
            sendThrottledProgress(client, "检测到" + reason + "，已发送 /res tp " + safeTerritory + "。", PROGRESS_MESSAGE_TICKS);
            return true;
        }

        if (!dangerEscapeActive) {
            return false;
        }

        if (dangerReturnDelayTicks < 0) {
            dangerReturnDelayTicks = dangerReturnDelayTicksConfig;
            return true;
        }

        if (dangerReturnDelayTicks-- > 0) {
            return true;
        }

        dangerEscapeActive = false;
        dangerReturnDelayTicks = -1;
        sendCommand(client, "res tp " + startTerritory);
        startTeleportCooldownTicks = startTeleportCooldownTicksConfig;
        sendThrottledProgress(client, "危险玩家已离开，已自动返回 /res tp " + startTerritory + "。", PROGRESS_MESSAGE_TICKS);
        return true;
    }

    private static String dangerReason(Minecraft client) {
        if (client.getConnection() == null) {
            return null;
        }

        String selfName = client.player.getGameProfile().name();
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            if (info == null || info.getProfile() == null) {
                continue;
            }

            String name = info.getProfile().name();
            if (name == null || name.equalsIgnoreCase(selfName)) {
                continue;
            }

            if (dangerPlayerName.equalsIgnoreCase(name)) {
                return "玩家 " + dangerPlayerName;
            }

            if (spectatorDangerDetection && info.getGameMode() == GameType.SPECTATOR) {
                return "旁观者玩家 " + name;
            }
        }
        return null;
    }

    private static List<Target> findTargets(Minecraft client) {
        List<Target> targets = new ArrayList<>();
        LocalPlayer player = client.player;
        BlockPos playerPos = player.blockPosition();

        for (int yOffset = -2; yOffset <= 2; yOffset++) {
            for (int xOffset = -scanRadius; xOffset <= scanRadius; xOffset++) {
                for (int zOffset = -scanRadius; zOffset <= scanRadius; zOffset++) {
                    if (xOffset * xOffset + zOffset * zOffset > scanRadius * scanRadius) {
                        continue;
                    }

                    BlockPos columnPos = playerPos.offset(xOffset, yOffset, zOffset);
                    addTargetIfUsable(client, targets, columnPos);
                }
            }
        }

        targets.sort(Comparator
                .comparingInt((Target target) -> target.action() == Action.HARVEST ? 0 : 1)
                .thenComparingDouble(target -> target.hitPoint().distanceToSqr(player.getEyePosition())));
        return targets;
    }

    private static void addTargetIfUsable(Minecraft client, List<Target> targets, BlockPos columnPos) {
        for (int yOffset = 1; yOffset >= -2; yOffset--) {
            BlockPos cropPos = columnPos.offset(0, yOffset, 0);
            BlockState cropState = client.level.getBlockState(cropPos);
            Vec3 cropHit = cropPos.getCenter();
            if (isMatureNetherWart(cropState) && isReachableAndVisible(client, cropPos, cropHit)) {
                targets.add(new Target(cropPos.immutable(), Action.HARVEST, cropHit));
                return;
            }

            BlockPos soulSandPos = cropPos.below();
            BlockState soulSandState = client.level.getBlockState(soulSandPos);
            Vec3 plantHit = soulSandPos.getCenter().add(0.0D, 0.5D, 0.0D);
            if (soulSandState.is(Blocks.SOUL_SAND)
                    && cropState.isAir()
                    && client.level.getFluidState(cropPos).isEmpty()
                    && isReachableAndVisible(client, soulSandPos, plantHit)) {
                targets.add(new Target(soulSandPos.immutable(), Action.PLANT, plantHit));
                return;
            }
        }
    }

    private static boolean isMatureNetherWart(BlockState state) {
        return state.is(Blocks.NETHER_WART)
                && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
    }

    private static boolean isReachableAndVisible(Minecraft client, BlockPos pos, Vec3 hitPoint) {
        if (client.player.getEyePosition().distanceToSqr(hitPoint) > REACH_DISTANCE_SQUARED) {
            return false;
        }

        BlockHitResult hitResult = client.level.clip(new ClipContext(
                client.player.getEyePosition(),
                hitPoint,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hitResult.getType() == HitResult.Type.MISS || hitResult.getBlockPos().equals(pos);
    }

    private static void harvest(Minecraft client, BlockPos cropPos) {
        selectFortuneTool(client);
        Direction side = Direction.getApproximateNearest(
                client.player.getX() - (cropPos.getX() + 0.5D),
                client.player.getEyeY() - (cropPos.getY() + 0.5D),
                client.player.getZ() - (cropPos.getZ() + 0.5D)
        );
        client.gameMode.startDestroyBlock(cropPos, side);
        client.gameMode.continueDestroyBlock(cropPos, side);
        client.player.swing(InteractionHand.MAIN_HAND);
        harvestedCount++;
        restorePreferredSlot(client);
    }

    private static void plant(Minecraft client, BlockPos soulSandPos) {
        Vec3 hit = soulSandPos.getCenter().add(0.0D, 0.5D, 0.0D);
        BlockHitResult hitResult = new BlockHitResult(hit, Direction.UP, soulSandPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
        plantedCount++;
        restorePreferredSlot(client);
    }

    private static boolean ensureNetherWartReady(Minecraft client) {
        if (selectNetherWart(client)) {
            return true;
        }

        if (moveNetherWartFromInventoryToHotbar(client)) {
            return selectNetherWart(client);
        }

        beginStorageRefill(client);
        return false;
    }

    private static boolean moveNetherWartFromInventoryToHotbar(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int sourceSlot = -1;
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isNetherWartItem(stack)) {
                sourceSlot = slot;
                break;
            }
        }

        if (sourceSlot < 0) {
            return false;
        }

        int hotbarSlot = findHotbarTargetSlot(inventory);
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                sourceSlot,
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

        return preferredSlot >= 0 && preferredSlot < Inventory.getSelectionSize()
                ? preferredSlot
                : inventory.getSelectedSlot();
    }

    private static void beginStorageRefill(Minecraft client) {
        if (refillState != RefillState.NONE) {
            return;
        }

        int currentWarts = countInventoryNetherWart(client);
        if (currentWarts > 0) {
            sendThrottledProgress(client, "背包已有 " + currentWarts + " 个下界疣，不从作物仓库取出。", PROGRESS_MESSAGE_TICKS);
            return;
        }

        skippedPlantNoSeedCount++;
        releaseAutoWalk(client);
        restorePreferredSlot(client);
        closeContainer(client);
        refillState = RefillState.WAITING_FOR_CD_MENU;
        refillDelayTicks = refillScreenDelayTicksConfig;
        refillTimeoutTicks = refillTimeoutTicksConfig;
        client.getConnection().sendCommand(cropStorageCommand);
        sendThrottledProgress(client, "背包里没有下界疣，正在打开 /" + cropStorageCommand + " 作物仓库补货。", PROGRESS_MESSAGE_TICKS);
    }

    private static void tickRefill(Minecraft client) {
        if (refillDelayTicks > 0) {
            refillDelayTicks--;
            return;
        }

        if (refillTimeoutTicks-- <= 0) {
            abortRefill(client, "补货超时，没能从 /" + cropStorageCommand + " 作物仓库拿到下界疣。");
            return;
        }

        switch (refillState) {
            case WAITING_FOR_CD_MENU -> {
                if (client.screen instanceof AbstractContainerScreen<?> screen) {
                    handleCdMenu(client, screen);
                }
            }
            case WAITING_FOR_CROP_STORAGE -> {
                if (client.screen instanceof AbstractContainerScreen<?> screen) {
                    handleCropStorage(client, screen);
                }
            }
            case WAITING_FOR_NETHER_WART_STORAGE -> {
                if (client.screen instanceof AbstractContainerScreen<?> screen) {
                    handleNetherWartStorage(client, screen);
                }
            }
            case WAITING_FOR_REFILL_VERIFY -> handleRefillVerify(client);
            case NONE -> {
            }
        }
    }

    private static void handleCdMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        int cropSlot = findSlotByKeywords(screen, false, "作物", "农作物", "庄稼", "crop", "crops");
        if (cropSlot < 0) {
            cropSlot = findSlotByKeywords(screen, false, "仓库", "storage");
        }

        if (cropSlot < 0) {
            return;
        }

        clickSlot(client, screen, cropSlot, 0, ContainerInput.PICKUP);
        refillState = RefillState.WAITING_FOR_CROP_STORAGE;
        refillDelayTicks = refillScreenDelayTicksConfig;
        refillTimeoutTicks = refillTimeoutTicksConfig;
    }

    private static void handleCropStorage(Minecraft client, AbstractContainerScreen<?> screen) {
        int wartSlot = findNetherWartSlot(screen, false);
        if (wartSlot < 0) {
            return;
        }

        clickSlot(client, screen, wartSlot, 0, ContainerInput.PICKUP);
        refillState = RefillState.WAITING_FOR_NETHER_WART_STORAGE;
        refillDelayTicks = refillScreenDelayTicksConfig;
        refillTimeoutTicks = refillTimeoutTicksConfig;
    }

    private static void handleNetherWartStorage(Minecraft client, AbstractContainerScreen<?> screen) {
        List<Integer> wartSlots = findNetherWartSlots(screen, false);
        if (wartSlots.isEmpty()) {
            return;
        }

        refillWartCountBefore = countInventoryNetherWart(client);
        refillMovedStackClicks = quickMoveSeveralStacks(client, screen, wartSlots);
        closeContainer(client);
        refillState = RefillState.WAITING_FOR_REFILL_VERIFY;
        refillDelayTicks = refillScreenDelayTicksConfig;
        refillTimeoutTicks = refillTimeoutTicksConfig;
    }

    private static void handleRefillVerify(Minecraft client) {
        int currentCount = countInventoryNetherWart(client);
        int movedItems = Math.max(0, currentCount - refillWartCountBefore);
        if (movedItems <= 0) {
            abortRefill(client, "作物仓库没有实际取出下界疣。点击槽位 "
                    + refillMovedStackClicks + " 次，背包数量未增加。");
            return;
        }

        storageRefillCount += movedItems;
        refillState = RefillState.NONE;
        refillDelayTicks = 0;
        refillTimeoutTicks = 0;
        sendThrottledProgress(client, "已从作物仓库实际取出 " + movedItems + " 个下界疣。", PROGRESS_MESSAGE_TICKS);
    }

    private static int quickMoveSeveralStacks(Minecraft client, AbstractContainerScreen<?> screen, List<Integer> wartSlots) {
        int remainingCapacity = countInventoryCapacityForNetherWart(client);
        if (remainingCapacity <= 0) {
            return 0;
        }

        int moved = 0;
        for (int slotIndex : wartSlots) {
            if (remainingCapacity <= 0) {
                break;
            }
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }
            int stackCount = slot.getItem().getCount();
            clickSlot(client, screen, slotIndex, 0, ContainerInput.QUICK_MOVE);
            moved++;
            remainingCapacity -= stackCount;
        }
        return moved;
    }

    private static int countInventoryCapacityForNetherWart(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int capacity = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                capacity += 64;
            } else if (isNetherWartItem(stack) && stack.getCount() < stack.getMaxStackSize()) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    private static int countInventoryNetherWart(Minecraft client) {
        if (client == null || client.player == null) {
            return 0;
        }
        Inventory inventory = client.player.getInventory();
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isNetherWartItem(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void abortRefill(Minecraft client, String message) {
        refillState = RefillState.NONE;
        refillDelayTicks = 0;
        refillTimeoutTicks = 0;
        refillWartCountBefore = 0;
        refillMovedStackClicks = 0;
        closeContainer(client);
        sendMessage(client, message);
    }

    private static int findNetherWartSlot(AbstractContainerScreen<?> screen, boolean includePlayerInventory) {
        int slot = findSlotByItemId(screen, includePlayerInventory, "minecraft:nether_wart");
        if (slot >= 0) {
            return slot;
        }

        return findSlotByKeywords(screen, includePlayerInventory, "下界疣", "地狱疣", "nether wart", "nether_wart");
    }

    private static List<Integer> findNetherWartSlots(AbstractContainerScreen<?> screen, boolean includePlayerInventory) {
        List<Integer> slots = findSlotsByItemId(screen, includePlayerInventory, "minecraft:nether_wart");
        if (!slots.isEmpty()) {
            return slots;
        }

        return findSlotsByKeywords(screen, includePlayerInventory, "下界疣", "地狱疣", "nether wart", "nether_wart");
    }

    private static int findSlotByItemId(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String itemId) {
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            if (BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString().equals(itemId)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static List<Integer> findSlotsByItemId(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String itemId) {
        List<Integer> slots = new ArrayList<>();
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            if (BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString().equals(itemId)) {
                slots.add(slotIndex);
            }
        }
        return slots;
    }

    private static int findSlotByKeywords(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String... keywords) {
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            String label = normalizeText(slot.getItem().getHoverName().getString());
            for (String keyword : keywords) {
                if (label.contains(normalizeText(keyword))) {
                    return slotIndex;
                }
            }
        }
        return -1;
    }

    private static List<Integer> findSlotsByKeywords(AbstractContainerScreen<?> screen, boolean includePlayerInventory, String... keywords) {
        List<Integer> slots = new ArrayList<>();
        int playerInventoryStart = Math.max(0, screen.getMenu().slots.size() - 36);
        int end = includePlayerInventory ? screen.getMenu().slots.size() : playerInventoryStart;
        for (int slotIndex = 0; slotIndex < end; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            String label = normalizeText(slot.getItem().getHoverName().getString());
            for (String keyword : keywords) {
                if (label.contains(normalizeText(keyword))) {
                    slots.add(slotIndex);
                    break;
                }
            }
        }
        return slots;
    }

    private static void clickSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex, int button, ContainerInput input) {
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotIndex,
                button,
                input,
                client.player
        );
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

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase().replace(" ", "").replace("_", "");
    }

    private static boolean selectNetherWart(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        if (isNetherWartItem(inventory.getSelectedItem())) {
            return true;
        }

        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isNetherWartItem(stack)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private static boolean isNetherWartItem(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("minecraft:nether_wart");
    }

    private static void restorePreferredSlot(Minecraft client) {
        if (client == null || client.player == null || preferredSlot < 0) {
            return;
        }

        Inventory inventory = client.player.getInventory();
        if (preferredSlot < Inventory.getSelectionSize() && inventory.getSelectedSlot() != preferredSlot) {
            inventory.setSelectedSlot(preferredSlot);
        }
    }

    private static void selectFortuneTool(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int bestSlot = -1;
        int bestLevel = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            int level = getFortuneLevel(inventory.getItem(slot));
            if (level > bestLevel) {
                bestLevel = level;
                bestSlot = slot;
            }
        }

        if (bestSlot >= 0) {
            inventory.setSelectedSlot(bestSlot);
        }
    }

    private static int getFortuneLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(Enchantments.FORTUNE)) {
                return stack.getEnchantments().getLevel(enchantment);
            }
        }
        return 0;
    }

    private static void handleMinecartAutomation(Minecraft client) {
        if (minecartActionCooldownTicks > 0 || client.player.getVehicle() instanceof AbstractMinecart) {
            return;
        }

        if (autoClearNonPlayerMinecart && tryClearNonPlayerMinecart(client)) {
            minecartActionCooldownTicks = AutoNetherWartConfig.get(client).minecartActionCooldownTicks();
            return;
        }

        if (autoRideMinecart && tryRideNearestEmptyMinecart(client)) {
            minecartActionCooldownTicks = AutoNetherWartConfig.get(client).minecartActionCooldownTicks();
            return;
        }

        if (autoPlaceMinecart && tryPlaceMinecartOnRail(client)) {
            minecartActionCooldownTicks = AutoNetherWartConfig.get(client).minecartActionCooldownTicks();
        }
    }

    private static boolean tryClearNonPlayerMinecart(Minecraft client) {
        LocalPlayer player = client.player;
        AABB box = player.getBoundingBox().inflate(MINECART_SCAN_RANGE, 2.0D, MINECART_SCAN_RANGE);
        List<Entity> entities = client.level.getEntities(player, box, entity ->
                EntitySelector.ENTITY_STILL_ALIVE.test(entity)
                        && entity instanceof AbstractMinecart minecart
                        && player.distanceToSqr(entity) <= MINECART_INTERACT_DISTANCE_SQUARED
                        && isOnRail(client, minecart)
                        && !minecart.hasPassenger(passenger -> passenger instanceof Player));
        if (entities.isEmpty()) {
            return false;
        }

        Entity target = entities.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (target == null) {
            return false;
        }

        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean tryRideNearestEmptyMinecart(Minecraft client) {
        LocalPlayer player = client.player;
        AABB box = player.getBoundingBox().inflate(MINECART_SCAN_RANGE, 2.0D, MINECART_SCAN_RANGE);
        List<Entity> entities = client.level.getEntities(player, box, entity ->
                EntitySelector.ENTITY_STILL_ALIVE.test(entity)
                        && entity instanceof AbstractMinecart
                        && !entity.hasPassenger(passenger -> passenger instanceof Player)
                        && player.distanceToSqr(entity) <= MINECART_INTERACT_DISTANCE_SQUARED);
        if (entities.isEmpty()) {
            return false;
        }

        Entity target = entities.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (target == null) {
            return false;
        }

        client.gameMode.interact(player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean tryPlaceMinecartOnRail(Minecraft client) {
        BlockPos railPos = findNearestRail(client);
        if (railPos == null || hasMinecartNear(client, railPos)) {
            return false;
        }

        if (!selectMinecart(client)) {
            return false;
        }

        Vec3 hit = railPos.getCenter().add(0.0D, 0.08D, 0.0D);
        BlockHitResult hitResult = new BlockHitResult(hit, Direction.UP, railPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
        restorePreferredSlot(client);
        return true;
    }

    private static BlockPos findNearestRail(Minecraft client) {
        LocalPlayer player = client.player;
        BlockPos playerPos = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 3;
        for (int yOffset = -2; yOffset <= 1; yOffset++) {
            for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                    BlockPos pos = playerPos.offset(xOffset, yOffset, zOffset);
                    if (!BaseRailBlock.isRail(client.level.getBlockState(pos))) {
                        continue;
                    }

                    double distance = player.getEyePosition().distanceToSqr(pos.getCenter());
                    if (distance > REACH_DISTANCE_SQUARED || distance >= bestDistance) {
                        continue;
                    }

                    best = pos.immutable();
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean hasMinecartNear(Minecraft client, BlockPos railPos) {
        AABB box = new AABB(railPos).inflate(0.75D, 0.75D, 0.75D);
        return !client.level.getEntities(client.player, box, entity ->
                EntitySelector.ENTITY_STILL_ALIVE.test(entity) && entity instanceof AbstractMinecart).isEmpty();
    }

    private static boolean isOnRail(Minecraft client, AbstractMinecart minecart) {
        BlockPos blockPos = minecart.blockPosition();
        return BaseRailBlock.isRail(client.level.getBlockState(blockPos))
                || BaseRailBlock.isRail(client.level.getBlockState(blockPos.below()));
    }

    private static boolean selectMinecart(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        if (isMinecartItem(inventory.getSelectedItem())) {
            return true;
        }

        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (isMinecartItem(inventory.getItem(slot))) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }

        int sourceSlot = -1;
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isMinecartItem(inventory.getItem(slot))) {
                sourceSlot = slot;
                break;
            }
        }

        if (sourceSlot < 0) {
            return false;
        }

        int hotbarSlot = findHotbarTargetSlot(inventory);
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                sourceSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        inventory.setSelectedSlot(hotbarSlot);
        return true;
    }

    private static boolean isMinecartItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.MINECART;
    }

    private static void applyAutoWalk(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static boolean shouldHoldForward(Minecraft client) {
        return autoWalk || minecartForwardAssist && client != null && client.player != null
                && client.player.getVehicle() instanceof AbstractMinecart;
    }

    private static void alignViewToMinecartDirection(Minecraft client) {
        if (!minecartForwardAssist || client == null || client.player == null
                || !(client.player.getVehicle() instanceof AbstractMinecart minecart)) {
            return;
        }

        Vec3 movement = minecart.getDeltaMovement();
        float yaw;
        if (movement.horizontalDistanceSqr() >= MINECART_DIRECTION_SPEED_SQUARED) {
            yaw = (float) (Math.toDegrees(Math.atan2(movement.z, movement.x)) - 90.0D);
        } else {
            yaw = minecart.getYRot();
        }

        client.player.setYRot(yaw);
        client.player.setYHeadRot(yaw);
        client.player.setYBodyRot(yaw);
        client.player.setXRot(MINECART_VIEW_PITCH);
    }

    private static void releaseAutoWalk(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.gameMode != null && client.getConnection() != null;
    }

    private static void sendThrottledProgress(Minecraft client, String message, int cooldown) {
        if (progressMessageCooldownTicks > 0) {
            return;
        }

        progressMessageCooldownTicks = cooldown;
        sendMessage(client, message);
    }

    private static void sendCommand(Minecraft client, String command) {
        if (client != null && client.getConnection() != null) {
            client.getConnection().sendCommand(command);
        }
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

    private enum Action {
        HARVEST,
        PLANT
    }

    private enum RefillState {
        NONE,
        WAITING_FOR_CD_MENU,
        WAITING_FOR_CROP_STORAGE,
        WAITING_FOR_NETHER_WART_STORAGE,
        WAITING_FOR_REFILL_VERIFY
    }

    private record Target(BlockPos pos, Action action, Vec3 hitPoint) {
    }
}
