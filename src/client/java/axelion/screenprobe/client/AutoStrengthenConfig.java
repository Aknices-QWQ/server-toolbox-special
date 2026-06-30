package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class AutoStrengthenConfig {
    private static final String FILE_NAME = "auto-strengthen.properties";
    private static final int MIN_VALUE = 1;
    private static final int MAX_VALUE = 100;
    private static Config values = Config.defaults();
    private static boolean loaded;

    private AutoStrengthenConfig() {
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
                    readInt(properties, "defaultRolls", values.defaultRolls()),
                    readInt(properties, "damageThreshold", values.damageThreshold()),
                    readInt(properties, "defenseThreshold", values.defenseThreshold()),
                    readInt(properties, "bothDamageThreshold", values.bothDamageThreshold()),
                    readInt(properties, "bothDefenseThreshold", values.bothDefenseThreshold()),
                    readInt(properties, "focusChanceThreshold", values.focusChanceThreshold()),
                    readBoolean(properties, "autoOpenStrengthenMenu", values.autoOpenStrengthenMenu()),
                    readBoolean(properties, "autoInsertHeldItem", values.autoInsertHeldItem()),
                    readBoolean(properties, "clearBeforeStart", values.clearBeforeStart()),
                    readBoolean(properties, "autoClearMixedLowAttributes", values.autoClearMixedLowAttributes()),
                    readInt(properties, "autoClearAttributeCount", values.autoClearAttributeCount()),
                    readInt(properties, "autoClearAttributeSumThreshold", values.autoClearAttributeSumThreshold()),
                    readString(properties, "cdCommand", values.cdCommand()),
                    readString(properties, "equipmentCategoryLabel", values.equipmentCategoryLabel()),
                    readString(properties, "strengthenMenuLabel", values.strengthenMenuLabel()),
                    readString(properties, "clearAttributesMenuLabel", values.clearAttributesMenuLabel()),
                    readString(properties, "clearAttributesConfirmLabel", values.clearAttributesConfirmLabel()),
                    readBoolean(properties, "feishuNotificationEnabled", values.feishuNotificationEnabled()),
                    readString(properties, "feishuWebhookUrl", values.feishuWebhookUrl()),
                    readString(properties, "feishuSecret", values.feishuSecret())
            ).clamped();
        } catch (IOException | NumberFormatException exception) {
            ScreenProbe.LOGGER.warn("Failed to load auto strengthen config from {}", path.toAbsolutePath(), exception);
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
        properties.setProperty("defaultRolls", Integer.toString(config.defaultRolls()));
        properties.setProperty("damageThreshold", Integer.toString(config.damageThreshold()));
        properties.setProperty("defenseThreshold", Integer.toString(config.defenseThreshold()));
        properties.setProperty("bothDamageThreshold", Integer.toString(config.bothDamageThreshold()));
        properties.setProperty("bothDefenseThreshold", Integer.toString(config.bothDefenseThreshold()));
        properties.setProperty("focusChanceThreshold", Integer.toString(config.focusChanceThreshold()));
        properties.setProperty("autoOpenStrengthenMenu", Boolean.toString(config.autoOpenStrengthenMenu()));
        properties.setProperty("autoInsertHeldItem", Boolean.toString(config.autoInsertHeldItem()));
        properties.setProperty("clearBeforeStart", Boolean.toString(config.clearBeforeStart()));
        properties.setProperty("autoClearMixedLowAttributes", Boolean.toString(config.autoClearMixedLowAttributes()));
        properties.setProperty("autoClearAttributeCount", Integer.toString(config.autoClearAttributeCount()));
        properties.setProperty("autoClearAttributeSumThreshold", Integer.toString(config.autoClearAttributeSumThreshold()));
        properties.setProperty("cdCommand", config.cdCommand());
        properties.setProperty("equipmentCategoryLabel", config.equipmentCategoryLabel());
        properties.setProperty("strengthenMenuLabel", config.strengthenMenuLabel());
        properties.setProperty("clearAttributesMenuLabel", config.clearAttributesMenuLabel());
        properties.setProperty("clearAttributesConfirmLabel", config.clearAttributesConfirmLabel());
        properties.setProperty("feishuNotificationEnabled", Boolean.toString(config.feishuNotificationEnabled()));
        properties.setProperty("feishuWebhookUrl", config.feishuWebhookUrl());
        properties.setProperty("feishuSecret", config.feishuSecret());

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ScreenProbe auto strengthen settings");
            }
        } catch (IOException exception) {
            ScreenProbe.LOGGER.warn("Failed to save auto strengthen config to {}", path.toAbsolutePath(), exception);
        }
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

    private static Path configPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
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

    record Config(int defaultRolls, int damageThreshold, int defenseThreshold,
                  int bothDamageThreshold, int bothDefenseThreshold, int focusChanceThreshold,
                  boolean autoOpenStrengthenMenu, boolean autoInsertHeldItem, boolean clearBeforeStart,
                  boolean autoClearMixedLowAttributes, int autoClearAttributeCount,
                  int autoClearAttributeSumThreshold, String cdCommand, String equipmentCategoryLabel,
                  String strengthenMenuLabel, String clearAttributesMenuLabel,
                  String clearAttributesConfirmLabel, boolean feishuNotificationEnabled,
                  String feishuWebhookUrl, String feishuSecret) {
        static Config defaults() {
            return new Config(4, 7, 7, 5, 5, 7,
                    true, true, false,
                    true, 2, 3,
                    "cd", "装备属性", "装备强化", "清除属性", "确认清除",
                    false, "", "");
        }

        Config clamped() {
            Config defaults = defaults();
            return new Config(
                    clamp(defaultRolls, 1, 64),
                    clamp(damageThreshold, MIN_VALUE, MAX_VALUE),
                    clamp(defenseThreshold, MIN_VALUE, MAX_VALUE),
                    clamp(bothDamageThreshold, MIN_VALUE, MAX_VALUE),
                    clamp(bothDefenseThreshold, MIN_VALUE, MAX_VALUE),
                    clamp(focusChanceThreshold, MIN_VALUE, MAX_VALUE),
                    autoOpenStrengthenMenu,
                    autoInsertHeldItem,
                    clearBeforeStart,
                    autoClearMixedLowAttributes,
                    clamp(autoClearAttributeCount, 2, 6),
                    clamp(autoClearAttributeSumThreshold, 1, MAX_VALUE),
                    nonBlank(cdCommand, defaults.cdCommand()),
                    nonBlank(equipmentCategoryLabel, defaults.equipmentCategoryLabel()),
                    nonBlank(strengthenMenuLabel, defaults.strengthenMenuLabel()),
                    nonBlank(clearAttributesMenuLabel, defaults.clearAttributesMenuLabel()),
                    nonBlank(clearAttributesConfirmLabel, defaults.clearAttributesConfirmLabel()),
                    feishuNotificationEnabled,
                    feishuWebhookUrl == null ? "" : feishuWebhookUrl.trim(),
                    feishuSecret == null ? "" : feishuSecret.trim()
            );
        }
    }
}
