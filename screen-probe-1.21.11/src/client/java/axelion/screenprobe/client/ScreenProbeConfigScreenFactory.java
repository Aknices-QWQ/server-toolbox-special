package axelion.screenprobe.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ScreenProbeConfigScreenFactory {
    private ScreenProbeConfigScreenFactory() {
    }

    static void preload() {
    }

    static Screen create(Screen parent) {
        return new SimpleSettingsScreen(parent);
    }

    private static final class SimpleSettingsScreen extends Screen {
        private final Screen parent;

        private SimpleSettingsScreen(Screen parent) {
            super(Component.literal("ScreenProbe 设置"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            clearWidgets();
            Minecraft client = Minecraft.getInstance();
            ScreenProbeGlobalConfig.Config config = ScreenProbeGlobalConfig.get(client);
            int x = width / 2 - 155;
            int y = 34;
            int w = 150;
            int h = 20;
            int gap = 24;

            addButton(x, y, w, h, toggleLabel("自动售卖", config.enableAutoSell()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            config.enableAutoWart(), config.enableAutoStrengthen(), !config.enableAutoSell(), config.enableAutoBoat(),
                            config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));
            addButton(x + 160, y, w, h, toggleLabel("自动下界疣", config.enableAutoWart()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            !config.enableAutoWart(), config.enableAutoStrengthen(), config.enableAutoSell(), config.enableAutoBoat(),
                            config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));

            y += gap;
            addButton(x, y, w, h, toggleLabel("自动强化", config.enableAutoStrengthen()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            config.enableAutoWart(), !config.enableAutoStrengthen(), config.enableAutoSell(), config.enableAutoBoat(),
                            config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));
            addButton(x + 160, y, w, h, toggleLabel("自动补船", config.enableAutoBoat()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            config.enableAutoWart(), config.enableAutoStrengthen(), config.enableAutoSell(), !config.enableAutoBoat(),
                            config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));

            y += gap;
            addButton(x, y, w, h, toggleLabel("Node机器人", config.enableNodeBot()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            config.enableAutoWart(), config.enableAutoStrengthen(), config.enableAutoSell(), config.enableAutoBoat(),
                            !config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));
            addButton(x + 160, y, w, h, toggleLabel("低TPS降速", config.lowTpsAutoSlowdown()), button -> updateGlobal(client,
                    new ScreenProbeGlobalConfig.Config(config.menuActionDelayTicks(), config.menuResultDelayTicks(), config.menuCloseDelayTicks(),
                            config.enableAutoWart(), config.enableAutoStrengthen(), config.enableAutoSell(), config.enableAutoBoat(),
                            config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                            !config.lowTpsAutoSlowdown(), config.lowTpsThreshold(), config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks())));

            y += gap + 8;
            addDelayButtons(client, config, x, y, w, h, "页面延迟", config.menuActionDelayTicks(), DelayField.ACTION);
            addDelayButtons(client, config, x + 160, y, w, h, "结果延迟", config.menuResultDelayTicks(), DelayField.RESULT);
            y += gap;
            addDelayButtons(client, config, x, y, w, h, "关闭延迟", config.menuCloseDelayTicks(), DelayField.CLOSE);
            addDelayButtons(client, config, x + 160, y, w, h, "TPS阈值", (int) config.lowTpsThreshold(), DelayField.TPS_THRESHOLD);

            y += gap + 10;
            addButton(width / 2 - 75, y, 150, h, "完成", button -> onClose());
        }

        private void addDelayButtons(Minecraft client, ScreenProbeGlobalConfig.Config config, int x, int y, int w, int h,
                                     String label, int value, DelayField field) {
            addButton(x, y, 45, h, label + "-", button -> updateDelay(client, config, field, -1));
            addButton(x + 50, y, w - 50, h, label + ": " + value, button -> updateDelay(client, config, field, 1));
        }

        private void addButton(int x, int y, int w, int h, String label, Button.OnPress action) {
            addRenderableWidget(Button.builder(Component.literal(label), action).bounds(x, y, w, h).build());
        }

        private String toggleLabel(String label, boolean enabled) {
            return label + ": " + (enabled ? "开" : "关");
        }

        private void updateDelay(Minecraft client, ScreenProbeGlobalConfig.Config config, DelayField field, int delta) {
            int action = config.menuActionDelayTicks();
            int result = config.menuResultDelayTicks();
            int close = config.menuCloseDelayTicks();
            double tps = config.lowTpsThreshold();
            if (field == DelayField.ACTION) {
                action = clamp(action + delta, 0, 60);
            } else if (field == DelayField.RESULT) {
                result = clamp(result + delta, 0, 100);
            } else if (field == DelayField.CLOSE) {
                close = clamp(close + delta, 0, 40);
            } else if (field == DelayField.TPS_THRESHOLD) {
                tps = clamp((int) tps + delta, 5, 20);
            }
            updateGlobal(client, new ScreenProbeGlobalConfig.Config(action, result, close,
                    config.enableAutoWart(), config.enableAutoStrengthen(), config.enableAutoSell(), config.enableAutoBoat(),
                    config.enableNodeBot(), config.enableAutoDragon(), config.enableAutoChunk(), config.enableAutoVillagerTrade(),
                    config.lowTpsAutoSlowdown(), tps, config.lowTpsDelayMultiplier(), config.tpsQueryIntervalTicks()));
        }

        private void updateGlobal(Minecraft client, ScreenProbeGlobalConfig.Config config) {
            ScreenProbeGlobalConfig.Config saved = config.clamped();
            ScreenProbeGlobalConfig.setAndSave(client, saved);
            ScreenProbeClient.applyGlobalConfig(client, saved);
            rebuildButtons();
        }

        private int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, ScreenProbeGlobalConfig.prefix(Minecraft.getInstance()) + " 设置", width / 2, 12, 0xFFFF55);
            graphics.drawCenteredString(font, "更多详细项仍可通过 config/*.properties 调整", width / 2, height - 28, ChatFormatting.GRAY.getColor());
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }
    }

    private enum DelayField {
        ACTION,
        RESULT,
        CLOSE,
        TPS_THRESHOLD
    }
}
