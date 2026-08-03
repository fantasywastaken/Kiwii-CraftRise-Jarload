package me.kiwii.ui;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.util.Logger;

import java.lang.reflect.Field;

/**
 * Installs the runtime-generated GuiIngame subclass (see IngameHudFactory)
 * onto Minecraft's mc.ingameGUI field, so our HUD renders inside the game's
 * own render pipeline instead of via a C++ wglSwapBuffers hook.
 *
 * tryInstall() is safe to call repeatedly (checks the "installed" flag and
 * caps at 120 attempts). Call it from a periodic tick loop until installed.
 */
public final class IngameHudHook {

    private static boolean installed;
    private static int     installAttempts;
    private static final int MAX_ATTEMPTS = 120;
    private static final String HOOK_BINARY = "craftrise.ГЩ$Ki";

    private IngameHudHook() {}

    public static void tryInstall() {
        if (installed) return;
        if (installAttempts > MAX_ATTEMPTS) return;
        installAttempts++;

        try {
            Object mc = MinecraftMapper.getMinecraft();
            if (mc == null) return;

            Class<?> ingameClass = MinecraftMapper.get("GuiInGame");
            if (ingameClass == null) return;

            // Find the mc field whose type is GuiInGame (or subtype)
            Field guiField = null;
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (ingameClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    guiField = f;
                    break;
                }
            }
            if (guiField == null) return;

            Object current = guiField.get(mc);
            if (current != null && HOOK_BINARY.equals(current.getClass().getName())) {
                installed = true;
                return;
            }

            Object hooked = IngameHudFactory.wrap(mc, current);
            if (hooked == null) {
                if (installAttempts == 1 || installAttempts % 30 == 0) {
                    Logger.warn("HUD hook FAIL (retry): " + IngameHudFactory.debugInfo);
                }
                return;
            }

            guiField.set(mc, hooked);
            installed = true;
            Logger.info("HUD hook OK: " + IngameHudFactory.debugInfo);
        } catch (Throwable t) {
            if (installAttempts == 1 || installAttempts % 30 == 0) {
                Logger.warn("HUD hook install error: " + t.getMessage());
            }
        }
    }

    public static boolean isInstalled() {
        return installed;
    }
}
