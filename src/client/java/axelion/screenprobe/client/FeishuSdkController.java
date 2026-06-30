package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FeishuSdkController {
    private static final String CHAT_MODULE = "飞书";
    private static final Pattern TEXT_CONTENT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static Client wsClient;
    private static boolean wsRunning;

    private FeishuSdkController() {
    }

    static void sendTestMessage(Minecraft client) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (!isMessageConfigReady(config)) {
            sendMessage(client, "飞书 SDK 配置不完整：需要 App ID、App Secret、接收类型、接收 ID。");
            return;
        }
        String player = client.player == null ? "-" : client.player.getName().getString();
        AutoStrengthenController.sendFeishuTextMessage(config,
                "飞书测试消息\n玩家：" + player + "\n来源：ranmc: toolbox 自动强化",
                result -> {
                    if (client != null) {
                        client.execute(() -> sendMessage(client, result));
                    }
                });
        sendMessage(client, "飞书测试消息已提交，等待 SDK 返回。");
    }

    static void startLongConnection(Minecraft client) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (config.feishuAppId().isBlank() || config.feishuAppSecret().isBlank()) {
            sendMessage(client, "飞书长连接配置不完整：需要 App ID 和 App Secret。");
            return;
        }
        stopLongConnection(client, false);
        Thread thread = new Thread(() -> {
            try {
                EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                        .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                            @Override
                            public void handle(P2MessageReceiveV1 event) {
                                handleMessageEvent(client, event);
                            }
                        })
                        .build();
                wsClient = new Client.Builder(config.feishuAppId(), config.feishuAppSecret())
                        .eventHandler(dispatcher)
                        .autoReconnect(true)
                        .onReconnecting(() -> ScreenProbe.LOGGER.info("Feishu long connection reconnecting"))
                        .onReconnected(() -> ScreenProbe.LOGGER.info("Feishu long connection reconnected"))
                        .build();
                wsClient.start();
                wsRunning = true;
                ScreenProbe.LOGGER.info("Feishu long connection started");
            } catch (Exception exception) {
                wsRunning = false;
                ScreenProbe.LOGGER.warn("Failed to start Feishu long connection", exception);
            }
        }, "ranmc-feishu-ws");
        thread.setDaemon(true);
        thread.start();
        sendMessage(client, "飞书长连接正在启动。");
    }

    static void stopLongConnection(Minecraft client) {
        stopLongConnection(client, true);
    }

    static void sendStatus(Minecraft client) {
        sendMessage(client, "长连接状态：" + (wsRunning ? "已启动" : "未启动"));
    }

    private static void stopLongConnection(Minecraft client, boolean notify) {
        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception exception) {
                ScreenProbe.LOGGER.warn("Failed to close Feishu long connection", exception);
            }
        }
        wsClient = null;
        wsRunning = false;
        if (notify) {
            sendMessage(client, "飞书长连接已关闭。");
        }
    }

    private static boolean isMessageConfigReady(AutoStrengthenConfig.Config config) {
        return !config.feishuAppId().isBlank()
                && !config.feishuAppSecret().isBlank()
                && !config.feishuReceiveIdType().isBlank()
                && !config.feishuReceiveId().isBlank();
    }

    private static void handleMessageEvent(Minecraft client, P2MessageReceiveV1 event) {
        if (client == null || event == null || event.getEvent() == null) {
            return;
        }
        EventMessage message = event.getEvent().getMessage();
        if (message == null || message.getMessageType() == null || !"text".equalsIgnoreCase(message.getMessageType())) {
            return;
        }
        String text = extractText(message.getContent());
        if (text.isBlank()) {
            return;
        }
        ScreenProbe.LOGGER.info("Feishu command received: {}", text);
        client.execute(() -> handleRemoteCommand(client, text));
    }

    private static void handleRemoteCommand(Minecraft client, String text) {
        String normalized = normalize(text);
        if (normalized.equals("autominediamond") || normalized.contains("挖钻石") || normalized.contains("钻石挖矿")) {
            AutoMineController.startDiamondFromRemote(client);
            reply(client, "已执行远程指令：自动挖钻石。");
            return;
        }
        if (normalized.equals("autominedebris") || normalized.contains("挖残骸") || normalized.contains("远古残骸")) {
            AutoMineController.startDebrisFromRemote(client);
            reply(client, "已执行远程指令：自动挖残骸。");
            return;
        }
        if (normalized.equals("autominenetherite") || normalized.contains("挖下界合金")) {
            AutoMineController.startDebrisFromRemote(client);
            reply(client, "已执行远程指令：自动挖残骸。");
            return;
        }
        if (normalized.equals("autominestop") || normalized.contains("停止挖矿") || normalized.contains("关闭挖矿")) {
            AutoMineController.stopFromRemote(client);
            reply(client, "已执行远程指令：停止自动挖矿。");
            return;
        }
        if (normalized.equals("autominestatus") || normalized.contains("挖矿状态")) {
            AutoMineController.statusFromRemote(client);
            reply(client, "已执行远程指令：查询自动挖矿状态。");
            return;
        }
        if (normalized.equals("autominehelp") || normalized.contains("挖矿帮助")) {
            reply(client, "飞书控制自动挖矿：\nautomine diamond\nautomine debris\nautomine stop\nautomine status");
        }
    }

    private static void reply(Minecraft client, String text) {
        AutoStrengthenConfig.Config config = AutoStrengthenConfig.get(client);
        if (isMessageConfigReady(config)) {
            AutoStrengthenController.sendFeishuTextMessage(config, text);
        }
        sendMessage(client, text);
    }

    private static String extractText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher matcher = TEXT_CONTENT_PATTERN.matcher(content);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1)).trim();
        }
        return content.trim();
    }

    private static String unescapeJson(String value) {
        return value.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("§.", "")
                .replaceAll("\\s+", "")
                .replace("/", "")
                .replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
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
}
