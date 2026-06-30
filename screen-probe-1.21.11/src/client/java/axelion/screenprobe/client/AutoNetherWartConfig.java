package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class AutoNetherWartConfig {
    private static final String FILE_NAME = "auto-nether-wart.properties";
    private static Config values = Config.defaults();
    private static boolean loaded;

    private AutoNetherWartConfig() {
    }

    static Config get(Minecraft client) {
        ensureLoaded(client);
        return values;
    }

    static void setAndSave(Minecraft client, Config config) {
        values = config.clamped();
        loaded = true;
        save(client);
    }

    static void reload(Minecraft client) {
        loaded = false;
        ensureLoaded(client);
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
                    readBoolean(properties, "enabled", values.enabled()),
                    readInt(properties, "scanRadius", values.scanRadius()),
                    readInt(properties, "actionsPerTick", values.actionsPerTick()),
                    readBoolean(properties, "autoWalk", values.autoWalk()),
                    readBoolean(properties, "minecartForwardAssist", values.minecartForwardAssist()),
                    readBoolean(properties, "autoRideMinecart", values.autoRideMinecart()),
                    readBoolean(properties, "autoPlaceMinecart", values.autoPlaceMinecart()),
                    readBoolean(properties, "autoClearNonPlayerMinecart", values.autoClearNonPlayerMinecart()),
                    readBoolean(properties, "autoRespawnReturn", values.autoRespawnReturn()),
                    readBoolean(properties, "spectatorDangerDetection", values.spectatorDangerDetection()),
                    readInt(properties, "respawnReturnDelayTicks", values.respawnReturnDelayTicks()),
                    readInt(properties, "dangerReturnDelayTicks", values.dangerReturnDelayTicks()),
                    readInt(properties, "dangerCommandCooldownTicks", values.dangerCommandCooldownTicks()),
                    readInt(properties, "startTeleportCooldownTicks", values.startTeleportCooldownTicks()),
                    readInt(properties, "minecartActionCooldownTicks", values.minecartActionCooldownTicks()),
                    readInt(properties, "refillScreenDelayTicks", values.refillScreenDelayTicks()),
                    readInt(properties, "refillTimeoutTicks", values.refillTimeoutTicks()),
                    readInt(properties, "storageRefillStacksPerRun", values.storageRefillStacksPerRun()),
                    readString(properties, "startTerritory", values.startTerritory()),
                    readString(properties, "safeTerritory", values.safeTerritory()),
                    readString(properties, "dangerPlayerName", values.dangerPlayerName()),
                    readString(properties, "cropStorageCommand", values.cropStorageCommand())
            ).clamped();
        } catch (IOException | NumberFormatException exception) {
            ScreenProbe.LOGGER.warn("Failed to load auto nether wart config from {}", path.toAbsolutePath(), exception);
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
        properties.setProperty("enabled", Boolean.toString(config.enabled()));
        properties.setProperty("scanRadius", Integer.toString(config.scanRadius()));
        properties.setProperty("actionsPerTick", Integer.toString(config.actionsPerTick()));
        properties.setProperty("autoWalk", Boolean.toString(config.autoWalk()));
        properties.setProperty("minecartForwardAssist", Boolean.toString(config.minecartForwardAssist()));
        properties.setProperty("autoRideMinecart", Boolean.toString(config.autoRideMinecart()));
        properties.setProperty("autoPlaceMinecart", Boolean.toString(config.autoPlaceMinecart()));
        properties.setProperty("autoClearNonPlayerMinecart", Boolean.toString(config.autoClearNonPlayerMinecart()));
        properties.setProperty("autoRespawnReturn", Boolean.toString(config.autoRespawnReturn()));
        properties.setProperty("spectatorDangerDetection", Boolean.toString(config.spectatorDangerDetection()));
        properties.setProperty("respawnReturnDelayTicks", Integer.toString(config.respawnReturnDelayTicks()));
        properties.setProperty("dangerReturnDelayTicks", Integer.toString(config.dangerReturnDelayTicks()));
        properties.setProperty("dangerCommandCooldownTicks", Integer.toString(config.dangerCommandCooldownTicks()));
        properties.setProperty("startTeleportCooldownTicks", Integer.toString(config.startTeleportCooldownTicks()));
        properties.setProperty("minecartActionCooldownTicks", Integer.toString(config.minecartActionCooldownTicks()));
        properties.setProperty("refillScreenDelayTicks", Integer.toString(config.refillScreenDelayTicks()));
        properties.setProperty("refillTimeoutTicks", Integer.toString(config.refillTimeoutTicks()));
        properties.setProperty("storageRefillStacksPerRun", Integer.toString(config.storageRefillStacksPerRun()));
        properties.setProperty("startTerritory", config.startTerritory());
        properties.setProperty("safeTerritory", config.safeTerritory());
        properties.setProperty("dangerPlayerName", config.dangerPlayerName());
        properties.setProperty("cropStorageCommand", config.cropStorageCommand());

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ScreenProbe auto nether wart settings");
            }
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("Failed to save auto nether wart config to {}", path.toAbsolutePath(), exception);
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static String readString(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        value = value.trim();
        return value.isEmpty() ? fallback : value;
    }

    private static Path configPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record Config(boolean enabled, int scanRadius, int actionsPerTick, boolean autoWalk,
                  boolean minecartForwardAssist, boolean autoRideMinecart, boolean autoPlaceMinecart,
                  boolean autoClearNonPlayerMinecart, boolean autoRespawnReturn, boolean spectatorDangerDetection,
                  int respawnReturnDelayTicks, int dangerReturnDelayTicks, int dangerCommandCooldownTicks,
                  int startTeleportCooldownTicks, int minecartActionCooldownTicks,
                  int refillScreenDelayTicks, int refillTimeoutTicks, int storageRefillStacksPerRun,
                  String startTerritory, String safeTerritory, String dangerPlayerName, String cropStorageCommand) {
        static Config defaults() {
            return new Config(
                    false,
                    6,
                    3,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    20,
                    20 * 3,
                    20 * 15,
                    20 * 10,
                    10,
                    6,
                    20 * 8,
                    6,
                    "akwart1",
                    "homekit",
                    "Ranica",
                    "cd"
            );
        }

        Config clamped() {
            return new Config(
                    enabled,
                    clamp(scanRadius, 1, 6),
                    clamp(actionsPerTick, 1, 5),
                    autoWalk,
                    minecartForwardAssist,
                    autoRideMinecart,
                    autoPlaceMinecart,
                    autoClearNonPlayerMinecart,
                    autoRespawnReturn,
                    spectatorDangerDetection,
                    clamp(respawnReturnDelayTicks, 0, 20 * 60),
                    clamp(dangerReturnDelayTicks, 0, 20 * 60),
                    clamp(dangerCommandCooldownTicks, 0, 20 * 60 * 10),
                    clamp(startTeleportCooldownTicks, 0, 20 * 60 * 10),
                    clamp(minecartActionCooldownTicks, 0, 20 * 10),
                    clamp(refillScreenDelayTicks, 0, 20 * 10),
                    clamp(refillTimeoutTicks, 20, 20 * 60 * 10),
                    clamp(storageRefillStacksPerRun, 1, 36),
                    sanitize(startTerritory, defaults().startTerritory()),
                    sanitize(safeTerritory, defaults().safeTerritory()),
                    sanitize(dangerPlayerName, defaults().dangerPlayerName()),
                    sanitize(cropStorageCommand, defaults().cropStorageCommand())
            );
        }

        private static String sanitize(String value, String fallback) {
            if (value == null) {
                return fallback;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? fallback : trimmed;
        }
    }
}
