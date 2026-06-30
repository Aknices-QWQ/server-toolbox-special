package axelion.screenprobe.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class ScreenProbeInspector {
    private static final int MAX_DEPTH = 3;
    private static final int MAX_PREVIEW_LINES = 3;
    private static final int MAX_CHILD_PREVIEW = 5;
    private static final int MAX_TREE_DEPTH = 3;
    private static final int MAX_TREE_LINES = 24;

    private ScreenProbeInspector() {
    }

    static List<String> inspect(Screen screen) {
        List<String> lines = new ArrayList<>();
        List<EditBox> editBoxes = collectEditBoxes(screen);
        int childCount = countChildren(screen);

        lines.add("screen=" + screen.getClass().getName());
        lines.add("title=" + shorten(screen.getTitle().getString()));
        lines.add("children=" + childCount);
        lines.add("editBoxes=" + editBoxes.size());

        List<String> childTypes = previewChildTypes(screen);
        for (int i = 0; i < childTypes.size(); i++) {
            lines.add("child[" + i + "]=" + childTypes.get(i));
        }

        lines.addAll(describeChildTree(screen));

        for (int i = 0; i < Math.min(editBoxes.size(), MAX_PREVIEW_LINES); i++) {
            EditBox box = editBoxes.get(i);
            lines.add("editBox[" + i + "]=\"" + shorten(box.getValue()) + "\"");
        }

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            Object menu = containerScreen.getMenu();
            lines.add("menu=" + menu.getClass().getName());
            lines.add("slots=" + readSlotCount(menu));
            lines.addAll(describeContainerSlots(containerScreen));
        }

        for (String fieldLine : describeInterestingFields(screen)) {
            lines.add(fieldLine);
        }

        return lines;
    }

    static InputAttemptResult fillFirstEditBox(Screen screen, String text) {
        List<EditBox> editBoxes = collectEditBoxes(screen);

        if (editBoxes.isEmpty()) {
            boolean inserted = invokeInsertText(screen, text);
            if (inserted) {
                return new InputAttemptResult(true, "No EditBox found, but insertText succeeded on "
                        + screen.getClass().getSimpleName() + ".");
            }

            return new InputAttemptResult(false, "No EditBox found on " + screen.getClass().getSimpleName()
                    + ", and insertText fallback did not work.");
        }

        EditBox box = editBoxes.get(0);
        box.setFocused(true);
        box.setValue(text);

        return new InputAttemptResult(true, "Wrote \"" + text + "\" into the first EditBox on "
                + screen.getClass().getSimpleName() + ".");
    }

    static boolean isSellQuantityDialog(Screen screen) {
        if (screen == null) {
            return false;
        }

        if (!"net.minecraft.client.gui.screens.dialog.SimpleDialogScreen".equals(screen.getClass().getName())) {
            return false;
        }

        boolean hasQuantityLabel = false;
        boolean hasConfirmButton = false;
        boolean hasEditBox = false;

        for (AbstractWidget widget : collectWidgets(screen)) {
            String text = widget.getMessage().getString();
            if (text.contains("交易数量")) {
                hasQuantityLabel = true;
            }

            if (widget instanceof EditBox) {
                hasEditBox = true;
            }

            if (widget instanceof Button && "确认".equals(text)) {
                hasConfirmButton = true;
            }
        }

        return hasQuantityLabel && hasEditBox && hasConfirmButton;
    }

    static ButtonClickResult clickButtonByText(Screen screen, String buttonText) {
        for (AbstractWidget widget : collectWidgets(screen)) {
            if (!(widget instanceof Button button) || !button.active || !button.visible) {
                continue;
            }

            if (!buttonText.equals(button.getMessage().getString())) {
                continue;
            }

            button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            return new ButtonClickResult(true, "Clicked button \"" + buttonText + "\".");
        }

        return new ButtonClickResult(false, "Button \"" + buttonText + "\" was not found.");
    }

    static Integer readSellQuantity(Screen screen) {
        if (screen == null) {
            return null;
        }

        for (AbstractWidget widget : collectWidgets(screen)) {
            if (!(widget instanceof EditBox editBox)) {
                continue;
            }

            String value = editBox.getValue();
            if (value == null) {
                continue;
            }

            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private static int countChildren(Screen screen) {
        Object children = invokeNoArg(screen, "children");

        if (!(children instanceof Iterable<?> iterable)) {
            return -1;
        }

        int count = 0;
        for (Object ignored : iterable) {
            count++;
        }

        return count;
    }

    private static List<String> previewChildTypes(Screen screen) {
        Object children = invokeNoArg(screen, "children");
        if (!(children instanceof Iterable<?> iterable)) {
            return List.of();
        }

        List<String> types = new ArrayList<>();
        for (Object child : iterable) {
            if (child == null) {
                continue;
            }

            types.add(child.getClass().getName());
            if (types.size() >= MAX_CHILD_PREVIEW) {
                break;
            }
        }

        return types;
    }

    private static List<String> describeChildTree(Screen screen) {
        List<String> lines = new ArrayList<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        Object children = invokeNoArg(screen, "children");

        if (!(children instanceof Iterable<?> iterable)) {
            return lines;
        }

        int index = 0;
        for (Object child : iterable) {
            appendChildTree(lines, child, Integer.toString(index), 0, visited);
            index++;
            if (lines.size() >= MAX_TREE_LINES) {
                break;
            }
        }

        return lines;
    }

    private static int readSlotCount(Object menu) {
        Object slots = readField(menu, "slots");

        if (slots instanceof List<?> list) {
            return list.size();
        }

        return -1;
    }

    private static List<String> describeContainerSlots(AbstractContainerScreen<?> screen) {
        List<String> lines = new ArrayList<>();
        List<?> rawSlots = screen.getMenu().slots;
        int playerInventoryStart = Math.max(0, rawSlots.size() - 36);

        for (int slotIndex = 0; slotIndex < Math.min(rawSlots.size(), playerInventoryStart); slotIndex++) {
            Object rawSlot = rawSlots.get(slotIndex);
            if (!(rawSlot instanceof net.minecraft.world.inventory.Slot slot) || !slot.hasItem()) {
                continue;
            }

            var stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            lines.add("slot[" + slotIndex + "]=\"" + shorten(stack.getHoverName().getString()) + "\" x" + stack.getCount());
            if (lines.size() >= 18) {
                break;
            }
        }

        return lines;
    }

    private static List<AbstractWidget> collectWidgets(Screen screen) {
        Set<AbstractWidget> widgets = Collections.newSetFromMap(new IdentityHashMap<>());
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

        Object children = invokeNoArg(screen, "children");
        if (children instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scanWidgets(child, widgets, visited, 0);
            }
        }

        return new ArrayList<>(widgets);
    }

    private static List<EditBox> collectEditBoxes(Screen screen) {
        Set<EditBox> editBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

        scan(screen, editBoxes, visited, 0);
        Object children = invokeNoArg(screen, "children");

        if (children instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scan(child, editBoxes, visited, 1);
            }
        }

        return new ArrayList<>(editBoxes);
    }

    private static void scan(Object value, Set<EditBox> editBoxes, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || depth > MAX_DEPTH || visited.containsKey(value)) {
            return;
        }

        visited.put(value, Boolean.TRUE);

        if (value instanceof EditBox editBox) {
            editBoxes.add(editBox);
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scan(child, editBoxes, visited, depth + 1);
            }
            return;
        }

        Object nestedChildren = invokeNoArg(value, "children");
        if (nestedChildren instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scan(child, editBoxes, visited, depth + 1);
            }
        }

        Class<?> type = value.getClass();
        if (!isInterestingType(type)) {
            return;
        }

        for (Field field : getAllFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }

            Object fieldValue = readField(value, field);
            if (fieldValue != null) {
                scan(fieldValue, editBoxes, visited, depth + 1);
            }
        }
    }

    private static void scanWidgets(Object value, Set<AbstractWidget> widgets,
                                    IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || depth > MAX_DEPTH || visited.containsKey(value)) {
            return;
        }

        visited.put(value, Boolean.TRUE);

        if (value instanceof AbstractWidget widget) {
            widgets.add(widget);
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scanWidgets(child, widgets, visited, depth + 1);
            }
            return;
        }

        Object nestedChildren = invokeNoArg(value, "children");
        if (nestedChildren instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                scanWidgets(child, widgets, visited, depth + 1);
            }
        }
    }

    private static boolean isInterestingType(Class<?> type) {
        if (type.isEnum()
                || type.isPrimitive()
                || type.isArray()
                || type.getName().startsWith("java.")
                || type.getName().startsWith("javax.")
                || type.getName().startsWith("sun.")) {
            return false;
        }

        String name = type.getName();
        return name.startsWith("net.minecraft.client.gui")
                || name.startsWith("net.minecraft.client.renderer")
                || Iterable.class.isAssignableFrom(type);
    }

    private static Object invokeNoArg(Object target, String methodName) {
        Class<?> type = target.getClass();

        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                ensureAccessible(method);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static boolean invokeInsertText(Screen screen, String text) {
        Class<?> type = screen.getClass();

        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod("insertText", String.class, boolean.class);
                ensureAccessible(method);
                method.invoke(screen, text, false);
                return true;
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }

        return false;
    }

    private static Object readField(Object target, String fieldName) {
        Class<?> type = target.getClass();

        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                ensureAccessible(field);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static Object readField(Object target, Field field) {
        try {
            ensureAccessible(field);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> describeInterestingFields(Screen screen) {
        List<String> lines = new ArrayList<>();
        int count = 0;

        for (Field field : getAllFields(screen.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }

            Object value = readField(screen, field);
            if (value == null) {
                continue;
            }

            Class<?> fieldType = value.getClass();
            String typeName = fieldType.getName();
            if (!typeName.startsWith("net.minecraft.client.gui")
                    && !(value instanceof Iterable<?>)
                    && !(value instanceof String)
                    && !(value instanceof Component)
                    && !typeName.contains("dialog")
                    && !typeName.contains("widget")) {
                continue;
            }

            lines.add("field[" + field.getName() + "]=" + summarizeValue(value));
            count++;
            if (count >= 8) {
                break;
            }
        }

        return lines;
    }

    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;

        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }

        return fields;
    }

    private static void appendChildTree(List<String> lines, Object child, String path, int depth,
                                        IdentityHashMap<Object, Boolean> visited) {
        if (child == null || depth > MAX_TREE_DEPTH || lines.size() >= MAX_TREE_LINES) {
            return;
        }

        lines.add("node[" + path + "]=" + summarizeValue(child));

        if (visited.containsKey(child)) {
            return;
        }
        visited.put(child, Boolean.TRUE);

        Object nestedChildren = child instanceof Iterable<?> ? child : invokeNoArg(child, "children");
        if (!(nestedChildren instanceof Iterable<?> iterable)) {
            return;
        }

        int index = 0;
        for (Object nestedChild : iterable) {
            appendChildTree(lines, nestedChild, path + "." + index, depth + 1, visited);
            index++;
            if (lines.size() >= MAX_TREE_LINES) {
                break;
            }
        }
    }

    private static String summarizeValue(Object value) {
        if (value instanceof AbstractWidget widget) {
            return widget.getClass().getName() + textSuffix(widget.getMessage());
        }

        if (value instanceof Component component) {
            return "Component(\"" + shorten(component.getString()) + "\")";
        }

        if (value instanceof String string) {
            return "String(\"" + shorten(string) + "\")";
        }

        if (value instanceof Iterable<?>) {
            return value.getClass().getName();
        }

        String className = value.getClass().getName();
        if (className.startsWith("net.minecraft.server.dialog")) {
            return shorten(className + " " + value);
        }

        return className;
    }

    private static String textSuffix(Component component) {
        if (component == null) {
            return "";
        }

        String text = component.getString();
        if (text == null || text.isEmpty()) {
            return "";
        }

        return "(\"" + shorten(text) + "\")";
    }

    private static void ensureAccessible(AccessibleObject object) throws ReflectiveOperationException {
        try {
            object.setAccessible(true);
        } catch (InaccessibleObjectException exception) {
            throw new ReflectiveOperationException(exception);
        }
    }

    private static String shorten(String input) {
        if (input == null || input.isEmpty()) {
            return "<empty>";
        }

        if (input.length() <= 80) {
            return input;
        }

        return input.substring(0, 77) + "...";
    }

    record InputAttemptResult(boolean success, String message) {
    }

    record ButtonClickResult(boolean success, String message) {
    }
}
