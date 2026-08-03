package me.kiwii.module.impl;

import java.lang.reflect.Field;

import org.lwjgl.input.Keyboard;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class InvMoveModule extends Module {

    private Object mcInstance;
    private Field  currentScreenField;
    private Field  keyCodeField;
    private Field  pressedField;

    private Object bindForward;
    private Object bindBack;
    private Object bindLeft;
    private Object bindRight;
    private Object bindJump;
    private Object bindSprint;

    private boolean lookupTried;
    private boolean lookupOk;

    public InvMoveModule() {
        super("InvMove", "Move / jump / sprint while inventory or any GUI is open",
              Category.MOVEMENT, 0);
    }

    @Override
    public void onEnable() {
        Logger.info("InvMove enabled");
        lookupTried = false;
        lookupOk = false;
    }

    @Override
    public void onDisable() {
        Logger.info("InvMove disabled");
        setPressed(bindForward, false);
        setPressed(bindBack,    false);
        setPressed(bindLeft,    false);
        setPressed(bindRight,   false);
        setPressed(bindJump,    false);
        setPressed(bindSprint,  false);
    }

    @Override
    public void onUpdate() {
        if (!ensureLookup()) return;
        try {
            Object screen = currentScreenField.get(mcInstance);
            if (screen == null) return;
            if (me.kiwii.util.GuiHelper.isChatOpen()) return;
            syncBinding(bindForward);
            syncBinding(bindBack);
            syncBinding(bindLeft);
            syncBinding(bindRight);
            syncBinding(bindJump);
            syncBinding(bindSprint);
        } catch (Throwable ignored) {}
    }

    private void syncBinding(Object kb) {
        if (kb == null) return;
        try {
            int code = keyCodeField.getInt(kb);
            if (code == 0) return;
            boolean down = Keyboard.isKeyDown(code);
            pressedField.setBoolean(kb, down);
        } catch (Throwable ignored) {}
    }

    private void setPressed(Object kb, boolean v) {
        if (kb == null || pressedField == null) return;
        try { pressedField.setBoolean(kb, v); } catch (Throwable ignored) {}
    }

    private boolean ensureLookup() {
        if (lookupOk) return true;
        boolean firstTry = !lookupTried;
        lookupTried = true;

        mcInstance         = MinecraftMapper.getMinecraft();
        currentScreenField = MappingUtils.getField("Minecraft.currentScreen");
        keyCodeField       = MappingUtils.getField("KeyBinding.keyCode");
        pressedField       = MappingUtils.getField("KeyBinding.pressed");

        if (mcInstance == null || currentScreenField == null
                || keyCodeField == null || pressedField == null) {
            if (firstTry) Logger.warn("InvMove: base mappings missing mc=" + (mcInstance != null)
                    + " screen=" + (currentScreenField != null)
                    + " keyCode=" + (keyCodeField != null)
                    + " pressed=" + (pressedField != null));
            return false;
        }
        currentScreenField.setAccessible(true);
        keyCodeField.setAccessible(true);
        pressedField.setAccessible(true);

        Object gs = findGameSettings();
        if (gs == null) { if (firstTry) Logger.warn("InvMove: GameSettings instance not found on Minecraft"); return false; }

        bindForward = findKeyBind(gs, "GameSettings.keyBindForward", Keyboard.KEY_W);
        bindBack    = findKeyBind(gs, "GameSettings.keyBindBack",    Keyboard.KEY_S);
        bindLeft    = findKeyBind(gs, "GameSettings.keyBindLeft",    Keyboard.KEY_A);
        bindRight   = findKeyBind(gs, "GameSettings.keyBindRight",   Keyboard.KEY_D);
        bindJump    = findKeyBind(gs, "GameSettings.keyBindJump",    Keyboard.KEY_SPACE);
        bindSprint  = findKeyBind(gs, "GameSettings.keyBindSprint",  Keyboard.KEY_LCONTROL);

        Logger.info("InvMove lookup: F=" + (bindForward != null)
                + " B=" + (bindBack   != null)
                + " L=" + (bindLeft   != null)
                + " R=" + (bindRight  != null)
                + " J=" + (bindJump   != null)
                + " Sp=" + (bindSprint != null));

        lookupOk = true;
        return true;
    }

    private Object findGameSettings() {
        try {
            Class<?> gsCls = MappingUtils.get("GameSettings");
            if (gsCls == null) return null;
            for (Class<?> c = mcInstance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != gsCls) continue;
                    f.setAccessible(true);
                    Object gs = f.get(mcInstance);
                    if (gs != null) return gs;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object findKeyBind(Object gs, String mappedName, int fallbackCode) {
        try {
            Field mapped = MappingUtils.getField(mappedName);
            if (mapped != null) {
                mapped.setAccessible(true);
                Object v = mapped.get(gs);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> kbCls = MappingUtils.get("KeyBinding");
            if (kbCls == null) return null;
            for (Class<?> c = gs.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != kbCls) continue;
                    f.setAccessible(true);
                    Object kb = f.get(gs);
                    if (kb == null) continue;
                    try {
                        int kc = keyCodeField.getInt(kb);
                        if (kc == fallbackCode) return kb;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
