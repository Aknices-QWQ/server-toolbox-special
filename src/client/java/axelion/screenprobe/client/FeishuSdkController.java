package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class FeishuSdkController {
    private static final String CHAT_MODULE = "飞书";
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
                "飞书测试消息\n玩家：" + player + "\n来源：ranmc: toolbox 自动强化");
        sendMessage(client, "已发送飞书测试消息。");
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
                EventDispatcher dispatcher = EventDispatcher.newBuilder("", "").build();
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
