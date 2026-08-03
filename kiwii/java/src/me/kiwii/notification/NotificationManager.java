package me.kiwii.notification;

import me.kiwii.util.FontUtil;
import me.kiwii.util.RoundedUtil;
import me.kiwii.util.ScissorUtil;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class NotificationManager {

    private static final List<Notification> stack = new ArrayList<Notification>();
    private static final Map<String, Float> anims = new HashMap<String, Float>();
    private static final int MAX_STACK = 5;

    private static volatile boolean globalEnabled = true;

    private static final Color KIWI      = new Color(45, 175, 100);
    private static final Color RED       = new Color(232, 74, 74);
    private static final Color YELLOW    = new Color(238, 194, 62);
    private static final Color INFO_GRAY = new Color(150, 154, 162);

    private static final Color BG_FILL = new Color(18, 18, 22, 235);
    private static final Color SHADOW  = new Color(0, 0, 0, 90);

    public static void setEnabled(boolean v) { globalEnabled = v; }
    public static boolean isEnabled() { return globalEnabled; }

    public static void post(String title, String message, Notification.Type type, long duration) {
        if (!globalEnabled) return;
        synchronized (stack) {
            for (Iterator<Notification> it = stack.iterator(); it.hasNext();) {
                Notification n = it.next();
                if (title != null && title.equals(n.getTitle())) it.remove();
            }
            stack.add(new Notification(title, message, type, duration));
            while (stack.size() > MAX_STACK) stack.remove(0);
        }
    }

    public static void postModule(String moduleName, boolean enabled) {
        post(moduleName,
             enabled ? "Enabled" : "Disabled",
             enabled ? Notification.Type.ENABLED : Notification.Type.DISABLED,
             1800);
    }

    public static void postInfo(String title, String message)    { post(title, message, Notification.Type.INFO,    2200); }
    public static void postWarning(String title, String message) { post(title, message, Notification.Type.WARNING, 2500); }
    public static void postError(String title, String message)   { post(title, message, Notification.Type.ERROR,   3000); }

    public static Notification getCurrentNotification() {
        synchronized (stack) {
            return stack.isEmpty() ? null : stack.get(stack.size() - 1);
        }
    }

    private static void purgeExpired() {
        synchronized (stack) {
            long now = System.currentTimeMillis();
            for (Iterator<Notification> it = stack.iterator(); it.hasNext();) {
                Notification n = it.next();
                if (now - n.getStartTime() > n.getDuration() + 400) it.remove();
            }
        }
    }

    public static void render() {
        purgeExpired();
        List<Notification> snapshot;
        synchronized (stack) {
            if (stack.isEmpty()) return;
            snapshot = new ArrayList<Notification>(stack);
        }

        int sw = ScissorUtil.getScaledWidth();
        int sh = ScissorUtil.getScaledHeight();

        float pillHeight = 28f;
        float pillRadius = 4f;
        float margin     = 8f;
        float gap        = 5f;

        float baseY = sh - pillHeight - margin;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        for (int idx = 0; idx < snapshot.size(); idx++) {
            Notification n = snapshot.get(idx);
            boolean isClosing = n.isFinished();
            String animKey = "notif_anim_"     + idx;
            String progKey = "notif_progress_" + idx;
            float slideAnim = animate(animKey, isClosing ? 0.0f : 1.0f, 0.18f);
            if (slideAnim < 0.01f && isClosing) continue;

            float eased          = 1f - (float) Math.pow(1 - slideAnim, 3);
            float targetProgress = 1.0f - n.getProgress();
            float smoothProgress = animate(progKey, targetProgress, 0.12f);

            String title   = n.getTitle()   == null ? "" : n.getTitle();
            String message = n.getMessage() == null ? "" : n.getMessage();
            float  titleScale = 0.62f;
            float  msgScale   = 0.55f;
            float  titleW = FontUtil.getStringWidth(title)   * titleScale;
            float  msgW   = FontUtil.getStringWidth(message) * msgScale;
            float  contentW = Math.max(titleW, msgW);

            float accentBarW  = 1.5f;
            float leftPad     = 8f;
            float rightPad    = 10f;
            float pillWidth   = Math.max(115f, accentBarW + leftPad + contentW + rightPad);

            float slideOffset = (1f - eased) * (pillWidth + margin + 20);
            float x = sw - pillWidth - margin + slideOffset;
            int stackIndexFromBottom = snapshot.size() - 1 - idx;
            float y = baseY - stackIndexFromBottom * (pillHeight + gap);

            Color accent = accentFor(n.getType());
            float alpha  = eased;

            RoundedUtil.roundedRect(x + 1.5f, y + 2.5f, pillWidth, pillHeight, pillRadius,
                    new Color(SHADOW.getRed(), SHADOW.getGreen(), SHADOW.getBlue(),
                              (int) (SHADOW.getAlpha() * alpha)));

            RoundedUtil.roundedRect(x, y, pillWidth, pillHeight, pillRadius,
                    new Color(BG_FILL.getRed(), BG_FILL.getGreen(), BG_FILL.getBlue(),
                              (int) (BG_FILL.getAlpha() * alpha)));

            RoundedUtil.roundedRect(x, y, accentBarW, pillHeight, accentBarW * 0.5f,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                              (int) (255 * alpha)));

            float textX  = x + accentBarW + leftPad;
            float titleY = y + 3f;
            float msgY   = titleY + (FontUtil.getFontHeight() * titleScale) + 1f;

            int titleColor = new Color(240, 242, 246, (int) (255 * alpha)).getRGB();
            int msgColor   = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                                       (int) (200 * alpha)).getRGB();

            GL11.glPushMatrix();
            GL11.glTranslatef(textX, titleY, 0);
            GL11.glScalef(titleScale, titleScale, 1f);
            FontUtil.drawString(title, 0, 0, titleColor, false);
            GL11.glPopMatrix();

            GL11.glPushMatrix();
            GL11.glTranslatef(textX, msgY, 0);
            GL11.glScalef(msgScale, msgScale, 1f);
            FontUtil.drawString(message, 0, 0, msgColor, false);
            GL11.glPopMatrix();

            float progressH = 1.5f;
            float progressY = y + pillHeight - progressH;
            float progressAvailW = pillWidth - accentBarW - 4f;
            float progressW = progressAvailW * smoothProgress;
            RoundedUtil.roundedRect(x + accentBarW + 2f, progressY, progressW, progressH, progressH * 0.5f,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                              (int) (170 * alpha)));
        }

        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static Color accentFor(Notification.Type type) {
        if (type == null) return INFO_GRAY;
        switch (type) {
            case ENABLED:  return KIWI;
            case DISABLED: return RED;
            case ERROR:    return RED;
            case WARNING:  return YELLOW;
            case INFO:
            default:       return INFO_GRAY;
        }
    }

    private static float animate(String key, float target, float speed) {
        Float cur = anims.get(key);
        float c = cur == null ? 0f : cur.floatValue();
        c += (target - c) * speed;
        anims.put(key, c);
        return c;
    }
}
