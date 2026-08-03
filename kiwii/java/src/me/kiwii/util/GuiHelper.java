package me.kiwii.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import me.kiwii.mapping.MinecraftMapper;

public final class GuiHelper {

    private GuiHelper() {}

    private static volatile Field cachedScreenField;
    private static volatile boolean screenFieldTried;

    public static Object getCurrentScreen() {
        try {
            Object mc = MinecraftMapper.getMinecraft();
            if (mc == null) return null;
            if (cachedScreenField == null && !screenFieldTried) {
                screenFieldTried = true;
                Field f = MappingUtils.getField("Minecraft.currentScreen");
                if (f != null) { f.setAccessible(true); cachedScreenField = f; }
            }
            if (cachedScreenField == null) return null;
            return cachedScreenField.get(mc);
        } catch (Throwable ignored) { return null; }
    }

    public static boolean isChatOpen() {
        Object screen = getCurrentScreen();
        if (screen == null) return false;
        try {
            for (Constructor<?> c : screen.getClass().getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isAnyScreenOpen() {
        return getCurrentScreen() != null;
    }

    private static volatile Field   cachedGsField;
    private static volatile Field   cachedDebugField;
    private static volatile Field[] gsBoolFields;
    private static volatile boolean[] gsBoolSnapshot;
    private static volatile boolean gsLookupTried;

    public static boolean isDebugScreenShown() {
        try {
            Object gs = getGameSettings();
            if (gs == null) return false;
            if (cachedDebugField != null) return cachedDebugField.getBoolean(gs);
            trainDebugField(gs);
            return cachedDebugField != null && cachedDebugField.getBoolean(gs);
        } catch (Throwable ignored) { return false; }
    }

    private static Object getGameSettings() throws IllegalAccessException {
        Object mc = MinecraftMapper.getMinecraft();
        if (mc == null) return null;
        if (cachedGsField == null && !gsLookupTried) {
            gsLookupTried = true;
            Class<?> gsCls = MappingUtils.get("GameSettings");
            if (gsCls == null) return null;
            for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != gsCls) continue;
                    f.setAccessible(true);
                    cachedGsField = f;
                    break;
                }
                if (cachedGsField != null) break;
            }
        }
        if (cachedGsField == null) return null;
        return cachedGsField.get(mc);
    }

    private static void trainDebugField(Object gs) throws IllegalAccessException {
        if (gsBoolFields == null) {
            List<Field> bools = new ArrayList<Field>();
            for (Class<?> c = gs.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != boolean.class) continue;
                    f.setAccessible(true);
                    bools.add(f);
                }
            }
            gsBoolFields = bools.toArray(new Field[0]);
            gsBoolSnapshot = new boolean[gsBoolFields.length];
            for (int i = 0; i < gsBoolFields.length; i++) {
                gsBoolSnapshot[i] = gsBoolFields[i].getBoolean(gs);
            }
            return;
        }
        boolean f3Down = Keyboard.isKeyDown(Keyboard.KEY_F3);
        int changedIdx = -1;
        int changedCount = 0;
        for (int i = 0; i < gsBoolFields.length; i++) {
            boolean cur = gsBoolFields[i].getBoolean(gs);
            if (cur != gsBoolSnapshot[i]) {
                changedCount++;
                changedIdx = i;
                gsBoolSnapshot[i] = cur;
            }
        }
        if (f3Down && changedCount == 1) {
            cachedDebugField = gsBoolFields[changedIdx];
            Logger.info("GuiHelper: debug-info field discovered = " + cachedDebugField.getDeclaringClass().getSimpleName()
                    + "." + cachedDebugField.getName());
        }
    }
}
