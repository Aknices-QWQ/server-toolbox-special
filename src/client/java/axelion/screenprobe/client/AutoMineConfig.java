package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

final class AutoMineConfig {
    private static final String FILE_NAME = "auto-mine.properties";
    private static Config values = Config.defaults();
    private static boolean loaded;

    private AutoMineConfig() {
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

    static void markNetherPointDone(Minecraft client, int index) {
        Config current = get(client);
        Set<Integer> completed = new TreeSet<>(current.completedNetherPoints());
        completed.add(index);
        setAndSave(client, new Config(current.enabled(), current.reportIntervalSeconds(),
                current.netherTargetStacksPerPoint(), current.resourceRandomTeleportCommand(),
                current.completedNetherPointsRaw(), completed));
    }

    static void clearCompletedNetherPoints(Minecraft client) {
        Config current = get(client);
        setAndSave(client, new Config(current.enabled(), current.reportIntervalSeconds(),
                current.netherTargetStacksPerPoint(), current.resourceRandomTeleportCommand(), "", Set.of()));
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
            String completedRaw = readString(properties, "completedNetherPoints", "");
            values = new Config(
                    readBoolean(properties, "enabled", values.enabled()),
                    readInt(properties, "reportIntervalSeconds", values.reportIntervalSeconds()),
                    readInt(properties, "netherTargetStacksPerPoint", values.netherTargetStacksPerPoint()),
                    readString(properties, "resourceRandomTeleportCommand", values.resourceRandomTeleportCommand()),
                    completedRaw,
                    parseCompleted(completedRaw)
            ).clamped();
        } catch (IOException | NumberFormatException exception) {
            ScreenProbe.LOGGER.warn("Failed to load auto mine config from {}", path.toAbsolutePath(), exception);
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
        properties.setProperty("reportIntervalSeconds", Integer.toString(config.reportIntervalSeconds()));
        properties.setProperty("netherTargetStacksPerPoint", Integer.toString(config.netherTargetStacksPerPoint()));
        properties.setProperty("resourceRandomTeleportCommand", config.resourceRandomTeleportCommand());
        properties.setProperty("completedNetherPoints", formatCompleted(config.completedNetherPoints()));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ranmc toolbox auto mine settings");
            }
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("Failed to save auto mine config to {}", path.toAbsolutePath(), exception);
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

    private static String readString(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static Set<Integer> parseCompleted(String value) {
        Set<Integer> result = new HashSet<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            try {
                int index = Integer.parseInt(part.trim());
                if (index >= 1 && index <= 100) {
                    result.add(index);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static String formatCompleted(Set<Integer> completed) {
        StringBuilder builder = new StringBuilder();
        for (int index : new TreeSet<>(completed)) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(index);
        }
        return builder.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    record Config(boolean enabled, int reportIntervalSeconds, int netherTargetStacksPerPoint,
                  String resourceRandomTeleportCommand, String completedNetherPointsRaw,
                  Set<Integer> completedNetherPoints) {
        static Config defaults() {
            return new Config(true, 300, 6, "rtp", "", Set.of());
        }

        Config clamped() {
            return new Config(
                    enabled,
                    clamp(reportIntervalSeconds, 30, 3600),
                    clamp(netherTargetStacksPerPoint, 1, 27),
                    nonBlank(resourceRandomTeleportCommand, "rtp"),
                    completedNetherPointsRaw == null ? "" : completedNetherPointsRaw,
                    Set.copyOf(completedNetherPoints == null ? Set.of() : completedNetherPoints)
            );
        }
    }
}
