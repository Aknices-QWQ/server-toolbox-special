package axelion.screenprobe.client;

import axelion.screenprobe.ScreenProbe;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CdHelpDumpController {
    private static final String COMMAND = "cddump";
    private static final String CHAT_MODULE = "CD采集";
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int ACTION_DELAY_TICKS = 12;
    private static final int TIMEOUT_TICKS = 20 * 20;
    private static final String ROOT_COMMAND = "cd";
    private static final DumpTarget ENHANCE_HELP = new DumpTarget(
            "enhance",
            "强化介绍",
            "cd-enhance",
            List.of("游戏帮助", "强化介绍")
    );
    private static final DumpTarget EQUIPMENT_STRENGTHEN = new DumpTarget(
            "equipment-strengthen",
            "装备强化",
            "cd-equipment-strengthen",
            List.of("装备属性", "装备强化")
    );

    private static boolean active;
    private static State state = State.IDLE;
    private static int delayTicks;
    private static int timeoutTicks;
    private static int pageIndex;
    private static int pathIndex;
    private static DumpTarget activeTarget = ENHANCE_HELP;
    private static Path outputPath;
    private static final List<String> outputLines = new ArrayList<>();
    private static final Set<String> seenPages = new HashSet<>();

    private CdHelpDumpController() {
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal(COMMAND)
                .executes(command -> {
                    start(command.getSource().getClient(), ENHANCE_HELP);
                    return 1;
                })
                .then(ClientCommands.literal("enhance")
                        .executes(command -> {
                            start(command.getSource().getClient(), ENHANCE_HELP);
                            return 1;
                        }))
                .then(ClientCommands.literal("equip")
                        .executes(command -> {
                            start(command.getSource().getClient(), EQUIPMENT_STRENGTHEN);
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(command -> {
                            stop(command.getSource().getClient(), "已停止采集。");
                            return 1;
                        })));
    }

    static void tick(Minecraft client) {
        if (!active) {
            return;
        }
        if (!isClientReady(client)) {
            stop(client, "客户端状态不可用，采集已停止。");
            return;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        if (timeoutTicks-- <= 0) {
            stop(client, "等待菜单超时，采集已停止。");
            return;
        }

        Screen screen = client.screen;
        switch (state) {
            case OPEN_ROOT -> openRootMenu(client);
            case CLICK_PATH -> clickNextPathItem(client, screen);
            case DUMP_PAGE -> dumpCurrentPage(client, screen);
            case IDLE -> {
            }
        }
    }

    private static void start(Minecraft client, DumpTarget target) {
        if (!isClientReady(client)) {
            sendMessage(client, "现在还不能启动 /cd 菜单采集。");
            return;
        }

        if (client.screen != null) {
            client.player.closeContainer();
            client.setScreen(null);
        }

        active = true;
        state = State.OPEN_ROOT;
        delayTicks = 0;
        timeoutTicks = TIMEOUT_TICKS;
        pageIndex = 0;
        pathIndex = 0;
        activeTarget = target;
        outputPath = null;
        outputLines.clear();
        seenPages.clear();
        outputLines.add("# /cd " + String.join(" / ", target.pathLabels()) + " 物品采集");
        outputLines.add("startedAt=" + LocalDateTime.now());
        outputLines.add("target=" + target.id());
        outputLines.add("");
        sendMessage(client, "开始采集：将打开 /cd -> " + String.join(" -> ", target.pathLabels()) + "，并导出所有菜单物品信息。");
    }

    private static void openRootMenu(Minecraft client) {
        client.getConnection().sendCommand(ROOT_COMMAND);
        state = State.CLICK_PATH;
        delayTicks = ACTION_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
    }

    private static void clickNextPathItem(Minecraft client, Screen screen) {
        if (pathIndex >= activeTarget.pathLabels().size()) {
            state = State.DUMP_PAGE;
            delayTicks = ACTION_DELAY_TICKS;
            timeoutTicks = TIMEOUT_TICKS;
            return;
        }

        String label = activeTarget.pathLabels().get(pathIndex);
        State nextState = pathIndex + 1 >= activeTarget.pathLabels().size() ? State.DUMP_PAGE : State.CLICK_PATH;
        clickMenuItem(client, screen, label, nextState);
    }

    private static void clickMenuItem(Minecraft client, Screen screen, String label, State nextState) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        int slotIndex = findSlotByText(containerScreen, label);
        if (slotIndex < 0) {
            return;
        }

        clickSlot(client, containerScreen, slotIndex);
        pathIndex++;
        state = nextState;
        delayTicks = ACTION_DELAY_TICKS;
        timeoutTicks = TIMEOUT_TICKS;
        sendMessage(client, "已点击菜单项：" + label);
    }

    private static void dumpCurrentPage(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        if (!containsNormalized(screen.getTitle().getString(), activeTarget.titleContains())) {
            return;
        }

        List<Slot> slots = containerScreen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        String pageSignature = buildPageSignature(screen, slots, playerInventoryStart);
        if (!seenPages.add(pageSignature)) {
            finish(client, "检测到页面重复，采集结束。");
            return;
        }

        pageIndex++;
        outputLines.add("## page " + pageIndex);
        outputLines.add("screenTitle=" + safeText(screen.getTitle()));
        outputLines.add("screenClass=" + screen.getClass().getName());
        outputLines.add("menuClass=" + containerScreen.getMenu().getClass().getName());
        outputLines.add("containerId=" + containerScreen.getMenu().containerId);
        outputLines.add("slotCount=" + slots.size());
        outputLines.add("");

        int dumped = 0;
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            dumped++;
            appendStackDump(client, slotIndex, slot, stack);
        }
        outputLines.add("pageItemCount=" + dumped);
        outputLines.add("");

        int nextSlot = findNextPageSlot(containerScreen);
        if (nextSlot >= 0) {
            clickSlot(client, containerScreen, nextSlot);
            delayTicks = ACTION_DELAY_TICKS;
            timeoutTicks = TIMEOUT_TICKS;
            sendMessage(client, "已采集第 " + pageIndex + " 页，继续翻下一页。");
            return;
        }

        finish(client, "没有发现下一页，采集结束。");
    }

    private static void appendStackDump(Minecraft client, int slotIndex, Slot slot, ItemStack stack) {
        Item item = stack.getItem();
        outputLines.add("### slot " + slotIndex);
        outputLines.add("slotIndex=" + slotIndex);
        outputLines.add("slotX=" + slot.x);
        outputLines.add("slotY=" + slot.y);
        outputLines.add("active=" + slot.isActive());
        outputLines.add("itemId=" + BuiltInRegistries.ITEM.getKey(item));
        outputLines.add("itemClass=" + item.getClass().getName());
        outputLines.add("count=" + stack.getCount());
        outputLines.add("hoverName=" + safeText(stack.getHoverName()));
        outputLines.add("styledHoverName=" + safeText(stack.getStyledHoverName()));
        outputLines.add("itemName=" + safeText(stack.getItemName()));
        outputLines.add("customName=" + safeText(stack.getCustomName()));
        outputLines.add("displayName=" + safeText(stack.getDisplayName()));
        outputLines.add("rarity=" + stack.getRarity());
        outputLines.add("foil=" + stack.hasFoil());
        outputLines.add("damage=" + stack.getDamageValue());
        outputLines.add("maxDamage=" + stack.getMaxDamage());
        outputLines.add("componentsPatch=" + stack.getComponentsPatch());
        outputLines.add("componentsSize=" + stack.getComponents().size());
        outputLines.add("components:");
        for (TypedDataComponent<?> component : stack.getComponents()) {
            outputLines.add("  - " + componentId(component) + "=" + encodeComponent(component));
        }
        outputLines.add("tooltip:");
        for (Component line : tooltipLines(client, stack)) {
            outputLines.add("  - " + safeText(line));
        }
        outputLines.add("");
    }

    private static List<Component> tooltipLines(Minecraft client, ItemStack stack) {
        try {
            return stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.ADVANCED);
        } catch (RuntimeException exception) {
            ScreenProbe.LOGGER.warn("Failed to read tooltip for {}", stack, exception);
            return List.of(Component.literal("<tooltip read failed: " + exception.getClass().getSimpleName() + ">"));
        }
    }

    private static String componentId(TypedDataComponent<?> component) {
        return BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()).toString();
    }

    private static String encodeComponent(TypedDataComponent<?> component) {
        try {
            return component.encodeValue(JsonOps.INSTANCE)
                    .map(JsonElement::toString)
                    .result()
                    .orElseGet(component::toString);
        } catch (RuntimeException exception) {
            return component + " <encode failed: " + exception.getClass().getSimpleName() + ">";
        }
    }

    private static int findSlotByText(AbstractContainerScreen<?> screen, String expectedText) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            if (containsNormalized(stack.getHoverName().getString(), expectedText)
                    || tooltipContains(stack, expectedText)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static int findNextPageSlot(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, slots.size() - 36);
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem() || !slot.isActive()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString();
            if (isNextPageText(name) || tooltipContainsNextPage(stack)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean tooltipContains(ItemStack stack, String expectedText) {
        Minecraft client = Minecraft.getInstance();
        for (Component line : tooltipLines(client, stack)) {
            if (containsNormalized(line.getString(), expectedText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tooltipContainsNextPage(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        for (Component line : tooltipLines(client, stack)) {
            if (isNextPageText(line.getString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNextPageText(String text) {
        String normalized = normalize(text);
        return normalized.contains("下一页")
                || normalized.contains("下页")
                || normalized.contains("nextpage")
                || normalized.equals("next")
                || normalized.contains("page>");
    }

    private static String buildPageSignature(Screen screen, List<Slot> slots, int playerInventoryStart) {
        StringBuilder builder = new StringBuilder(screen.getTitle().getString()).append('|');
        for (int slotIndex = 0; slotIndex < playerInventoryStart; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            if (slot == null || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            builder.append(slotIndex)
                    .append(':')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(':')
                    .append(stack.getHoverName().getString())
                    .append(':')
                    .append(stack.getComponentsPatch())
                    .append('|');
        }
        return builder.toString();
    }

    private static void clickSlot(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex) {
        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotIndex,
                0,
                ContainerInput.PICKUP,
                client.player
        );
    }

    private static void finish(Minecraft client, String message) {
        outputPath = writeDump(client);
        String suffix = outputPath == null ? "但文件写入失败。" : "文件：" + outputPath.toAbsolutePath();
        stop(client, message + " 共采集 " + pageIndex + " 页，" + suffix);
    }

    private static Path writeDump(Minecraft client) {
        try {
            Path logDir = client.gameDirectory.toPath().resolve("logs").resolve("screenprobe").resolve("cd-help-dump");
            Files.createDirectories(logDir);
            Path file = logDir.resolve(activeTarget.filePrefix() + "-" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".txt");
            Files.write(file, outputLines);
            return file;
        } catch (IOException exception) {
            ScreenProbe.LOGGER.error("Failed to write /cd help dump", exception);
            return null;
        }
    }

    private static void stop(Minecraft client, String message) {
        active = false;
        state = State.IDLE;
        delayTicks = 0;
        timeoutTicks = 0;
        if (message != null) {
            sendMessage(client, message);
        }
    }

    private static boolean containsNormalized(String text, String expected) {
        return normalize(text).contains(normalize(expected));
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("§.", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String safeText(Component component) {
        return component == null ? "" : component.getString().replace('\n', ' ');
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.getConnection() != null && client.gameMode != null;
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

    private enum State {
        IDLE,
        OPEN_ROOT,
        CLICK_PATH,
        DUMP_PAGE
    }

    private record DumpTarget(String id, String titleContains, String filePrefix, List<String> pathLabels) {
    }
}
