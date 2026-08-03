package me.kiwii.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.kiwii.util.MappingUtils;

public final class MappingManager {

    private MappingManager() {}

    public static Class<?> cls(String logicalName) {
        return MappingUtils.get(logicalName);
    }

    public static Field field(String logicalName) {
        Field f = MappingUtils.getField(logicalName);
        if (f != null) f.setAccessible(true);
        return f;
    }

    public static Method method(String logicalName) {
        Method m = MappingUtils.getMethod(logicalName);
        if (m != null) m.setAccessible(true);
        return m;
    }

    public static Object minecraft() {
        return MinecraftMapper.getMinecraft();
    }

    public static Object thePlayer() {
        try {
            Field f = field("Minecraft.thePlayer");
            if (f != null) { Object v = f.get(minecraft()); if (v != null) return v; }
            Method m = method("Minecraft.getThePlayer");
            if (m != null) return m.invoke(minecraft());
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object theWorld() {
        try {
            Method m = method("Minecraft.getTheWorld");
            if (m != null) return m.invoke(minecraft());
            Field f = field("EntityPlayerSP.worldObj");
            if (f != null) { Object p = thePlayer(); if (p != null) return f.get(p); }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object currentScreen() {
        try {
            Field f = field("Minecraft.currentScreen");
            if (f != null) return f.get(minecraft());
        } catch (Throwable ignored) {}
        return null;
    }

    public static io.netty.channel.Channel networkChannel() {
        try { return MinecraftMapper.getNetworkChannel(); }
        catch (Throwable ignored) { return null; }
    }

    public static boolean ready(String logicalName) {
        return MappingUtils.get(logicalName) != null
                || MappingUtils.getField(logicalName) != null
                || MappingUtils.getMethod(logicalName) != null;
    }
}
