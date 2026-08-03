package me.kiwii.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.input.Keyboard;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class AutoRodModule extends Module {

    private final NumberOption slot;
    private final NumberOption switchDelay;
    private final NumberOption castDelay;

    private int    savedSlot   = -1;
    private long   phaseStart  = 0L;
    private int    phase       = 0;   

    private Object mcInstance;
    private Method mRightClickMouse;
    private Method mSyncPlayItem;
    private Field  fCurrentItem;
    private Field  fInventory;
    private boolean lookupTried;

    public final me.kiwii.setting.BooleanOption returnToOld;
    public final NumberOption returnSlot;

    public AutoRodModule() {
        super("AutoRod", "Switch to rod slot → cast → switch back",
              Category.PLAYER, Keyboard.KEY_X);
        slot         = new NumberOption("Rod Slot",     3.0D, 1.0D, 9.0D,   1.0D, this);
        switchDelay  = new NumberOption("Switch Delay",  0.0D, 0.0D, 500.0D, 5.0D, this);
        castDelay    = new NumberOption("Cast Delay",  275.0D, 0.0D, 500.0D, 5.0D, this);
        returnToOld  = new me.kiwii.setting.BooleanOption("Return To Old Slot", true, this);
        returnSlot   = new NumberOption("Return Slot",   1.0D, 1.0D, 9.0D,   1.0D, this);
        returnSlot.setDependency("Return To Old Slot:false");
        slot.setGroup("Slot");
        switchDelay.setGroup("Timing");
        castDelay.setGroup("Timing");
        returnToOld.setGroup("Return");
        returnSlot.setGroup("Return");
        addOptions(slot, switchDelay, castDelay, returnToOld, returnSlot);
    }

    private int resolveReturnSlot() {
        if (returnToOld.getValue()) return savedSlot;
        return (returnSlot.getValue().intValue() - 1) & 0xF;
    }

    @Override
    public void onEnable() {
        Logger.info("AutoRod: cast sequence begin, slot=" + slot.getValue().intValue());
        ensureLookup();
        savedSlot = readCurrentSlot();
        if (savedSlot < 0) {
            Logger.warn("AutoRod: failed to read current slot — abort");
            phase = 4;
            disable();
            return;
        }
        int target = (slot.getValue().intValue() - 1) & 0xF;
        writeCurrentSlot(target);
        syncSlot();
        phase = 1;
        phaseStart = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {

        if (savedSlot >= 0 && phase < 4) {
            int retSlot = resolveReturnSlot();
            if (retSlot >= 0 && retSlot < 9) {
                writeCurrentSlot(retSlot);
                syncSlot();
            }
        }
        savedSlot = -1;
        phase = 0;
    }

    @Override
    public void onUpdate() {
        if (phase == 4) return;
        long elapsed = System.currentTimeMillis() - phaseStart;
        switch (phase) {
            case 1: 
                if (elapsed >= switchDelay.getValue().longValue()) {
                    rightClickMouse();
                    phase = 2;
                    phaseStart = System.currentTimeMillis();
                }
                break;
            case 2:
                if (elapsed >= castDelay.getValue().longValue()) {
                    int retSlot = resolveReturnSlot();
                    if (retSlot >= 0 && retSlot < 9) {
                        writeCurrentSlot(retSlot);
                        syncSlot();
                    }
                    phase = 3;
                    phaseStart = System.currentTimeMillis();
                }
                break;
            case 3: 
                if (elapsed >= switchDelay.getValue().longValue()) {
                    phase = 4;
                    disable();
                }
                break;
            default:
                break;
        }
    }

    private void ensureLookup() {
        if (mRightClickMouse != null && mSyncPlayItem != null && fCurrentItem != null && fInventory != null) return;
        lookupTried = true;
        try {
            mcInstance       = MinecraftMapper.getMinecraft();
            fInventory       = MappingUtils.getField("EntityPlayer.inventory");
            fCurrentItem     = MappingUtils.getField("InventoryPlayer.currentItem");
            mSyncPlayItem    = MappingUtils.getMethod("PlayerControllerMP.syncCurrentPlayItem");

            mRightClickMouse = MappingUtils.getMethod("Minecraft.rightClickMouse");
            if (fInventory      != null) fInventory.setAccessible(true);
            if (fCurrentItem    != null) fCurrentItem.setAccessible(true);
            if (mSyncPlayItem   != null) mSyncPlayItem.setAccessible(true);
            if (mRightClickMouse!= null) mRightClickMouse.setAccessible(true);
            Logger.info("[AutoRod] lookup: rc=" + (mRightClickMouse != null)
                    + " sync=" + (mSyncPlayItem != null)
                    + " inv=" + (fInventory != null)
                    + " ci=" + (fCurrentItem != null));
        } catch (Throwable t) {
            Logger.warn("AutoRod lookup: " + t.getMessage());
        }
    }

    private Object cachedInvClass;
    private java.lang.reflect.Field cachedCurrentItemField;

    private java.lang.reflect.Field discoverCurrentItemField(Object inv) {
        if (cachedCurrentItemField != null && cachedInvClass == inv.getClass()) return cachedCurrentItemField;
        cachedInvClass = inv.getClass();
        java.lang.reflect.Field best = null;
        int bestScore = -1;
        for (Class<?> c = inv.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                int score = -1;
                try {
                    if (f.getType() == int.class) {
                        int v = f.getInt(inv);
                        if (v >= 0 && v <= 8) score = 100;
                    }
                } catch (Throwable ignored) {}
                if (score > bestScore) { bestScore = score; best = f; }
            }
        }
        cachedCurrentItemField = best;
        return best;
    }

    private int readCurrentSlot() {
        Object player = getThePlayer();
        if (player == null || fInventory == null) return -1;
        try {
            Object inv = fInventory.get(player);
            if (inv == null) return -1;
            java.lang.reflect.Field f = fCurrentItem;
            if (f == null || !f.getDeclaringClass().isInstance(inv)) f = discoverCurrentItemField(inv);
            if (f == null) return -1;
            if (f.getType() == int.class) return f.getInt(inv);
            Object v = f.get(inv);
            if (v == null) return -1;
            if (v instanceof Number) return ((Number) v).intValue();
            for (java.lang.reflect.Method m : v.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                try { Object r = m.invoke(v); if (r instanceof Integer) return (Integer) r; } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private void writeCurrentSlot(int slotIdx) {
        Object player = getThePlayer();
        if (player == null || fInventory == null) return;
        int val = slotIdx & 0xF;
        try {
            Object inv = fInventory.get(player);
            if (inv == null) return;
            for (Class<?> c = inv.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field ff : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(ff.getModifiers())) continue;
                    try {
                        ff.setAccessible(true);
                        if (ff.getType() == int.class) {
                            int cur = ff.getInt(inv);
                            if (cur >= 0 && cur <= 8) ff.setInt(inv, val);
                        } else if (!ff.getType().isPrimitive() && !ff.getType().isArray()) {
                            Object wrap = ff.get(inv);
                            if (wrap == null) continue;
                            Integer curVal = null;
                            for (java.lang.reflect.Method g : wrap.getClass().getMethods()) {
                                if (g.getParameterCount() != 0 || g.getReturnType() != int.class) continue;
                                try { Object r = g.invoke(wrap); if (r instanceof Integer) { curVal = (Integer) r; break; } } catch (Throwable ignored) {}
                            }
                            if (curVal == null || curVal < 0 || curVal > 8) continue;
                            for (java.lang.reflect.Method s : wrap.getClass().getMethods()) {
                                if (s.getParameterCount() != 1) continue;
                                if (s.getParameterTypes()[0] != int.class) continue;
                                if (s.getReturnType() != void.class) continue;
                                try { s.invoke(wrap, val); break; } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private void syncSlot() {
        try {
            if (mSyncPlayItem == null || mcInstance == null) return;
            Field fPlayerController = MappingUtils.getField("Minecraft.playerController");
            if (fPlayerController == null) return;
            fPlayerController.setAccessible(true);
            Object controller = fPlayerController.get(mcInstance);
            if (controller != null) mSyncPlayItem.invoke(controller);
        } catch (Throwable ignored) {}
    }

    private void rightClickMouse() {

        pressAndReleaseRmb();
    }

    static void pressAndReleaseRmb() {
        try {
            java.awt.Robot r = ROBOT != null ? ROBOT : (ROBOT = new java.awt.Robot());

            int mask = java.awt.event.InputEvent.BUTTON3_DOWN_MASK;
            r.mousePress(mask);

            RMB_RELEASE.schedule(() -> {
                try { r.mouseRelease(mask); }
                catch (Throwable ignored) {}
            }, 60, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!loggedFirstFire) {
                loggedFirstFire = true;
                Logger.info("[AutoRod] AWT Robot RMB press fired");
            }
        } catch (java.awt.AWTException e) {
            if (!loggedRobotFail) {
                loggedRobotFail = true;
                Logger.warn("[AutoRod] AWT Robot init failed (headless?): " + e.getMessage());
            }
        } catch (Throwable t) {
            Logger.warn("[AutoRod] pressAndReleaseRmb: " + t.getMessage());
        }
    }

    private static java.awt.Robot ROBOT;
    private static final java.util.concurrent.ScheduledExecutorService RMB_RELEASE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Kiwii-RMB-Release");
                t.setDaemon(true);
                return t;
            });
    private static boolean loggedFirstFire;
    private static boolean loggedRobotFail;

    static void sendUseItemOnAir() { pressAndReleaseRmb(); }

    public static java.lang.reflect.Method fallbackFindRightClickMouse(Object mc) {
        return null;
    }

    private static java.lang.reflect.Method _unused_fallback(Object mc) {
        try {
            java.lang.reflect.Field cps2 = MappingUtils.getField("Minecraft.cps2");
            if (cps2 == null) return null;
            String cps2Name = cps2.getName();
            Class<?> mcClass = mc.getClass();
            byte[] bytes = me.kiwii.mapping.MinecraftMapper.getClassBytes(mcClass);
            if (bytes == null) return null;
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            String bestName = null, bestDesc = null;
            int    bestScore = 0;
            for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;
                if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) continue;
                int score = 0;
                for (org.objectweb.asm.tree.AbstractInsnNode ins = mn.instructions.getFirst(); ins != null; ins = ins.getNext()) {
                    if (ins.getOpcode() != org.objectweb.asm.Opcodes.PUTFIELD) continue;
                    org.objectweb.asm.tree.FieldInsnNode fin = (org.objectweb.asm.tree.FieldInsnNode) ins;
                    if ("I".equals(fin.desc) && fin.name.equals(cps2Name)) score++;
                }
                if (score > bestScore) { bestScore = score; bestName = mn.name; bestDesc = mn.desc; }
            }
            if (bestName == null) return null;
            Class<?> c = mcClass;
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(bestName)) continue;
                    if (!org.objectweb.asm.Type.getMethodDescriptor(m).equals(bestDesc)) continue;
                    m.setAccessible(true);
                    return m;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
