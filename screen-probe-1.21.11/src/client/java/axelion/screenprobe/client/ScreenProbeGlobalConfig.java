package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScreenProbeGlobalConfig {
    static final String BRAND = "【特供】";
    static final String BUILD_NOTICE = "构建版本：2026-06-30 DEV build 仅供内部使用 严禁截图严禁外传 1.21.11历史版本，不再更新；后续仅维护26.1.2";
    private static final String FILE_NAME = "ranmc-toolbox-global.properties";
    private static final Pattern TPS_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*20\\.00");
    private static final int TPS_QUERY_INTERVAL_TICKS = 20 * 30;
    private static Config values = Config.defaults();
    private static boolean loaded;
    private static double lastTps = 20.0D;
    private static int tpsQueryCooldownTicks;

    private ScreenProbeGlobalConfig() {
    }

    static Config get(Minecraft client) {
        ensureLoaded(client);
        return values;
    }

    static String prefix(Minecraft client) {
        return BRAND + "使用用户:" + currentUserName(client);
    }

    static String prefix(Minecraft client, String module) {
        return prefix(client) + " " + module;
    }

    private static String currentUserName(Minecraft client) {
        if (client != null && client.player != null && client.player.getName() != null) {
            String name = client.player.getName().getString();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "未知";
    }

    static void setAndSave(Minecraft client, Config config) {
        values = config.clamped();
        loaded = true;
        save(client);
    }

    static int menuActionDelayTicks(Minecraft client) {
        return scaledDelay(client, get(client).menuActionDelayTicks());
    }

    static int menuResultDelayTicks(Minecraft client) {
        return scaledDelay(client, get(client).menuResultDelayTicks());
    }

    static int menuCloseDelayTicks(Minecraft client) {
        return scaledDelay(client, get(client).menuCloseDelayTicks());
    }

    static void tick(Minecraft client) {
        Config config = get(client);
        if (!config.lowTpsAutoSlowdown() || client == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (tpsQueryCooldownTicks > 0) {
            tpsQueryCooldownTicks--;
            return;
        }
        tpsQueryCooldownTicks = Math.max(20, config.tpsQueryIntervalTicks());
        client.getConnection().sendCommand("ranmc:tps");
    }

    static void handleServerMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Matcher matcher = TPS_PATTERN.matcher(message.replace(',', '.'));
        if (!matcher.find()) {
            return;
        }
        try {
            lastTps = Math.max(0.0D, Math.min(20.0D, Double.parseDouble(matcher.group(1))));
        } catch (NumberFormatException ignored) {
        }
    }

    static double lastTps() {
        return lastTps;
    }

    private static int scaledDelay(Minecraft client, int baseDelay) {
        Config config = get(client);
        if (!config.lowTpsAutoSlowdown() || lastTps >= config.lowTpsThreshold()) {
            return baseDelay;
        }
        return Math.min(200, baseDelay * config.lowTpsDelayMultiplier());
    }

    private static void ensureLoaded(Minecraft client) {
        if (loaded) {
            return;
        }
        loaded = true;
        values = Config.defaults();
        if (client == null) {
            return;
        }

        Path path = configPath(client);
        if (!Files.exists(path)) {
            save(client);
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            values = new Config(
                    readInt(properties, "menuActionDelayTicks", values.menuActionDelayTicks()),
                    readInt(properties, "menuResultDelayTicks", values.menuResultDelayTicks()),
                    readInt(properties, "menuCloseDelayTicks", values.menuCloseDelayTicks()),
                    readBoolean(properties, "enableAutoWart", values.enableAutoWart()),
                    readBoolean(properties, "enableAutoStrengthen", values.enableAutoStrengthen()),
                    readBoolean(properties, "enableAutoSell", values.enableAutoSell()),
                    readBoolean(properties, "enableAutoBoat", values.enableAutoBoat()),
                    readBoolean(properties, "enableNodeBot", values.enableNodeBot()),
                    readBoolean(properties, "enableAutoDragon", values.enableAutoDragon()),
                    readBoolean(properties, "enableAutoChunk", values.enableAutoChunk()),
                    readBoolean(properties, "enableAutoVillagerTrade", values.enableAutoVillagerTrade()),
                    readBoolean(properties, "lowTpsAutoSlowdown", values.lowTpsAutoSlowdown()),
                    readDouble(properties, "lowTpsThreshold", values.lowTpsThreshold()),
                    readInt(properties, "lowTpsDelayMultiplier", values.lowTpsDelayMultiplier()),
                    readInt(properties, "tpsQueryIntervalTicks", values.tpsQueryIntervalTicks())
            ).clamped();
        } catch (IOException | NumberFormatException exception) {
            ScreenProbe.LOGGER.warn("Failed to load global config from {}", path.toAbsolutePath(), exception);
            values = Config.defaults();
        }
    }

    private static void save(Minecraft client) {
        if (client == null) {
            return;
        }
        Path path = configPath(client);
        Properties properties = new Properties();
        Config config = values.clamped();
        properties.setProperty("menuActionDelayTicks", Integer.toString(config.menuActionDelayTicks()));
        properties.setProperty("menuResultDelayTicks", Integer.toString(config.menuResultDelayTicks()));
        properties.setProperty("menuCloseDelayTicks", Integer.toString(config.menuCloseDelayTicks()));
        properties.setProperty("enableAutoWart", Boolean.toString(config.enableAutoWart()));
        properties.setProperty("enableAutoStrengthen", Boolean.toString(config.enableAutoStrengthen()));
        properties.setProperty("enableAutoSell", Boolean.toString(config.enableAutoSell()));
        properties.setProperty("enableAutoBoat", Boolean.toString(config.enableAutoBoat()));
        properties.setProperty("enableNodeBot", Boolean.toString(config.enableNodeBot()));
        properties.setProperty("enableAutoDragon", Boolean.toString(config.enableAutoDragon()));
        properties.setProperty("enableAutoChunk", Boolean.toString(config.enableAutoChunk()));
        properties.setProperty("enableAutoVillagerTrade", Boolean.toString(config.enableAutoVillagerTrade()));
        properties.setProperty("lowTpsAutoSlowdown", Boolean.toString(config.lowTpsAutoSlowdown()));
        properties.setProperty("lowTpsThreshold", String.format(Locale.ROOT, "%.2f", config.lowTpsThreshold()));
        properties.setProperty("lowTpsDelayMultiplier", Integer.toString(config.lowTpsDelayMultiplier()));
        properties.setProperty("tpsQueryIntervalTicks", Integer.toString(config.tpsQueryIntervalTicks()));

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ScreenProbe global settings");
            }
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("Failed to save global config to {}", path.toAbsolutePath(), exception);
        }
    }

    private static Path configPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static double readDouble(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(value.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record Config(int menuActionDelayTicks, int menuResultDelayTicks, int menuCloseDelayTicks,
                  boolean enableAutoWart, boolean enableAutoStrengthen, boolean enableAutoSell,
                  boolean enableAutoBoat, boolean enableNodeBot, boolean enableAutoDragon,
                  boolean enableAutoChunk, boolean enableAutoVillagerTrade,
                  boolean lowTpsAutoSlowdown, double lowTpsThreshold,
                  int lowTpsDelayMultiplier, int tpsQueryIntervalTicks) {
        static Config defaults() {
            return new Config(12, 16, 6,
                    true, true, true,
                    true, true, true,
                    true, true,
                    true, 15.0D, 2, 20 * 30);
        }

        Config clamped() {
            return new Config(
                    clamp(menuActionDelayTicks, 0, 60),
                    clamp(menuResultDelayTicks, 0, 100),
                    clamp(menuCloseDelayTicks, 0, 40),
                    enableAutoWart,
                    enableAutoStrengthen,
                    enableAutoSell,
                    enableAutoBoat,
                    enableNodeBot,
                    enableAutoDragon,
                    enableAutoChunk,
                    enableAutoVillagerTrade,
                    lowTpsAutoSlowdown,
                    clamp(lowTpsThreshold, 5.0D, 20.0D),
                    clamp(lowTpsDelayMultiplier, 1, 5),
                    clamp(tpsQueryIntervalTicks, 20, 20 * 300)
            );
        }
    }
}
