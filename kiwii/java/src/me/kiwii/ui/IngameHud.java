package me.kiwii.ui;

import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import me.kiwii.Kiwii;
import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.module.impl.TabGuiModule;
import me.kiwii.notification.NotificationManager;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.setting.OptionBase;
import me.kiwii.setting.StringOption;
import me.kiwii.util.FontUtil;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;
import me.kiwii.util.RoundedUtil;
import me.kiwii.util.ScissorUtil;

public final class IngameHud {

    private static List<Module> sortedModules = new ArrayList<Module>();
    private static long lastSortTime = 0;

    private static volatile boolean firstRenderLogged;
    private static volatile boolean firstDrawLogged;
    private static volatile boolean mappingMissingLogged;
    private static volatile long lastWarnTime;
    private static volatile Method drawStringMethod;

    private static final float TAB_WIDTH = 75f;
    private static final float TAB_HEIGHT = 14f;
    private static final float TAB_X = 4f;
    private static final float TITLE_Y = 4f;
    private static final float TEXT_SCALE = 0.65f;

    private static float tabAlpha = 1.0f;
    private static volatile long lastHudActivityMs = System.currentTimeMillis();
    private static volatile float currentHudFade = 1.0f;
    private static volatile boolean wasIdle = false;
    private static final int[] IDLE_KEYS = {
            org.lwjgl.input.Keyboard.KEY_UP,
            org.lwjgl.input.Keyboard.KEY_DOWN,
            org.lwjgl.input.Keyboard.KEY_LEFT,
            org.lwjgl.input.Keyboard.KEY_RIGHT
    };
    private static final boolean[] prevIdleKeyState = new boolean[IDLE_KEYS.length];

