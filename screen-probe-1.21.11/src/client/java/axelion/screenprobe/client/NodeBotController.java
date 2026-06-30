package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

final class NodeBotController {
    private static final String COMMAND = "nodebot";
    private static final String CHAT_MODULE = "Node机器人";
    private static final String BOT_DIR_NAME = "screenprobe-nodebot";
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Duration STOP_TIMEOUT = Duration.ofMillis(1500L);

    private static Process process;
    private static Path activeWorkDir;
    private static Path activeLogFile;
    private static LocalDateTime startedAt;
    private static boolean shutdownHookRegistered;

    private NodeBotController() {
    }

    static void initialize() {
        if (shutdownHookRegistered) {
            return;
        }

        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(NodeBotController::stopQuietly, "screenprobe-nodebot-shutdown"));
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
                            stop(command.getSource().getClient(), "Node 机器人已停止。");
                            return 1;
                        }))
                .then(ClientCommandManager.literal("restart")
                        .executes(command -> {
                            restart(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("status")
                        .executes(command -> {
                            sendStatus(command.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("send")
                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(command -> {
                                    sendToBot(command.getSource().getClient(), StringArgumentType.getString(command, "message"));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("say")
                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(command -> {
                                    sendToBot(command.getSource().getClient(), StringArgumentType.getString(command, "message"));
                                    return 1;
                                }))));
    }

    static synchronized void sendToBot(Minecraft client, String message) {
        if (!ScreenProbeGlobalConfig.get(client).enableNodeBot()) {
            sendMessage(client, "Node 机器人已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        pruneExitedProcess();
        if (process == null || !process.isAlive()) {
            sendMessage(client, "Node 机器人没有在运行，不能发消息。");
            return;
        }

        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            sendMessage(client, "消息不能为空。");
            return;
        }

        try {
            process.getOutputStream().write((trimmed + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            sendMessage(client, Component.empty()
                    .append(Component.literal("已发送给 Node：").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(trimmed).withStyle(ChatFormatting.YELLOW)));
        } catch (IOException exception) {
            sendMessage(client, Component.empty()
                    .append(Component.literal("发送失败：").withStyle(ChatFormatting.RED))
                    .append(Component.literal(exception.getMessage()).withStyle(ChatFormatting.YELLOW)));
        }
    }

    static synchronized String getShortStatusName() {
        if (isRunning()) {
            return "运行中";
        }
        return "已停止";
    }

    static synchronized Path getBotDirectory(Minecraft client) {
        return resolveBotDirectory(client);
    }

    static synchronized boolean isRunning() {
        pruneExitedProcess();
        return process != null && process.isAlive();
    }

    static synchronized String getStateName() {
        if (isRunning()) {
            return "RUNNING";
        }
        return "STOPPED";
    }

    static synchronized void setEnabled(Minecraft client, boolean value) {
        if (value) {
            start(client);
        } else {
            stop(client, "Node 机器人已停止。");
        }
    }

    static synchronized void start(Minecraft client) {
        if (!ScreenProbeGlobalConfig.get(client).enableNodeBot()) {
            sendMessage(client, "Node 机器人已在 /autosettings 全局设置中屏蔽。");
            return;
        }

        pruneExitedProcess();
        if (process != null && process.isAlive()) {
            sendMessage(client, "Node 机器人已经在运行。");
            return;
        }

        Path workDir = resolveBotDirectory(client);
        Path script = workDir.resolve("mc-bot.js");
        if (!Files.isRegularFile(script)) {
            createDirectoryIfMissing(workDir);
            sendMessage(client, Component.empty()
                    .append(Component.literal("没有找到当前版本里的机器人副本：").withStyle(ChatFormatting.RED))
                    .append(Component.literal(script.toString()).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        Path logDir = resolveLogDirectory(client);
        try {
            Files.createDirectories(logDir);
        } catch (IOException exception) {
            sendMessage(client, "创建 Node 机器人日志目录失败：" + exception.getMessage());
            return;
        }

        Path logFile = logDir.resolve("nodebot-" + LOG_TIME_FORMAT.format(LocalDateTime.now()) + ".log");
        ProcessBuilder builder = new ProcessBuilder("node", "mc-bot.js");
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.environment().put("MC_HEADLESS", "true");

        try {
            process = builder.start();
            activeWorkDir = workDir;
            activeLogFile = logFile;
            startedAt = LocalDateTime.now();
            sendMessage(client, Component.empty()
                    .append(Component.literal("Node 机器人已启动。").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" 副本目录：").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(workDir.toString()).withStyle(ChatFormatting.YELLOW)));
        } catch (IOException exception) {
            process = null;
            sendMessage(client, Component.empty()
                    .append(Component.literal("启动 Node 失败：").withStyle(ChatFormatting.RED))
                    .append(Component.literal(exception.getMessage()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("，请确认 node 已加入 PATH。").withStyle(ChatFormatting.RED)));
        }
    }

    static synchronized void stop(Minecraft client, String message) {
        pruneExitedProcess();
        if (process == null) {
            sendMessage(client, "Node 机器人没有在运行。");
            return;
        }

        stopProcess();
        process = null;
        startedAt = null;
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static synchronized void restart(Minecraft client) {
        if (process != null) {
            stopProcess();
            process = null;
            startedAt = null;
        }
        start(client);
    }

    private static synchronized void toggle(Minecraft client) {
        if (isRunning()) {
            stop(client, "Node 机器人已停止。");
        } else {
            start(client);
        }
    }

    static synchronized void sendStatus(Minecraft client) {
        boolean running = isRunning();
        MutableComponent message = Component.empty()
                .append(Component.literal("状态 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(running ? "运行中" : "已停止").withStyle(running ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal(" | 副本目录 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(resolveBotDirectory(client).toString()).withStyle(ChatFormatting.YELLOW));
        if (running && process != null) {
            message.append(Component.literal(" | PID ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(Long.toString(process.pid())).withStyle(ChatFormatting.GREEN));
        }
        if (activeLogFile != null) {
            message.append(Component.literal(" | 日志 ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(activeLogFile.toString()).withStyle(ChatFormatting.YELLOW));
        }
        if (startedAt != null) {
            message.append(Component.literal(" | 启动 ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(startedAt.toLocalTime().withNano(0).toString()).withStyle(ChatFormatting.GREEN));
        }
        sendMessage(client, message);
    }

    private static Path resolveBotDirectory(Minecraft client) {
        Path gameDir = resolveGameDirectory(client);
        String folderName = gameDir.getFileName() == null ? "" : gameDir.getFileName().toString();
        if (".minecraft".equalsIgnoreCase(folderName)) {
            String launchedVersion = client == null ? "" : client.getLaunchedVersion();
            if (launchedVersion != null && !launchedVersion.isBlank()) {
                return gameDir.resolve("versions").resolve(launchedVersion).resolve(BOT_DIR_NAME).toAbsolutePath().normalize();
            }
        }
        return gameDir.resolve(BOT_DIR_NAME).toAbsolutePath().normalize();
    }

    private static Path resolveLogDirectory(Minecraft client) {
        return resolveGameDirectory(client).resolve("logs").resolve("screenprobe").resolve("nodebot").toAbsolutePath().normalize();
    }

    private static Path resolveGameDirectory(Minecraft client) {
        File gameDirectory = client == null ? null : client.gameDirectory;
        if (gameDirectory == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return gameDirectory.toPath().toAbsolutePath().normalize();
    }

    private static void createDirectoryIfMissing(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("{} failed to create nodebot directory {}",
                    ScreenProbeGlobalConfig.prefix(Minecraft.getInstance(), CHAT_MODULE), directory, exception);
        }
    }

    private static void pruneExitedProcess() {
        if (process != null && !process.isAlive()) {
            int exitCode = process.exitValue();
            ScreenProbe.LOGGER.info("{} Node bot exited with code {}",
                    ScreenProbeGlobalConfig.prefix(Minecraft.getInstance(), CHAT_MODULE), exitCode);
            process = null;
            startedAt = null;
        }
    }

    private static void stopProcess() {
        Process current = process;
        if (current == null) {
            return;
        }

        current.destroy();
        try {
            if (!current.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static synchronized void stopQuietly() {
        if (process == null) {
            return;
        }
        stopProcess();
        process = null;
        startedAt = null;
    }

    private static void sendMessage(Minecraft client, String message) {
        sendMessage(client, Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    private static void sendMessage(Minecraft client, Component message) {
        String prefix = ScreenProbeGlobalConfig.prefix(client, CHAT_MODULE);
        ScreenProbe.LOGGER.info("{} {}", prefix, message.getString().replace('\n', ' '));
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.empty()
                            .append(Component.literal(prefix).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                            .append(message)
            , false);
        }
    }
}


