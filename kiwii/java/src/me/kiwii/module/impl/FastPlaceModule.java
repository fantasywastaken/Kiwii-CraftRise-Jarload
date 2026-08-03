package me.kiwii.module.impl;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;
import me.kiwii.util.UnsafeHelper;

public class FastPlaceModule extends Module {

    private static final int TARGET_DELAY = 2;

    private long cps1Offset = -1;
    private long cps2Offset = -1;
    private Object mcInstance;
    private boolean lookupTried;

    public FastPlaceModule() {
        super("FastPlace", "Fixes right-click delay to 2 ticks", Category.PLAYER, 0);
    }

    @Override public void onEnable()  { lookupTried = false; }
    @Override public void onDisable() {}

    @Override
    public void onUpdate() {
        ensureLookup();
        if (mcInstance == null) return;
        Unsafe u = UnsafeHelper.getUnsafe();
        if (u == null) return;
        try {
            if (cps1Offset >= 0 && u.getInt(mcInstance, cps1Offset) > TARGET_DELAY) {
                u.putInt(mcInstance, cps1Offset, TARGET_DELAY);
            }
            if (cps2Offset >= 0 && u.getInt(mcInstance, cps2Offset) > TARGET_DELAY) {
                u.putInt(mcInstance, cps2Offset, TARGET_DELAY);
            }
        } catch (Throwable ignored) {}
    }

    private void ensureLookup() {
        if (lookupTried) return;
        lookupTried = true;
        try {
            mcInstance = MinecraftMapper.getMinecraft();
            Field f1 = MappingUtils.getField("Minecraft.cps1");
            Field f2 = MappingUtils.getField("Minecraft.cps2");
            Unsafe u = UnsafeHelper.getUnsafe();
            if (f1 != null && u != null) cps1Offset = u.objectFieldOffset(f1);
            if (f2 != null && u != null) cps2Offset = u.objectFieldOffset(f2);
            Logger.info("[FastPlace] cps1=" + (cps1Offset >= 0) + " cps2=" + (cps2Offset >= 0));
        } catch (Throwable t) { Logger.warn("[FastPlace] lookup: " + t.getMessage()); }
    }
}