    private static void updateHudFade() {
        long now = System.currentTimeMillis();
        boolean chatOpen = false;
        try { chatOpen = me.kiwii.util.GuiHelper.isChatOpen(); } catch (Throwable ignored) {}
        try {
            if (org.lwjgl.input.Keyboard.isCreated()) {
                for (int i = 0; i < IDLE_KEYS.length; i++) {
                    boolean down = org.lwjgl.input.Keyboard.isKeyDown(IDLE_KEYS[i]);
                    if (chatOpen) { prevIdleKeyState[i] = down; continue; }
                    if (down != prevIdleKeyState[i]) { lastHudActivityMs = now; prevIdleKeyState[i] = down; }
                }
            }
        } catch (Throwable ignored) {}
        float target = 1.0f;
        boolean isIdleNow = false;
        me.kiwii.module.impl.HudModule hud = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.HudModule.class);
        if (hud != null && hud.idle.getValue()) {
            long thresholdMs = (long) (hud.idleTimer.getValue() * 1000.0);
            if (now - lastHudActivityMs >= thresholdMs) {
                target = (float) (hud.idleOpacity.getValue() / 255.0);
                isIdleNow = true;
            }
        }
        if (isIdleNow != wasIdle) {
            wasIdle = isIdleNow;
            try {
                if (isIdleNow) me.kiwii.notification.NotificationManager.postWarning("HUD", "Idle");
                else           me.kiwii.notification.NotificationManager.postInfo("HUD", "Active");
            } catch (Throwable ignored) {}
        }
        currentHudFade += (target - currentHudFade) * 0.12f;
        if (Math.abs(target - currentHudFade) < 0.002f) currentHudFade = target;
        tabAlpha = currentHudFade;
    }

    private static final Color BG = new Color(12, 12, 15, 235);
    private static final Color BG_SEL = new Color(45, 175, 100, 230);
    private static final Color BG_MOD = new Color(16, 16, 20, 240);
    private static final Color BG_MOD_SEL = new Color(45, 175, 100, 230);
    private static final Color BG_EDIT = new Color(45, 175, 100, 230);
    private static final Color ACCENT = new Color(75, 220, 130);
    private static final Color TEXT = new Color(230, 230, 235);
    private static final Color TEXT_ON = new Color(255, 255, 255);
    private static final Color TEXT_OFF = new Color(150, 150, 160);
    private static final Color VALUE_NUM = new Color(255, 255, 255);
    private static final Color VALUE_STR = new Color(255, 255, 255);
    private static final Color VALUE_BIND = new Color(255, 255, 255);

    private IngameHud() {}

    public static void renderFrame() {
        render(null, 0.0f);
    }

    public static void render(Object scaledResolution, float partialTicks) {
        try {
            if (!firstRenderLogged) {
                firstRenderLogged = true;
                Logger.info("IngameHud renderFrame reached");
                markHudStatus("JAVA_RENDER_FIRST_CALL");
            }

            if (me.kiwii.util.GuiHelper.isDebugScreenShown()) return;

            updateHudFade();
            findActiveRenderBuffers();
            snapshotActiveRenderMatrices();

            Object fontRenderer = MinecraftMapper.getFontRenderer();
            Method drawString = getDrawStringMethod();
            if (fontRenderer == null || drawString == null) {
                if (!mappingMissingLogged) {
                    mappingMissingLogged = true;
                    markHudStatus("JAVA_RENDER_MAPPING_MISSING");
                }
                warnThrottled("IngameHud: FontRenderer mapping is not ready");
                return;
            }

            pushHudState();
            try {
                int sw = ScissorUtil.getScaledWidth();
                me.kiwii.module.impl.HudModule hud = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.HudModule.class);
                boolean hudOn         = hud == null || hud.isEnabled();
                boolean showLogo      = hudOn && (hud == null || hud.showLogo.getValue());
                boolean showArrayList = hudOn && (hud == null || hud.showArrayList.getValue());
                String  listStyle     = hud == null ? "Bar" : hud.arrayListStyle.getValue();

                if (hudOn) renderTitle(showLogo);
                renderTabGui();
                if (showArrayList) renderArrayList(sw, listStyle);
                int sh = ScissorUtil.getScaledHeight();
                renderChestEsp3D(sw, sh);
                HudFrame frame = readHudFrame(sw, sh);
                if (frame != null) {
                    renderPlayerEsp3D(frame);
                    renderTracers3D(frame);
                    renderNameTags(frame);
                }

                try { NotificationManager.render(); } catch (Throwable ignored) {}

                if (!firstDrawLogged) {
                    firstDrawLogged = true;
                    Logger.info("IngameHud draw completed");
                    markHudStatus("JAVA_RENDER_DRAW_OK");
                }
            } finally {
                popHudState();
            }
        } catch (Throwable t) {
            markHudStatus("JAVA_RENDER_EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            warnThrottled("IngameHud render error: " + t.getMessage());
        }
    }

    private static void renderTitle(boolean showLogo) {
        float scale = 1.2f;
        float x = TAB_X + 3;
        float y = TITLE_Y + 2;
        float logoRadius = 6.5f;
        if (showLogo) {
            drawKiwiLogo(x + logoRadius, y + FontUtil.getFontHeight() * scale * 0.5f, logoRadius, tabAlpha);
            x += logoRadius * 2 + 4;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        FontUtil.drawStringWithShadow("Kiwii", 0, 0, fadeInt(ACCENT.getRGB(), tabAlpha));
        GL11.glPopMatrix();
    }


    private static void drawKiwiLogo(float cx, float cy, float radius, float alpha) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        fillCircle(cx, cy, radius, 0.35f, 0.28f, 0.16f, alpha);
        fillCircle(cx, cy, radius * 0.92f, 0.55f, 0.72f, 0.28f, alpha);
        fillCircle(cx, cy, radius * 0.62f, 0.78f, 0.90f, 0.55f, alpha);
        fillCircle(cx, cy, radius * 0.25f, 0.96f, 0.98f, 0.90f, alpha);

        int seeds = 8;
        float seedR = radius * 0.55f;
        float seedSize = radius * 0.10f;
        for (int i = 0; i < seeds; i++) {
            double a = (Math.PI * 2 * i / seeds) - Math.PI / 2;
            float sx = cx + (float) (Math.cos(a) * seedR);
            float sy = cy + (float) (Math.sin(a) * seedR);
            fillCircle(sx, sy, seedSize, 0.10f, 0.10f, 0.10f, alpha);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glPopAttrib();
    }

    private static void fillCircle(float cx, float cy, float radius, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        int segments = Math.max(12, (int) (radius * 3));
        for (int i = 0; i <= segments; i++) {
            double t = (Math.PI * 2 * i) / segments;
            GL11.glVertex2f(cx + (float) (Math.cos(t) * radius), cy + (float) (Math.sin(t) * radius));
        }
        GL11.glEnd();
    }



    private static Color fade(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, (int)(c.getAlpha() * alpha))));
    }

    private static int fadeInt(int argb, float alpha) {
        int a = (argb >>> 24) & 0xFF;
        a = Math.max(0, Math.min(255, (int)(a * alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static void renderTabGui() {
        TabGuiModule tabGui = Kiwii.getInstance().getModuleManager().getModule(TabGuiModule.class);
        if (tabGui == null || !tabGui.isEnabled()) return;

        Category[] categories = Category.values();
        float titleH = FontUtil.getFontHeight() * 1.2f + 8;
        float startY = TITLE_Y + titleH;

        int sel = tabGui.getSelectedCategory();
        TabGuiModule.State guiState = tabGui.getState();
        boolean showModules = guiState == TabGuiModule.State.MODULE || guiState == TabGuiModule.State.SETTINGS;

        float panelH = categories.length * TAB_HEIGHT + 2;
        RoundedUtil.roundedRect(TAB_X, startY, TAB_WIDTH, panelH, 2, fade(BG, tabAlpha));

        for (int i = 0; i < categories.length; i++) {
            float y = startY + 1 + i * TAB_HEIGHT;

            if (i == sel) {
                RoundedUtil.roundedRect(TAB_X + 1, y, TAB_WIDTH - 2, TAB_HEIGHT, 2, fade(BG_SEL, tabAlpha));
            }

            int color = (i == sel) ? fadeInt(0xFFFFFFFF, tabAlpha) : fade(TEXT, tabAlpha).getRGB();
            float textY = y + (TAB_HEIGHT - FontUtil.getFontHeight() * TEXT_SCALE) / 2f - 1f;

            GL11.glPushMatrix();
            GL11.glTranslatef(TAB_X + 5, textY, 0);
            GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
            FontUtil.drawString(categories[i].getDisplayName(), 0, 0, color, false);
            GL11.glPopMatrix();

            if (i == sel && showModules) {
                GL11.glPushMatrix();
                GL11.glTranslatef(TAB_X + TAB_WIDTH - 12, textY, 0);
                GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
                FontUtil.drawString(">", 0, 0, fadeInt(0xFFFFFFFF, tabAlpha), false);
                GL11.glPopMatrix();
            }
        }

        if (showModules && sel >= 0 && sel < categories.length) {
            float modAnchorY = startY + sel * TAB_HEIGHT;
            renderModulePanel(categories[sel], tabGui.getSelectedModule(), modAnchorY, guiState);

            if (guiState == TabGuiModule.State.SETTINGS) {
                renderSettingsPanel(tabGui, modAnchorY);
            }
        }
    }

    private static void renderModulePanel(Category category, int selMod, float anchorY,
                                          TabGuiModule.State guiState) {
        List<Module> mods = TabGuiModule.getModulesForCategory(category);
        if (mods.isEmpty()) return;

        float modX = TAB_X + TAB_WIDTH + 2;
        float modW = 80f;
        float panelH = mods.size() * TAB_HEIGHT + 2;

        RoundedUtil.roundedRect(modX, anchorY, modW, panelH, 2, fade(BG_MOD, tabAlpha));

        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            float y = anchorY + 1 + i * TAB_HEIGHT;

            if (i == selMod) {
                RoundedUtil.roundedRect(modX + 1, y, modW - 2, TAB_HEIGHT, 2, fade(BG_MOD_SEL, tabAlpha));

                if (guiState == TabGuiModule.State.SETTINGS) {
                    float arrowY = y + (TAB_HEIGHT - FontUtil.getFontHeight() * TEXT_SCALE) / 2f - 1f;
                    GL11.glPushMatrix();
                    GL11.glTranslatef(modX + modW - 12, arrowY, 0);
                    GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
                    FontUtil.drawString(">", 0, 0, fadeInt(0xFFFFFFFF, tabAlpha), false);
                    GL11.glPopMatrix();
                }
            }

            int color;
            if (m.isEnabled())      color = fade(TEXT_ON, tabAlpha).getRGB();
            else if (i == selMod)   color = fadeInt(0xFFEEEEEE, tabAlpha);
            else                    color = fade(TEXT_OFF, tabAlpha).getRGB();
            float textY = y + (TAB_HEIGHT - FontUtil.getFontHeight() * TEXT_SCALE) / 2f - 1f;

            GL11.glPushMatrix();
            GL11.glTranslatef(modX + 6, textY, 0);
            GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
            FontUtil.drawString(m.getDisplayName(), 0, 0, color, false);
            GL11.glPopMatrix();
        }
    }

    private static void renderSettingsPanel(TabGuiModule tabGui, float modAnchorY) {
        Module mod = tabGui.getSelectedModuleForRender();
        if (mod == null) return;

        List<OptionBase<?>> options = mod.getVisibleOptions();
        int totalEntries = options.size() + 1;
        int selOpt = tabGui.getSelectedOption();
        boolean isEditing = tabGui.isEditing();
        boolean waitBind = tabGui.isWaitingForKeybind();

        float settX = TAB_X + TAB_WIDTH + 2 + 80f + 2;
        float settW = computeSettingsWidth(mod, options);
        float settY = modAnchorY + tabGui.getSelectedModule() * TAB_HEIGHT;
        float panelH = totalEntries * TAB_HEIGHT + 2;

        RoundedUtil.roundedRect(settX, settY, settW, panelH, 2, fade(BG_MOD, tabAlpha));

        for (int i = 0; i < options.size(); i++) {
            OptionBase<?> opt = options.get(i);
            float y = settY + 1 + i * TAB_HEIGHT;
            boolean selected = (i == selOpt);
            boolean focused = selected && isEditing;

            if (selected) {
                Color hlColor = focused ? BG_EDIT : BG_MOD_SEL;
                RoundedUtil.roundedRect(settX + 1, y, settW - 2, TAB_HEIGHT, 2, fade(hlColor, tabAlpha));
            }

            float textY = y + (TAB_HEIGHT - FontUtil.getFontHeight() * TEXT_SCALE) / 2f - 1f;
            String label = opt.getName();
            String value;
            int valueColor;

            if (opt instanceof BooleanOption) {
                boolean on = ((BooleanOption) opt).getValue();
                value = on ? "ON" : "OFF";
                valueColor = on ? fadeInt(0xFF74DE80, tabAlpha) : fadeInt(0xFFFF6060, tabAlpha);
            } else if (opt instanceof NumberOption) {
                value = formatNumber((NumberOption) opt);
                if (focused) {
                    value = "< " + value + " >";
                }
                valueColor = fade(VALUE_NUM, tabAlpha).getRGB();
            } else if (opt instanceof StringOption) {
                value = String.valueOf(opt.getValue());
                if (focused) {
                    value = "< " + value + " >";
                }
                valueColor = fade(VALUE_STR, tabAlpha).getRGB();
            } else {
                value = String.valueOf(opt.getValue());
                valueColor = fade(TEXT, tabAlpha).getRGB();
            }

            GL11.glPushMatrix();
            GL11.glTranslatef(settX + 5, textY, 0);
            GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
            int labelColor = selected ? fadeInt(0xFFFFFFFF, tabAlpha) : fade(TEXT, tabAlpha).getRGB();
            FontUtil.drawString(label, 0, 0, labelColor, false);
            GL11.glPopMatrix();

            float valueX = settX + settW - FontUtil.getStringWidth(value) * TEXT_SCALE - 5;
            GL11.glPushMatrix();
            GL11.glTranslatef(valueX, textY, 0);
            GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
            FontUtil.drawString(value, 0, 0, valueColor, false);
            GL11.glPopMatrix();
        }

        float bindY = settY + 1 + options.size() * TAB_HEIGHT;
        if (selOpt >= options.size()) {
            RoundedUtil.roundedRect(settX + 1, bindY, settW - 2, TAB_HEIGHT, 2, fade(BG_MOD_SEL, tabAlpha));
        }

        float textY = bindY + (TAB_HEIGHT - FontUtil.getFontHeight() * TEXT_SCALE) / 2f - 1f;
        String bindValue;
        if (waitBind) {
            bindValue = "...";
        } else {
            int kb = mod.getKeyBind();
            bindValue = kb > 0 ? Keyboard.getKeyName(kb) : "NONE";
        }

        GL11.glPushMatrix();
        GL11.glTranslatef(settX + 5, textY, 0);
        GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
        int bindLabelColor = (selOpt >= options.size())
                ? fadeInt(0xFFFFFFFF, tabAlpha)
                : fade(TEXT, tabAlpha).getRGB();
        FontUtil.drawString("Bind", 0, 0, bindLabelColor, false);
        GL11.glPopMatrix();

        float bindValX = settX + settW - FontUtil.getStringWidth(bindValue) * TEXT_SCALE - 5;
        int bindColor = waitBind ? fade(TEXT_ON, tabAlpha).getRGB() : fade(VALUE_BIND, tabAlpha).getRGB();
        GL11.glPushMatrix();
        GL11.glTranslatef(bindValX, textY, 0);
        GL11.glScalef(TEXT_SCALE, TEXT_SCALE, 1);
        FontUtil.drawString(bindValue, 0, 0, bindColor, false);
        GL11.glPopMatrix();
    }

    private static float computeSettingsWidth(Module mod, List<OptionBase<?>> options) {
        float maxW = FontUtil.getStringWidth("Bind  NONE") * TEXT_SCALE;
        for (OptionBase<?> opt : options) {
            String label = opt.getName();
            String value;
            if (opt instanceof BooleanOption) {
                value = "OFF";
            } else if (opt instanceof NumberOption) {
                value = "< " + formatNumber((NumberOption) opt) + " >";
            } else if (opt instanceof StringOption) {
                StringOption so = (StringOption) opt;
                value = "";
                for (String m : so.getModes()) {
                    if (m.length() > value.length()) value = m;
                }
                value = "< " + value + " >";
            } else {
                value = String.valueOf(opt.getValue());
            }
            float w = (FontUtil.getStringWidth(label) + FontUtil.getStringWidth("  " + value)) * TEXT_SCALE;
            if (w > maxW) maxW = w;
        }
        return maxW + 16;
    }

    private static String formatNumber(NumberOption opt) {
        double val = opt.getValue();
        double inc = opt.getIncrement();
        if (inc >= 1.0) return String.valueOf((int) val);
        if (inc >= 0.1) return String.format("%.1f", val);
        return String.format("%.2f", val);
    }

    private static void renderArrayList(int sw, String style) {
        long now = System.currentTimeMillis();

        me.kiwii.module.impl.HudModule hud = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.HudModule.class);
        final boolean showSuffix = hud != null && hud.showSuffix.getValue();

        if (now - lastSortTime > 100 || sortedModules.isEmpty()) {
            List<Module> all = Kiwii.getInstance().getModuleManager().getModules();
            sortedModules = new ArrayList<Module>();
            for (Module m : all) {
                if (!m.isEnabled()) continue;
                if ("TabGUI".equals(m.getName())) continue;
                if ("HUD".equals(m.getName())) continue;
                sortedModules.add(m);
            }
            sortedModules.sort(new java.util.Comparator<Module>() {
                public int compare(Module a, Module b) {
                    return Float.compare(entryWidth(b, showSuffix), entryWidth(a, showSuffix));
                }
            });
            lastSortTime = now;
        }

        if (sortedModules.isEmpty()) return;

        float textScale = 0.75f;
        float rowHeight = 13f;
        float rowGap = 1f;
        float margin = 2f;
        float currentY = 4f;

        boolean simple = "Simple".equalsIgnoreCase(style);
        boolean accent = "Accent".equalsIgnoreCase(style);
        boolean bar    = !simple && !accent;
        int suffixColor = 0xFFB0B0B0;

        for (int i = 0; i < sortedModules.size(); i++) {
            Module mod = sortedModules.get(i);
            String name = mod.getDisplayName();
            String suffix = showSuffix ? mod.getSuffix() : null;
            float nameW   = FontUtil.getStringWidth(name);
            float suffixW = suffix != null ? FontUtil.getStringWidth(" " + suffix) : 0;
            float textWidth = (nameW + suffixW) * textScale;
            float rowWidth = textWidth + 8f;
            float xPos = sw - rowWidth - margin;

            if (!simple) {
                RoundedUtil.rect(xPos, currentY, rowWidth, rowHeight, fade(new Color(12, 12, 18, 190), tabAlpha));
            }
            if (bar) {
                RoundedUtil.rect(sw - margin - 1.5f, currentY, 1.5f, rowHeight, fade(ACCENT, tabAlpha));
            }

            int nameColor = accent ? ACCENT.getRGB() : 0xFFFFFFFF;
            float textY = currentY + (rowHeight - FontUtil.getFontHeight() * textScale) / 2f - 1f;
            GL11.glPushMatrix();
            GL11.glTranslatef(xPos + 4, textY, 0);
            GL11.glScalef(textScale, textScale, 1);
            FontUtil.drawStringWithShadow(name, 0, 0, fadeInt(nameColor, tabAlpha));
            if (suffix != null) {
                FontUtil.drawStringWithShadow(" " + suffix, nameW, 0, fadeInt(suffixColor, tabAlpha));
            }
            GL11.glPopMatrix();

            currentY += rowHeight + rowGap;
        }
    }

    private static float entryWidth(Module m, boolean showSuffix) {
        float w = FontUtil.getStringWidth(m.getDisplayName());
        if (showSuffix) {
            String s = m.getSuffix();
            if (s != null) w += FontUtil.getStringWidth(" " + s);
        }
        return w;
    }

    private static long last3DDiagMs;
    private static void diag3D(String s) {
        long now = System.currentTimeMillis();
        if (now - last3DDiagMs > 2000L) { last3DDiagMs = now; Logger.info("[ChestESP-3D] " + s); }
    }

    private static volatile java.lang.reflect.Field cachedMcGameSettingsField;
    private static volatile java.lang.reflect.Field cachedThirdPersonField;
    private static volatile java.lang.reflect.Field[] gsIntFields;
    private static volatile int[] gsIntSnapshot;
    private static volatile long lastGsDump;

    private static int readThirdPersonView() {
        try {
            Object mc = MinecraftMapper.getMinecraft();
            if (mc == null) return 0;
            Class<?> gsCls = MappingUtils.get("GameSettings");
            if (gsCls == null) return 0;
            if (cachedMcGameSettingsField == null) {
                for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == gsCls) { f.setAccessible(true); cachedMcGameSettingsField = f; break; }
                }
                if (cachedMcGameSettingsField == null) return 0;
            }
            Object gs = cachedMcGameSettingsField.get(mc);
            if (gs == null) return 0;

            if (gsIntFields == null) {
                java.util.ArrayList<java.lang.reflect.Field> ints = new java.util.ArrayList<java.lang.reflect.Field>();
                for (java.lang.reflect.Field f : gs.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != int.class) continue;
                    f.setAccessible(true);
                    ints.add(f);
                }
                gsIntFields = ints.toArray(new java.lang.reflect.Field[0]);
                gsIntSnapshot = new int[gsIntFields.length];
                for (int i = 0; i < gsIntFields.length; i++) gsIntSnapshot[i] = gsIntFields[i].getInt(gs);
                Logger.info("[ChestESP-3D] gs int fields discovered n=" + gsIntFields.length);
            }

            if (cachedThirdPersonField == null) {
                for (int i = 0; i < gsIntFields.length; i++) {
                    int now = gsIntFields[i].getInt(gs);
                    int prev = gsIntSnapshot[i];
                    if (now != prev && now >= 0 && now <= 2 && prev >= 0 && prev <= 2) {
                        cachedThirdPersonField = gsIntFields[i];
                        Logger.info("[ChestESP-3D] detected thirdPersonView at idx=" + i + " (prev=" + prev + " -> now=" + now + ")");
                    }
                    gsIntSnapshot[i] = now;
                }
                long now = System.currentTimeMillis();
                if (now - lastGsDump > 3000L) {
                    lastGsDump = now;
                    StringBuilder sb = new StringBuilder("[ChestESP-3D] gs ints in[0..2]: ");
                    for (int i = 0; i < gsIntFields.length; i++) {
                        int v = gsIntFields[i].getInt(gs);
                        if (v >= 0 && v <= 2) sb.append("[").append(i).append("]=").append(v).append(" ");
                    }
                    Logger.info(sb.toString());
                }
                return 0;
            }

            int v = cachedThirdPersonField.getInt(gs);
            if (v < 0 || v > 2) return 0;
            return v;
        } catch (Throwable ignored) { return 0; }
    }

    private static volatile FovSrc cachedFovSrc;
    private static volatile FovSrc[] fovFieldPool;
    private static volatile float[] fovSnapshot;

    private static final class FovSrc {
        final Object owner;
        final java.lang.reflect.Field field;
        final String label;
        FovSrc(Object o, java.lang.reflect.Field f, String l) { owner = o; field = f; label = l; }
    }

    private static volatile java.nio.FloatBuffer arModelviewBuf;
    private static volatile java.nio.FloatBuffer arProjectionBuf;
    private static volatile java.nio.IntBuffer   arViewportBuf;
    private static volatile long lastArLookupMs;
    private static volatile boolean arLoggedOk;

    private static final float[] snapMv   = new float[16];
    private static final float[] snapProj = new float[16];
    private static final int[]   snapVp   = new int[4];
    private static volatile boolean snapValid;
    private static volatile long lastTagDiagMs;

    private static void findActiveRenderBuffers() {
        if (arModelviewBuf != null && arProjectionBuf != null && arViewportBuf != null) return;
        long now = System.currentTimeMillis();
        if (now - lastArLookupMs < 1000L) return;
        lastArLookupMs = now;
        try {
            java.util.List<Class<?>> classes = MinecraftMapper.getClasses();
            if (classes == null) return;
            for (Class<?> c : classes) {
                if (c == null) continue;
                String pkg = c.getName();
                if (!pkg.startsWith("craftrise") && !pkg.startsWith("crsecond") && !pkg.startsWith("net.minecraft")) continue;
                java.nio.FloatBuffer foundMv = null, foundProj = null;
                java.nio.IntBuffer foundVp = null;
                java.lang.reflect.Field[] fields;
                try { fields = c.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                for (java.lang.reflect.Field f : fields) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    try {
                        if (t == java.nio.FloatBuffer.class) {
                            f.setAccessible(true);
                            java.nio.FloatBuffer buf = (java.nio.FloatBuffer) f.get(null);
                            if (buf == null || buf.capacity() != 16) continue;
                            float v15 = buf.get(15);
                            if (Math.abs(v15) < 0.01f && foundProj == null) foundProj = buf;
                            else if (Math.abs(v15 - 1.0f) < 0.01f && foundMv == null) foundMv = buf;
                        } else if (t == java.nio.IntBuffer.class) {
                            f.setAccessible(true);
                            java.nio.IntBuffer buf = (java.nio.IntBuffer) f.get(null);
                            if (buf != null && buf.capacity() == 16 && foundVp == null) foundVp = buf;
                        }
                    } catch (Throwable ignored) {}
                }
                if (foundMv != null && foundProj != null && foundVp != null) {
                    arModelviewBuf = foundMv;
                    arProjectionBuf = foundProj;
                    arViewportBuf = foundVp;
                    if (!arLoggedOk) {
                        arLoggedOk = true;
                        Logger.info("[IngameHud] ActiveRenderInfo buffers found in " + c.getName());
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void snapshotActiveRenderMatrices() {
        if (arModelviewBuf == null || arProjectionBuf == null || arViewportBuf == null) {
            snapValid = false;
            return;
        }
        try {
            arModelviewBuf.rewind();
            for (int i = 0; i < 16; i++) snapMv[i] = arModelviewBuf.get(i);
            arProjectionBuf.rewind();
            for (int i = 0; i < 16; i++) snapProj[i] = arProjectionBuf.get(i);
            arViewportBuf.rewind();
            snapVp[0] = arViewportBuf.get(0);
            snapVp[1] = arViewportBuf.get(1);
            snapVp[2] = arViewportBuf.get(2);
            snapVp[3] = arViewportBuf.get(3);
            snapValid = true;
        } catch (Throwable ignored) { snapValid = false; }
    }

    private static float[] projectWithMatrix(double wx, double wy, double wz, int sw, int sh) {
        if (!snapValid) return null;
        float[] mv = snapMv;
        float[] pj = snapProj;

        double ex = mv[0]*wx + mv[4]*wy + mv[8]*wz  + mv[12];
        double ey = mv[1]*wx + mv[5]*wy + mv[9]*wz  + mv[13];
        double ez = mv[2]*wx + mv[6]*wy + mv[10]*wz + mv[14];
        double ew = mv[3]*wx + mv[7]*wy + mv[11]*wz + mv[15];

        double cx = pj[0]*ex + pj[4]*ey + pj[8]*ez  + pj[12]*ew;
        double cy = pj[1]*ex + pj[5]*ey + pj[9]*ez  + pj[13]*ew;
        double cw = pj[3]*ex + pj[7]*ey + pj[11]*ez + pj[15]*ew;
        if (cw <= 0.05) return null;

        double ndcX = cx / cw;
        double ndcY = cy / cw;

        int vpX = snapVp[0], vpY = snapVp[1], vpW = snapVp[2], vpH = snapVp[3];
        double winX = vpX + (ndcX + 1.0) * 0.5 * vpW;
        double winY = vpY + (ndcY + 1.0) * 0.5 * vpH;

        double sx = winX * (double) sw / (double) vpW;
        double sy = ((double) vpH - winY) * (double) sh / (double) vpH;
        return new float[] { (float) sx, (float) sy };
    }

    private static volatile java.lang.reflect.Field zoomModeField;
    private static volatile long lastZmLookupMs;

    private static boolean readZoomMode() {
        if (zoomModeField == null) {
            long now = System.currentTimeMillis();
            if (now - lastZmLookupMs < 1000L) return false;
            lastZmLookupMs = now;
            try {
                java.util.List<Class<?>> classes = MinecraftMapper.getClasses();
                if (classes == null) return false;
                for (Class<?> c : classes) {
                    if (c == null) continue;
                    String n = c.getName();
                    if (!n.contains("PlayerUsageSnooper") && !n.startsWith("crsecond") && !n.startsWith("craftrise")) continue;
                    java.lang.reflect.Field[] fields;
                    try { fields = c.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                    for (java.lang.reflect.Field f : fields) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != boolean.class) continue;
                        if (!"zoomMode".equals(f.getName())) continue;
                        f.setAccessible(true);
                        zoomModeField = f;
                        Logger.info("[IngameHud] zoomMode field found in " + c.getName());
                        break;
                    }
                    if (zoomModeField != null) break;
                }
            } catch (Throwable ignored) {}
        }
        if (zoomModeField != null) {
            try { return zoomModeField.getBoolean(null); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static java.lang.reflect.Method cachedGetEyeHeightMethod;
    private static volatile boolean eyeHeightLookupTried;

    private static double readEyeHeight(Object player) {
        if (player == null) return 1.62D;
        if (cachedGetEyeHeightMethod == null && !eyeHeightLookupTried) {
            eyeHeightLookupTried = true;
            java.lang.reflect.Method m = MappingUtils.getMethod("Entity.getEyeHeight");
            if (m != null) { try { m.setAccessible(true); } catch (Throwable ignored) {} cachedGetEyeHeightMethod = m; }
        }
        if (cachedGetEyeHeightMethod != null) {
            try {
                Object r = cachedGetEyeHeightMethod.invoke(player);
                if (r instanceof Number) {
                    double v = ((Number) r).doubleValue();
                    if (v > 0.0 && v < 3.0) return v;
                }
            } catch (Throwable ignored) {}
        }
        return 1.62D;
    }

    private static float readActualFov(float fallback) {
        float er = readFovFromEntityRenderer(readPartialTicks());
        if (er > 0) return er;
        try {
            if (fovFieldPool == null) {
                java.util.ArrayList<FovSrc> pool = new java.util.ArrayList<FovSrc>();
                Object mc = MinecraftMapper.getMinecraft();
                if (mc != null && cachedMcGameSettingsField != null) {
                    Object gs = cachedMcGameSettingsField.get(mc);
                    if (gs != null) {
                        for (java.lang.reflect.Field f : gs.getClass().getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType() != float.class) continue;
                            f.setAccessible(true);
                            pool.add(new FovSrc(gs, f, "gs"));
                        }
                    }
                }
                Class<?> cfg = MappingUtils.get("craftrise.Config");
                if (cfg != null) {
                    for (java.lang.reflect.Field f : cfg.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != float.class) continue;
                        f.setAccessible(true);
                        pool.add(new FovSrc(null, f, "cfg"));
                    }
                }
                fovFieldPool = pool.toArray(new FovSrc[0]);
                fovSnapshot = new float[fovFieldPool.length];
                float bestDelta = Float.MAX_VALUE;
                int bestIdx = -1;
                for (int i = 0; i < fovFieldPool.length; i++) {
                    float v = fovFieldPool[i].field.getFloat(fovFieldPool[i].owner);
                    fovSnapshot[i] = v;
                    if (v >= 30f && v <= 130f) {
                        float d = Math.abs(v - 70f);
                        if (d < bestDelta) { bestDelta = d; bestIdx = i; }
                    }
                }
                if (bestIdx >= 0) cachedFovSrc = fovFieldPool[bestIdx];
            }

            for (int i = 0; i < fovFieldPool.length; i++) {
                float now = fovFieldPool[i].field.getFloat(fovFieldPool[i].owner);
                float prev = fovSnapshot[i];
                if (Math.abs(now - prev) > 0.5f && now >= 5f && now <= 130f) {
                    if (cachedFovSrc != fovFieldPool[i]) cachedFovSrc = fovFieldPool[i];
                }
                fovSnapshot[i] = now;
            }

            if (cachedFovSrc == null) return fallback;
            float v = cachedFovSrc.field.getFloat(cachedFovSrc.owner);
            if (v < 5f || v > 130f) return fallback;
            if (readZoomMode()) v *= 0.25f;
            return v;
        } catch (Throwable ignored) { return fallback; }
    }

    private static float readPartialTicks() {
        try {
            java.lang.reflect.Field f = MappingUtils.getField("craftrise.Config.renderPartialTicks");
            if (f == null) return 1f;
            f.setAccessible(true);
            try { return f.getFloat(null); }
            catch (IllegalAccessException | NullPointerException nonStatic) {
                Object cfg = MinecraftMapper.getMinecraft();
                if (cfg != null) return f.getFloat(cfg);
            }
        } catch (Throwable ignored) {}
        return 1f;
    }

    private static volatile Object cachedRenderManager;
    private static volatile java.lang.reflect.Field rmPosX, rmPosY, rmPosZ;
    private static volatile long lastRmLookupMs;
    private static volatile boolean rmLoggedOk;

    private static Object getRenderManager() {
        if (cachedRenderManager != null) return cachedRenderManager;
        long now = System.currentTimeMillis();
        if (now - lastRmLookupMs < 500L) return null;
        lastRmLookupMs = now;
        try {
            Object mc = MinecraftMapper.getMinecraft();
            Class<?> rmCls = MappingUtils.get("RenderManager");
            if (mc == null || rmCls == null) return null;
            for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!rmCls.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(mc);
                    if (v != null) {
                        cachedRenderManager = v;
                        java.lang.reflect.Field fx = MappingUtils.getField("RenderManager.renderPosX");
                        java.lang.reflect.Field fy = MappingUtils.getField("RenderManager.renderPosY");
                        java.lang.reflect.Field fz = MappingUtils.getField("RenderManager.renderPosZ");
                        if (fx != null) { fx.setAccessible(true); rmPosX = fx; }
                        if (fy != null) { fy.setAccessible(true); rmPosY = fy; }
                        if (fz != null) { fz.setAccessible(true); rmPosZ = fz; }
                        if (!rmLoggedOk) {
                            rmLoggedOk = true;
                            Logger.info("[IngameHud] RenderManager cached fieldName=" + f.getName()
                                    + " posFields=" + (rmPosX != null && rmPosY != null && rmPosZ != null ? "OK" : "PARTIAL"));
                        }
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean readCameraFromRenderManager(double[] out3) {
        Object rm = getRenderManager();
        if (rm == null || rmPosX == null || rmPosY == null || rmPosZ == null) return false;
        try {
            out3[0] = rmPosX.getDouble(rm);
            out3[1] = rmPosY.getDouble(rm);
            out3[2] = rmPosZ.getDouble(rm);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static volatile Object cachedEntityRenderer;
    private static volatile java.lang.reflect.Method cachedGetFovModifier;
    private static volatile long lastErLookupMs;
    private static volatile boolean erLoggedOk;

    private static float readFovFromEntityRenderer(float partialTicks) {
        if (cachedGetFovModifier == null || cachedEntityRenderer == null) {
            long now = System.currentTimeMillis();
            if (now - lastErLookupMs >= 500L) {
                lastErLookupMs = now;
                try {
                    Object mc = MinecraftMapper.getMinecraft();
                    Class<?> erCls = MappingUtils.get("EntityRenderer");
                    if (mc != null && erCls != null) {
                        if (cachedEntityRenderer == null) {
                            for (Class<?> c = mc.getClass(); c != null && c != Object.class && cachedEntityRenderer == null; c = c.getSuperclass()) {
                                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                    if (!erCls.isAssignableFrom(f.getType())) continue;
                                    f.setAccessible(true);
                                    Object er = f.get(mc);
                                    if (er != null) { cachedEntityRenderer = er; break; }
                                }
                            }
                        }
                        if (cachedEntityRenderer != null && cachedGetFovModifier == null) {
                            for (Class<?> c = cachedEntityRenderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                                for (java.lang.reflect.Method mm : c.getDeclaredMethods()) {
                                    if (mm.getReturnType() != float.class) continue;
                                    Class<?>[] pt = mm.getParameterTypes();
                                    if (pt.length != 2 || pt[0] != float.class || pt[1] != boolean.class) continue;
                                    mm.setAccessible(true);
                                    try {
                                        Object r = mm.invoke(cachedEntityRenderer, 1.0f, true);
                                        if (r instanceof Number) {
                                            float v = ((Number) r).floatValue();
                                            if (v >= 5f && v <= 179f) { cachedGetFovModifier = mm; break; }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                if (cachedGetFovModifier != null) break;
                            }
                            if (!erLoggedOk) {
                                erLoggedOk = true;
                                Logger.info("[IngameHud] EntityRenderer getFOVModifier=" + (cachedGetFovModifier != null ? cachedGetFovModifier.getName() : "NULL"));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (cachedGetFovModifier != null && cachedEntityRenderer != null) {
            try {
                Object r = cachedGetFovModifier.invoke(cachedEntityRenderer, partialTicks, true);
                if (r instanceof Number) {
                    float v = ((Number) r).floatValue();
                    if (v >= 5f && v <= 179f) return v;
                }
            } catch (Throwable ignored) {}
        }
        return -1f;
    }

    private static void renderChestEsp3D(int sw, int sh) {
        me.kiwii.module.impl.ChestEspModule esp = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.ChestEspModule.class);
        if (esp == null || !esp.isEnabled()) { diag3D("skip: esp null or disabled"); return; }

        java.util.List<me.kiwii.module.impl.ChestEspModule.ChestHit> hits;
        try { hits = esp.collectChests(); } catch (Throwable t) { diag3D("collect threw: " + t); return; }
        if (hits == null || hits.isEmpty()) { diag3D("skip: no hits"); return; }

        HudFrame fr = readHudFrame(sw, sh);
        if (fr == null) { diag3D("skip: frame null"); return; }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(1.5f);

        int[][] edges = new int[][]{
            {0,1},{1,3},{3,2},{2,0},
            {4,5},{5,7},{7,6},{6,4},
            {0,4},{1,5},{2,6},{3,7}
        };

        int chestIdx = 0;
        int drawnEdges = 0;
        for (me.kiwii.module.impl.ChestEspModule.ChestHit hit : hits) {
            double x0 = hit.x, y0 = hit.y, z0 = hit.z;
            double x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
            double[][] corners = {
                {x0,y0,z0},{x1,y0,z0},{x0,y0,z1},{x1,y0,z1},
                {x0,y1,z0},{x1,y1,z0},{x0,y1,z1},{x1,y1,z1}
            };
            float[][] screens = new float[8][];
            int visible = 0;
            for (int i = 0; i < 8; i++) {
                screens[i] = project(fr, corners[i][0], corners[i][1], corners[i][2]);
                if (screens[i] != null) visible++;
            }
            if (chestIdx++ == 0) {
                diag3D("cam=(" + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", fr.px, fr.py, fr.pz)
                        + ") sw=" + fr.sw + " sh=" + fr.sh
                        + " chest0=(" + x0 + "," + y0 + "," + z0 + ")"
                        + " visibleCorners=" + visible
                        + (screens[0] != null ? " c0=(" + screens[0][0] + "," + screens[0][1] + ")" : " c0=NULL"));
            }

            int rgb = chestColor(hit.type);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >>  8) & 0xFF) / 255f;
            float b = ( rgb        & 0xFF) / 255f;
            GL11.glColor4f(r, g, b, 0.9f);
            GL11.glBegin(GL11.GL_LINES);
            for (int[] e : edges) {
                float[] a = screens[e[0]], c = screens[e[1]];
                if (a == null || c == null) continue;
                GL11.glVertex2f(a[0], a[1]);
                GL11.glVertex2f(c[0], c[1]);
                drawnEdges++;
            }
            GL11.glEnd();

            float sumX = 0, minTopY = Float.NaN;
            int topCount = 0;
            for (int i = 4; i < 8; i++) {
                if (screens[i] != null) {
                    sumX += screens[i][0];
                    topCount++;
                    if (Float.isNaN(minTopY) || screens[i][1] < minTopY) minTopY = screens[i][1];
                }
            }
            if (topCount > 0 && esp.showLabel.getValue()) {
                float labelSx = sumX / topCount;
                float labelSy = minTopY;
                String txt = typeLabel(hit.type) + " " + Math.round(hit.distance) + "m";
                float scale = 0.5f;
                float w = FontUtil.getStringWidth(txt) * scale;
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glPushMatrix();
                GL11.glTranslatef(labelSx - w / 2f, labelSy - 10f, 0);
                GL11.glScalef(scale, scale, 1);
                FontUtil.drawStringWithShadow(txt, 0, 0, rgb);
                GL11.glPopMatrix();
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
        }

        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        diag3D("rendered chests=" + hits.size() + " edges=" + drawnEdges);
    }

    private static float[] projectWorldToScreen(double wx, double wy, double wz,
                                                double cx, double cy, double cz,
                                                double sinYaw, double cosYaw, double sinPit, double cosPit,
                                                double f, double aspect, int sw, int sh) {
        double rx = wx - cx, ry = wy - cy, rz = wz - cz;
        double camX = -rx * cosYaw - rz * sinYaw;
        double camY = ry * cosPit - rx * sinYaw * sinPit + rz * cosYaw * sinPit;
        double camZ = -rx * sinYaw * cosPit - ry * sinPit + rz * cosYaw * cosPit;
        if (camZ < 0.1) return null;
        double ndcX = (camX / camZ) * (f / aspect);
        double ndcY = (camY / camZ) * f;
        float sx = (float) ((ndcX + 1) * 0.5 * sw);
        float sy = (float) ((1 - (ndcY + 1) * 0.5) * sh);
        return new float[]{ sx, sy };
    }

    private static final class HudFrame {
        final int sw, sh;
        final double px, py, pz;
        final double sinYaw, cosYaw, sinPit, cosPit;
        final double f, aspect;
        HudFrame(int sw, int sh, double px, double py, double pz, double sinYaw, double cosYaw, double sinPit, double cosPit, double f, double aspect) {
            this.sw = sw; this.sh = sh;
            this.px = px; this.py = py; this.pz = pz;
            this.sinYaw = sinYaw; this.cosYaw = cosYaw; this.sinPit = sinPit; this.cosPit = cosPit;
            this.f = f; this.aspect = aspect;
        }
    }

    private static HudFrame readHudFrame(int sw, int sh) {
        Object player = MinecraftMapper.getPlayer();
        if (player == null) return null;
        double px, py, pz;
        float yaw, pitch;

        double[] camPos = new double[3];
        boolean fromRm = readCameraFromRenderManager(camPos);

        try {
            java.lang.reflect.Field fyaw = MappingUtils.getField("Entity.rotationYaw");
            java.lang.reflect.Field fpit = MappingUtils.getField("Entity.rotationPitch");
            if (fyaw == null || fpit == null) return null;
            fyaw.setAccessible(true); fpit.setAccessible(true);
            yaw = fyaw.getFloat(player);
            pitch = fpit.getFloat(player);

            double eyeH = readEyeHeight(player);
            if (fromRm) {
                px = camPos[0]; py = camPos[1] + eyeH; pz = camPos[2];
            } else {
                java.lang.reflect.Field fpx = MappingUtils.getField("Entity.posX");
                java.lang.reflect.Field fpy = MappingUtils.getField("Entity.posY");
                java.lang.reflect.Field fpz = MappingUtils.getField("Entity.posZ");
                java.lang.reflect.Field fppx = MappingUtils.getField("Entity.prevPosX");
                java.lang.reflect.Field fppy = MappingUtils.getField("Entity.prevPosY");
                java.lang.reflect.Field fppz = MappingUtils.getField("Entity.prevPosZ");
                if (fpx == null || fpy == null || fpz == null) return null;
                fpx.setAccessible(true); fpy.setAccessible(true); fpz.setAccessible(true);
                float pt = readPartialTicks();
                double cx = fpx.getDouble(player), cy = fpy.getDouble(player), cz = fpz.getDouble(player);
                if (fppx != null && fppy != null && fppz != null && pt >= 0f && pt <= 1f) {
                    fppx.setAccessible(true); fppy.setAccessible(true); fppz.setAccessible(true);
                    double prevX = fppx.getDouble(player), prevY = fppy.getDouble(player), prevZ = fppz.getDouble(player);
                    px = prevX + (cx - prevX) * pt;
                    py = prevY + (cy - prevY) * pt + eyeH;
                    pz = prevZ + (cz - prevZ) * pt;
                } else { px = cx; py = cy + eyeH; pz = cz; }
            }
        } catch (Throwable t) { return null; }

        int f5 = readThirdPersonView();
        if (fromRm) {
            if (f5 == 2) { yaw += 180f; pitch = -pitch; }
        } else if (f5 != 0) {
            double yawR0 = Math.toRadians(yaw), pitchR0 = Math.toRadians(pitch);
            double lookX = -Math.sin(yawR0) * Math.cos(pitchR0);
            double lookY = -Math.sin(pitchR0);
            double lookZ =  Math.cos(yawR0) * Math.cos(pitchR0);
            double dist = 4.0;
            if (f5 == 1) { px -= lookX * dist; py -= lookY * dist; pz -= lookZ * dist; }
            else         { px += lookX * dist; py += lookY * dist; pz += lookZ * dist; yaw += 180f; pitch = -pitch; }
        }

        double fovDeg = readActualFov(70f);
        double aspect = (double) sw / (double) sh;
        double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
        return new HudFrame(sw, sh, px, py, pz,
                Math.sin(yawR), Math.cos(yawR), Math.sin(pitchR), Math.cos(pitchR),
                1.0 / Math.tan(Math.toRadians(fovDeg) / 2.0), aspect);
    }

    private static float[] project(HudFrame fr, double wx, double wy, double wz) {
        return projectWorldToScreen(wx, wy, wz, fr.px, fr.py, fr.pz, fr.sinYaw, fr.cosYaw, fr.sinPit, fr.cosPit, fr.f, fr.aspect, fr.sw, fr.sh);
    }

    private static void renderPlayerEsp3D(HudFrame fr) {
        me.kiwii.module.impl.PlayerEspModule esp = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.PlayerEspModule.class);
        if (esp == null || !esp.isEnabled()) return;
        java.util.List<me.kiwii.module.impl.PlayerEspModule.PlayerHit> hits;
        try { hits = esp.collectPlayers(); } catch (Throwable t) { return; }
        if (hits == null || hits.isEmpty()) return;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        float lw = (float)(double) esp.lineWidth.getValue();

        boolean filled = esp.filledBox.getValue();
        String cmode = esp.colorMode.getValue();

        float pt = readPartialTicks();
        float sanityLimit = Math.max(fr.sw, fr.sh) * 3f;
        long __diagNow = System.currentTimeMillis();
        boolean __diagDoLog = __diagNow - lastPlayerEspDiag > 2000L;
        if (__diagDoLog) { lastPlayerEspDiag = __diagNow; me.kiwii.util.Logger.info("[PlayerESP-3D] frame hits=" + hits.size() + " filled=" + filled); }
        int __drewFilled = 0, __skipVisible = 0, __skipClip = 0, __hitIdx = 0;
        for (me.kiwii.module.impl.PlayerEspModule.PlayerHit hit : hits) {
            double hw = 0.36;
            double hh = 1.90;
            double lx = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posX", "Entity.lastTickPosX", hit.x, pt);
            double ly = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posY", "Entity.lastTickPosY", hit.y, pt);
            double lz = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posZ", "Entity.lastTickPosZ", hit.z, pt);

            float[] center = project(fr, lx, ly + hh * 0.5, lz);
            if (center == null) continue;
            if (!Float.isFinite(center[0]) || !Float.isFinite(center[1])) continue;

            double x0 = lx - hw, y0 = ly - 0.02, z0 = lz - hw;
            double x1 = lx + hw, y1 = ly + hh, z1 = lz + hw;
            float[][] c = new float[8][];
            c[0] = project(fr, x0, y0, z0); c[1] = project(fr, x1, y0, z0);
            c[2] = project(fr, x0, y0, z1); c[3] = project(fr, x1, y0, z1);
            c[4] = project(fr, x0, y1, z0); c[5] = project(fr, x1, y1, z0);
            c[6] = project(fr, x0, y1, z1); c[7] = project(fr, x1, y1, z1);
            int visible = 0;
            for (int i = 0; i < 8; i++) {
                if (c[i] == null) continue;
                if (!Float.isFinite(c[i][0]) || !Float.isFinite(c[i][1])) { c[i] = null; continue; }
                visible++;
            }
            if (visible < 4) { __skipVisible++; if (__diagDoLog && __hitIdx == 0) me.kiwii.util.Logger.info("[PlayerESP-3D] hit0 skip visible=" + visible); __hitIdx++; continue; }

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] p : c) if (p != null) {
                if (p[0] < minX) minX = p[0]; if (p[0] > maxX) maxX = p[0];
                if (p[1] < minY) minY = p[1]; if (p[1] > maxY) maxY = p[1];
            }

            if (maxX < -fr.sw || minX > fr.sw * 2 || maxY < -fr.sh || minY > fr.sh * 2) { __skipClip++; if (__diagDoLog && __hitIdx == 0) me.kiwii.util.Logger.info("[PlayerESP-3D] hit0 skip offscreen b=(" + minX + "," + minY + "," + maxX + "," + maxY + ") sw=" + fr.sw + " sh=" + fr.sh); __hitIdx++; continue; }
            if (maxX - minX < 2f || maxY - minY < 2f) { __skipClip++; if (__diagDoLog && __hitIdx == 0) me.kiwii.util.Logger.info("[PlayerESP-3D] hit0 skip tinybox b=(" + minX + "," + minY + "," + maxX + "," + maxY + ")"); __hitIdx++; continue; }

            int rgb = pickPlayerColor(cmode, hit);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >>  8) & 0xFF) / 255f;
            float b = ( rgb        & 0xFF) / 255f;

            if (filled) {
                GL11.glColor4f(r, g, b, 0.35f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(minX, minY); GL11.glVertex2f(maxX, minY);
                GL11.glVertex2f(maxX, maxY); GL11.glVertex2f(minX, maxY);
                GL11.glEnd();
                __drewFilled++;
            }

            if (filled) {
                GL11.glLineWidth(lw + 1.5f);
                GL11.glColor4f(0f, 0f, 0f, 0.55f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(minX, minY); GL11.glVertex2f(maxX, minY);
                GL11.glVertex2f(maxX, maxY); GL11.glVertex2f(minX, maxY);
                GL11.glEnd();
                GL11.glLineWidth(lw);
                GL11.glColor4f(r, g, b, 0.95f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(minX, minY); GL11.glVertex2f(maxX, minY);
                GL11.glVertex2f(maxX, maxY); GL11.glVertex2f(minX, maxY);
                GL11.glEnd();
            } else {
                float bw = maxX - minX;
                float bh = maxY - minY;
                float cornerLen = Math.max(3f, Math.min(bw, bh) * 0.22f);

                GL11.glLineWidth(lw + 1.5f);
                GL11.glColor4f(0f, 0f, 0f, 0.55f);
                drawCornerBrackets(minX, minY, maxX, maxY, cornerLen);

                GL11.glLineWidth(lw);
                GL11.glColor4f(r, g, b, 0.95f);
                drawCornerBrackets(minX, minY, maxX, maxY, cornerLen);
            }

        }

        if (__diagDoLog) me.kiwii.util.Logger.info("[PlayerESP-3D] frame end filled=" + __drewFilled + " skipVisible=" + __skipVisible + " skipClip=" + __skipClip);

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static volatile long lastPlayerEspDiag;

    private static void renderTracers3D(HudFrame fr) {
        me.kiwii.module.impl.TracersModule tr = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.TracersModule.class);
        if (tr == null || !tr.isEnabled()) return;
        me.kiwii.module.impl.PlayerEspModule esp = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.PlayerEspModule.class);
        if (esp == null) return;
        java.util.List<me.kiwii.module.impl.PlayerEspModule.PlayerHit> hits;
        try { hits = esp.collectPlayers(); } catch (Throwable t) { return; }
        if (hits == null || hits.isEmpty()) return;

        double range = tr.range.getValue();
        double range2 = range * range;
        float lw = (float) (double) tr.lineWidth.getValue();
        float pt = readPartialTicks();

        float startX = fr.sw * 0.5f;
        float startY = fr.sh;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(lw);

        boolean trHideInv = tr.hideInvisible.getValue();
        for (me.kiwii.module.impl.PlayerEspModule.PlayerHit hit : hits) {
            double dxh = hit.x - fr.px;
            double dyh = hit.y - fr.py;
            double dzh = hit.z - fr.pz;
            if (dxh * dxh + dyh * dyh + dzh * dzh > range2) continue;
            if (trHideInv && me.kiwii.module.impl.PlayerEspModule.isEntityInvisible(hit.entity)) continue;

            double lx = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posX", "Entity.lastTickPosX", hit.x, pt);
            double ly = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posY", "Entity.lastTickPosY", hit.y, pt);
            double lz = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posZ", "Entity.lastTickPosZ", hit.z, pt);

            float[] target = project(fr, lx, ly + 0.95, lz);
            if (target == null) continue;
            if (!Float.isFinite(target[0]) || !Float.isFinite(target[1])) continue;
            if (target[0] < -fr.sw || target[0] > fr.sw * 2 || target[1] < -fr.sh || target[1] > fr.sh * 2) continue;

            GL11.glColor4f(0f, 0f, 0f, 0.45f);
            GL11.glLineWidth(lw + 1.2f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(startX, startY);
            GL11.glVertex2f(target[0], target[1]);
            GL11.glEnd();

            GL11.glColor4f(45f / 255f, 175f / 255f, 100f / 255f, 0.72f);
            GL11.glLineWidth(lw);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(startX, startY);
            GL11.glVertex2f(target[0], target[1]);
            GL11.glEnd();
        }

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void drawCornerBrackets(float minX, float minY, float maxX, float maxY, float len) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(minX, minY); GL11.glVertex2f(minX + len, minY);
        GL11.glVertex2f(minX, minY); GL11.glVertex2f(minX, minY + len);
        GL11.glVertex2f(maxX, minY); GL11.glVertex2f(maxX - len, minY);
        GL11.glVertex2f(maxX, minY); GL11.glVertex2f(maxX, minY + len);
        GL11.glVertex2f(minX, maxY); GL11.glVertex2f(minX + len, maxY);
        GL11.glVertex2f(minX, maxY); GL11.glVertex2f(minX, maxY - len);
        GL11.glVertex2f(maxX, maxY); GL11.glVertex2f(maxX - len, maxY);
        GL11.glVertex2f(maxX, maxY); GL11.glVertex2f(maxX, maxY - len);
        GL11.glEnd();
    }

    private static int pickPlayerColor(String mode, me.kiwii.module.impl.PlayerEspModule.PlayerHit hit) {
        if ("Health".equalsIgnoreCase(mode)) {
            float f = Math.max(0f, Math.min(1f, hit.health / (hit.maxHealth > 0 ? hit.maxHealth : 20f)));
            if (f > 0.5f) {
                float t = (f - 0.5f) * 2f;
                int r = (int) ((1f - t) * 200f + 40f);
                int g = 210;
                int b = 60 + (int)(t * 30);
                return (r << 16) | (g << 8) | b;
            } else {
                float t = f * 2f;
                int r = 240;
                int g = (int) (30 + t * 160f);
                int b = 40;
                return (r << 16) | (g << 8) | b;
            }
        }
        if ("Distance".equalsIgnoreCase(mode)) {
            double f = Math.max(0d, Math.min(1d, hit.distance / 64d));
            int r = 60 + (int)(f * 195);
            int g = 220 - (int)(f * 100);
            int b = 130 - (int)(f * 100);
            return (r << 16) | (g << 8) | b;
        }
        return ACCENT.getRGB() & 0x00FFFFFF;
    }

    private static void renderNameTags(HudFrame fr) {
        me.kiwii.module.impl.NameTagsModule tags = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.NameTagsModule.class);
        if (tags == null || !tags.isEnabled()) return;
        me.kiwii.module.impl.PlayerEspModule esp = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.PlayerEspModule.class);
        if (esp == null) return;
        java.util.List<me.kiwii.module.impl.PlayerEspModule.PlayerHit> hits;
        try { hits = esp.collectPlayers(); } catch (Throwable t) { return; }
        if (hits == null || hits.isEmpty()) return;

        float scale = (float)(double) tags.scale.getValue() * 0.5f;
        boolean showDist = tags.showDistance.getValue();
        boolean showHp = tags.showHealthText.getValue();
        boolean bg = tags.showBackground.getValue();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        int accentRgb = ACCENT.getRGB();
        int distColor = 0xFFB8B8C8;
        int sepColor  = 0xFF3A3A48;

        float ptTags = readPartialTicks();
        float tagSanity = Math.max(fr.sw, fr.sh) * 3f;
        boolean tagHideInv = tags.hideInvisible.getValue();
        int hitIdx = 0;
        for (me.kiwii.module.impl.PlayerEspModule.PlayerHit hit : hits) {
            if (tagHideInv && me.kiwii.module.impl.PlayerEspModule.isEntityInvisible(hit.entity)) continue;
            double lx = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posX", "Entity.lastTickPosX", hit.x, ptTags);
            double ly = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posY", "Entity.lastTickPosY", hit.y, ptTags);
            double lz = me.kiwii.module.impl.PlayerEspModule.liveInterp(hit.entity, "Entity.posZ", "Entity.lastTickPosZ", hit.z, ptTags);
            float[] head = project(fr, lx, ly + 2.10, lz);
            long tagNow = System.currentTimeMillis();
            if (hitIdx == 0 && tagNow - lastTagDiagMs > 250L) {
                lastTagDiagMs = tagNow;
                float[] matHead = projectWithMatrix(lx, ly + 2.10, lz, fr.sw, fr.sh);
                float[] fbHead = projectWorldToScreen(lx, ly + 2.10, lz, fr.px, fr.py, fr.pz, fr.sinYaw, fr.cosYaw, fr.sinPit, fr.cosPit, fr.f, fr.aspect, fr.sw, fr.sh);
                Logger.info("[NameTags] hit0 name=" + hit.name
                        + " pos=(" + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", lx, ly, lz)
                        + ") cam=(" + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", fr.px, fr.py, fr.pz)
                        + ") sinYaw=" + String.format(java.util.Locale.ROOT, "%.2f", fr.sinYaw)
                        + " cosYaw=" + String.format(java.util.Locale.ROOT, "%.2f", fr.cosYaw)
                        + " snap=" + snapValid
                        + " matHead=" + (matHead != null ? "(" + matHead[0] + "," + matHead[1] + ")" : "NULL")
                        + " fbHead=" + (fbHead != null ? "(" + fbHead[0] + "," + fbHead[1] + ")" : "NULL")
                        + " chosenHead=" + (head != null ? "(" + head[0] + "," + head[1] + ")" : "NULL")
                        + " sw=" + fr.sw + " sh=" + fr.sh + " sanity=" + tagSanity);
            }
            hitIdx++;
            if (head == null) continue;
            if (!Float.isFinite(head[0]) || !Float.isFinite(head[1])) continue;

            String name = hit.name != null ? hit.name : "?";
            boolean hpValid = Float.isFinite(hit.health) && hit.health >= 0f;
            float hpFrac = hpValid ? Math.max(0f, Math.min(1f, hit.health / (hit.maxHealth > 0 ? hit.maxHealth : 20f))) : 1f;
            int hpColor;
            if (!hpValid) hpColor = 0xFFA0A0A0;
            else if (hpFrac > 0.5f) hpColor = 0xFF64E070;
            else if (hpFrac > 0.25f) hpColor = 0xFFF4C842;
            else hpColor = 0xFFEC4848;

            String hpStr = showHp ? (hpValid ? formatOneDecimal(hit.health * 0.5f) + " HP" : "? HP") : null;
            String distStr = showDist ? (Math.round(hit.distance) + "m") : null;
            String sep = "  ";
            float nameW = FontUtil.getStringWidth(name) * scale;
            float hpW = hpStr != null ? FontUtil.getStringWidth(hpStr) * scale : 0f;
            float distW = distStr != null ? FontUtil.getStringWidth(distStr) * scale : 0f;
            float sepW = FontUtil.getStringWidth(sep) * scale;
            float totalW = nameW + (hpStr != null ? sepW + hpW : 0f) + (distStr != null ? sepW + distW : 0f);
            float h = FontUtil.getFontHeight() * scale;

            float padX = 4f;
            float padY = 2f;
            float bgX = head[0] - totalW / 2f - padX;
            float bgY = head[1] - h - padY - 4f;
            float bgW = totalW + padX * 2f;
            float bgH = h + padY * 2f;

            if (bg) {
                RoundedUtil.rect(bgX, bgY, bgW, bgH, new Color(10, 12, 18, 205));
                RoundedUtil.rect(bgX, bgY, bgW, 1f, new Color(accentRgb, true));
            }

            float rowX = head[0] - totalW / 2f;
            float rowY = bgY + padY;
            drawText(name, rowX, rowY, scale, accentRgb);
            rowX += nameW;
            if (hpStr != null) {
                drawText(sep, rowX, rowY, scale, sepColor); rowX += sepW;
                drawText(hpStr, rowX, rowY, scale, hpColor); rowX += hpW;
            }
            if (distStr != null) {
                drawText(sep, rowX, rowY, scale, sepColor); rowX += sepW;
                drawText(distStr, rowX, rowY, scale, distColor);
            }
        }
        GL11.glColor4f(1, 1, 1, 1);
    }


    private static void drawText(String s, float x, float y, float scale, int rgb) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        FontUtil.drawStringWithShadow(s, 0, 0, rgb);
        GL11.glPopMatrix();
    }


    private static String formatOneDecimal(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static void renderChestEspList(int sw) {
        me.kiwii.module.impl.ChestEspModule esp = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.ChestEspModule.class);
        if (esp == null || !esp.isEnabled()) return;

        java.util.List<me.kiwii.module.impl.ChestEspModule.ChestHit> hits;
        try { hits = esp.collectChests(); } catch (Throwable t) { return; }
        if (hits == null || hits.isEmpty()) return;

        hits.sort(new java.util.Comparator<me.kiwii.module.impl.ChestEspModule.ChestHit>() {
            public int compare(me.kiwii.module.impl.ChestEspModule.ChestHit a,
                               me.kiwii.module.impl.ChestEspModule.ChestHit b) {
                return Double.compare(a.distance, b.distance);
            }
        });

        Object player = Kiwii.getInstance().getModuleManager().getModule(me.kiwii.module.impl.ChestEspModule.class) != null
                ? me.kiwii.mapping.MinecraftMapper.getMinecraft() : null;
        float py = 60f;
        float textScale = 0.6f;
        float lineHeight = FontUtil.getFontHeight() * textScale + 2f;

        String header = "Chests (" + hits.size() + ")";
        float hwidth = FontUtil.getStringWidth(header) * textScale;
        drawEspRowBg(sw - hwidth - 8f, py, hwidth + 6f, lineHeight);
        GL11.glPushMatrix();
        GL11.glTranslatef(sw - hwidth - 5f, py + 1f, 0);
        GL11.glScalef(textScale, textScale, 1);
        FontUtil.drawStringWithShadow(header, 0, 0, ACCENT.getRGB());
        GL11.glPopMatrix();

        py += lineHeight;
        int shown = 0;
        for (me.kiwii.module.impl.ChestEspModule.ChestHit hit : hits) {
            if (shown++ >= 8) break;
            String label = typeLabel(hit.type) + "  " + Math.round(hit.distance) + "m  " + directionArrow(hit);
            float w = FontUtil.getStringWidth(label) * textScale;
            drawEspRowBg(sw - w - 8f, py, w + 6f, lineHeight);
            GL11.glPushMatrix();
            GL11.glTranslatef(sw - w - 5f, py + 1f, 0);
            GL11.glScalef(textScale, textScale, 1);
            FontUtil.drawStringWithShadow(label, 0, 0, chestColor(hit.type));
            GL11.glPopMatrix();
            py += lineHeight;
        }
    }

    private static void drawEspRowBg(float x, float y, float w, float h) {
        RoundedUtil.rect(x, y, w, h, new Color(12, 12, 18, 180));
    }

    private static String typeLabel(me.kiwii.module.impl.ChestEspModule.ChestType t) { return "Chest"; }

    private static int chestColor(me.kiwii.module.impl.ChestEspModule.ChestType t) { return 0xFFFFFFFF; }

    private static String directionArrow(me.kiwii.module.impl.ChestEspModule.ChestHit hit) {
        try {
            Object player = me.kiwii.mapping.MinecraftMapper.getMinecraft();
            if (player == null) return "";
            java.lang.reflect.Field f = me.kiwii.util.MappingUtils.getField("Minecraft.thePlayer");
            if (f == null) return "";
            f.setAccessible(true);
            Object p = f.get(player);
            if (p == null) return "";
            double px = readPlayerDouble(p, "Entity.posX");
            double pz = readPlayerDouble(p, "Entity.posZ");
            double dx = hit.x + 0.5 - px;
            double dz = hit.z + 0.5 - pz;
            double angle = Math.toDegrees(Math.atan2(-dx, dz));
            int idx = (int) Math.round(((angle + 360) % 360) / 45.0) % 8;
            String[] dirs = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };
            return dirs[idx];
        } catch (Throwable ignored) { return ""; }
    }

    private static double readPlayerDouble(Object player, String mapping) {
        try {
            java.lang.reflect.Field f = me.kiwii.util.MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(player); }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void pushHudState() {
        int width = Math.max(1, ScissorUtil.getScaledWidth());
        int height = Math.max(1, ScissorUtil.getScaledHeight());

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, width, height, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void popHudState() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
    }

    private static Method getDrawStringMethod() {
        Method cached = drawStringMethod;
        if (cached != null) return cached;
        Method mapped = MappingUtils.getMethod("FontRenderer.drawString");
        if (mapped == null) return null;
        mapped.setAccessible(true);
        drawStringMethod = mapped;
        return mapped;
    }

    private static void markHudStatus(String message) {
        FileWriter writer = null;
        try {
            writer = new FileWriter("C:\\kiwii\\logs\\hud_hook.txt", true);
            writer.write(message);
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime < 5000L) return;
        lastWarnTime = now;
        Logger.warn(message);
    }
}
