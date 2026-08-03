package me.kiwii.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.setting.StringOption;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class PlayerEspModule extends Module {

    public final NumberOption  range;
    public final BooleanOption filledBox;
    public final StringOption  colorMode;
    public final NumberOption  lineWidth;
    public final BooleanOption hideInvisible;

    private volatile Class<?> playerClass;
    private volatile Field    playerListField;
    private volatile Class<?> playerListFieldOwner;
    private volatile Method   getGameProfile;
    private volatile Method   gpGetName;
    private volatile Method[] floatCandidates;
    private volatile Method   getMaxHealthMethod;
    private volatile Method[] healthCandidates;
    private volatile Method   entityGetName;
    private volatile boolean  discoveryDone;

    private volatile List<PlayerHit> cachedHits = java.util.Collections.emptyList();
    private volatile long lastRefreshMs;
    private static final long REFRESH_INTERVAL_MS = 50L;

    @Override
    public String getSuffix() { return colorMode.getValue(); }

    public PlayerEspModule() {
        super("PlayerESP", "3D box for nearby players", Category.RENDER, 0);
        range         = new NumberOption ("Range",       64.0D, 8.0D, 256.0D, 8.0D, this);
        filledBox     = new BooleanOption("Filled Box",  false, this);
        colorMode     = new StringOption ("Color Mode",  "Fixed", this, "Fixed", "Health", "Distance");
        lineWidth     = new NumberOption ("Line Width",  1.5D, 0.5D, 4.0D, 0.5D, this);
        hideInvisible = new BooleanOption("Hide Invisible", true, this);
        addOptions(range, filledBox, colorMode, lineWidth, hideInvisible);
    }

    @Override public void onEnable()  { Logger.info("PlayerESP enabled"); discoveryDone = false; }
    @Override public void onDisable() { Logger.info("PlayerESP disabled"); cachedHits = java.util.Collections.emptyList(); }

    @Override public void onUpdate() { refreshIfStale(); }
    public List<PlayerHit> collectPlayers() { refreshIfStale(); return cachedHits; }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            lastRefreshMs = now;
            try { cachedHits = runCollection(); }
            catch (Throwable t) { Logger.warn("[PlayerESP] refresh: " + t.getMessage()); }
        }
    }

    private List<PlayerHit> runCollection() {
        ArrayList<PlayerHit> hits = new ArrayList<PlayerHit>();
        Object world = getTheWorld();
        Object localPlayer = getThePlayer();
        if (world == null || localPlayer == null) return hits;

        if (playerListFieldOwner != null && !playerListFieldOwner.isInstance(world)) {
            Logger.info("[PlayerESP] world class changed, resetting discovery");
            discoveryDone = false;
            playerListField = null;
            playerListFieldOwner = null;
        }

        if (!discoveryDone) { discover(localPlayer, world); discoveryDone = true; }
        if (playerClass == null) return hits;

        double lpx = readDouble(localPlayer, "Entity.posX");
        double lpy = readDouble(localPlayer, "Entity.posY");
        double lpz = readDouble(localPlayer, "Entity.posZ");
        double rSq = range.getValue() * range.getValue();
        boolean includeSelf = false;

        List<?> sourceList = null;
        if (playerListField != null) {
            try { Object v = playerListField.get(world); if (v instanceof List) sourceList = (List<?>) v; }
            catch (Throwable ignored) {}
        }
        if (sourceList == null) {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
            ArrayList<Object> collected = new ArrayList<Object>();
            for (Class<?> c = world.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(world);
                        if (!(v instanceof List)) continue;
                        for (Object e : (List<?>) v) {
                            if (e == null) continue;
                            if (!playerClass.isInstance(e)) continue;
                            if (!seen.containsKey(e)) { seen.put(e, Boolean.TRUE); collected.add(e); }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            sourceList = collected;
        }

        for (Object e : sourceList) {
            if (e == null) continue;
            if (!playerClass.isInstance(e)) continue;
            if (!includeSelf && e == localPlayer) continue;

            double ex = interp(e, "Entity.posX", "Entity.lastTickPosX", lpx);
            double ey = interp(e, "Entity.posY", "Entity.lastTickPosY", lpy);
            double ez = interp(e, "Entity.posZ", "Entity.lastTickPosZ", lpz);
            if (ex == Double.NaN) continue;
            double dx = ex - lpx, dy = ey - lpy, dz = ez - lpz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > rSq) continue;

            float health = -1f, maxHealth = 20f;
            if (getMaxHealthMethod != null) {
                try { Object r = getMaxHealthMethod.invoke(e); if (r instanceof Number) {
                    float m = ((Number) r).floatValue();
                    if (Float.isFinite(m) && m > 0) maxHealth = m;
                } }
                catch (Throwable ignored) {}
            }
            if (!Float.isFinite(maxHealth) || maxHealth <= 0) maxHealth = 20f;
            if (healthCandidates != null && healthCandidates.length > 0) {
                float best = -1f;
                for (Method fm : healthCandidates) {
                    try {
                        Object r = fm.invoke(e);
                        if (!(r instanceof Number)) continue;
                        float v = ((Number) r).floatValue();
                        if (!Float.isFinite(v)) continue;
                        if (v < 0f || v > maxHealth + 0.001f) continue;
                        if (best < 0f || v < best) best = v;
                    } catch (Throwable ignored) {}
                }
                health = best;
            }

            String name = extractName(e);
            hits.add(new PlayerHit(e, name, ex, ey, ez, health, maxHealth, Math.sqrt(distSq)));
        }
        return applyFilters(hits);
    }

    private List<PlayerHit> applyFilters(List<PlayerHit> raw) {
        boolean stripCR   = true;
        boolean dedupe    = true;
        boolean stripInv  = hideInvisible.getValue();

        ArrayList<PlayerHit> out = new ArrayList<PlayerHit>(raw.size());
        java.util.HashMap<String, Float> bestHealthByName = null;
        if (dedupe) {
            bestHealthByName = new java.util.HashMap<String, Float>();
            for (PlayerHit h : raw) {
                if (h.name == null) continue;
                Float prev = bestHealthByName.get(h.name);
                if (prev == null || h.health > prev) bestHealthByName.put(h.name, h.health);
            }
        }

        for (PlayerHit h : raw) {
            String n = h.name != null ? h.name : "";
            if (stripCR) {
                String up = n.trim();
                if (up.startsWith("[CR]") || up.startsWith("[cr]") || up.startsWith("(CR)")) continue;
                int ping = readPing(h.entity);
                if (ping < 0) continue;
            }
            if (stripInv && isInvisible(h.entity)) continue;
            if (dedupe && bestHealthByName != null) {
                Float best = bestHealthByName.get(n);
                if (best != null && h.health < best - 0.01f) continue;
            }
            out.add(h);
        }
        return out;
    }

    public static boolean isEntityInvisible(Object entity) {
        return staticIsInvisible(entity);
    }

    private static boolean staticIsInvisible(Object entity) {
        if (entity == null) return false;
        try {
            java.lang.reflect.Method flag = MappingUtils.getMethod("Entity.getFlag");
            if (flag == null) return false;
            Object r = flag.invoke(entity, Integer.valueOf(5));
            if (!(r instanceof Boolean)) return false;
            if (!(Boolean) r) return false;
            try {
                Object dw = MappingUtils.getField("Entity.dataWatcher") != null
                        ? MappingUtils.getField("Entity.dataWatcher").get(entity) : null;
                if (dw != null) {
                    java.lang.reflect.Method get = null;
                    for (java.lang.reflect.Method m : dw.getClass().getMethods()) {
                        if (m.getParameterCount() != 1) continue;
                        if (m.getParameterTypes()[0] != int.class) continue;
                        if (m.getReturnType() != byte.class && m.getReturnType() != Byte.class) continue;
                        get = m; break;
                    }
                    if (get != null) {
                        Object flags = get.invoke(dw, Integer.valueOf(0));
                        if (flags instanceof Byte) {
                            byte b = (Byte) flags;
                            return (b & 0x20) != 0;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isInvisible(Object entity) {
        return staticIsInvisible(entity);
    }

    private boolean isInvisibleImpl_OLD(Object entity) {
        if (entity == null) return false;
        try {
            java.lang.reflect.Method flag = MappingUtils.getMethod("Entity.getFlag");
            if (flag == null) return false;
            Object r = flag.invoke(entity, Integer.valueOf(5));
            if (!(r instanceof Boolean)) return false;
            if (!(Boolean) r) return false;
            try {
                Object dw = MappingUtils.getField("Entity.dataWatcher") != null
                        ? MappingUtils.getField("Entity.dataWatcher").get(entity) : null;
                if (dw != null) {
                    java.lang.reflect.Method get = null;
                    for (java.lang.reflect.Method m : dw.getClass().getMethods()) {
                        if (m.getParameterCount() != 1) continue;
                        if (m.getParameterTypes()[0] != int.class) continue;
                        if (m.getReturnType() != byte.class && m.getReturnType() != Byte.class) continue;
                        get = m; break;
                    }
                    if (get != null) {
                        Object flags = get.invoke(dw, Integer.valueOf(0));
                        if (flags instanceof Byte) {
                            byte b = (Byte) flags;
                            return (b & 0x20) != 0;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private int readPing(Object entity) {
        if (entity == null) return 999;
        try {
            Field pif = MappingUtils.getField("AbstractClientPlayer.playerInfo");
            if (pif != null) {
                pif.setAccessible(true);
                Object info = pif.get(entity);
                if (info != null) {
                    java.lang.reflect.Method g = MappingUtils.getMethod("NetworkPlayerInfo.getResponseTime");
                    if (g != null) { g.setAccessible(true); Object v = g.invoke(info); if (v instanceof Integer) return (Integer) v; }
                    Field rf = MappingUtils.getField("NetworkPlayerInfo.responseTime");
                    if (rf != null) { rf.setAccessible(true); return rf.getInt(info); }
                }
            }
        } catch (Throwable ignored) {}
        return 999;
    }

    private double interp(Object e, String curKey, String prevKey, double fallback) {
        try {
            Field fc = MappingUtils.getField(curKey);
            if (fc == null) return fallback;
            fc.setAccessible(true);
            double cur = fc.getDouble(e);
            Field fp = MappingUtils.getField(prevKey);
            if (fp == null) return cur;
            fp.setAccessible(true);
            double prev = fp.getDouble(e);
            float pt = readPartialTicks();
            if (pt < 0f || pt > 1f) return cur;
            return prev + (cur - prev) * pt;
        } catch (Throwable ignored) { return fallback; }
    }

    private float readPartialTicks() {
        try {
            Field f = MappingUtils.getField("craftrise.Config.renderPartialTicks");
            if (f == null) return 1f;
            f.setAccessible(true);
            try { return f.getFloat(null); }
            catch (Throwable ns) {
                Object cfg = me.kiwii.mapping.MinecraftMapper.getMinecraft();
                if (cfg != null) return f.getFloat(cfg);
            }
        } catch (Throwable ignored) {}
        return 1f;
    }

    private void discover(Object localPlayer, Object world) {
        playerClass = MappingUtils.get("EntityPlayer");
        if (playerClass == null) { Logger.warn("[PlayerESP] EntityPlayer class not mapped"); return; }

        try {
            Field pl = MappingUtils.getField("World.playerEntities");
            if (pl != null) { pl.setAccessible(true); playerListField = pl; playerListFieldOwner = world.getClass(); }
        } catch (Throwable ignored) {}

        try {
            Method m = MappingUtils.getMethod("EntityPlayer.getGameProfile");
            if (m != null) { m.setAccessible(true); getGameProfile = m; }
            if (getGameProfile != null) {
                Object gp = getGameProfile.invoke(localPlayer);
                if (gp != null) {
                    for (Method mm : gp.getClass().getMethods()) {
                        if (mm.getParameterCount() != 0) continue;
                        if (mm.getReturnType() != String.class) continue;
                        if (!"getName".equals(mm.getName())) continue;
                        mm.setAccessible(true);
                        gpGetName = mm;
                        break;
                    }
                }
            }
        } catch (Throwable t) { Logger.warn("[PlayerESP] getGameProfile discover: " + t.getMessage()); }

        try {
            java.util.ArrayList<Method> pool = new java.util.ArrayList<Method>();
            java.util.ArrayList<Method> around20 = new java.util.ArrayList<Method>();
            for (Class<?> c = localPlayer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getParameterCount() != 0) continue;
                    if (mm.getReturnType() != float.class) continue;
                    if (Modifier.isStatic(mm.getModifiers())) continue;
                    mm.setAccessible(true);
                    try {
                        Object r = mm.invoke(localPlayer);
                        if (r instanceof Number) {
                            float v = ((Number) r).floatValue();
                            if (v >= 0f && v <= 25f) pool.add(mm);
                            if (Math.abs(v - 20f) < 0.001f) around20.add(mm);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            floatCandidates = pool.toArray(new Method[0]);
            healthCandidates = around20.toArray(new Method[0]);
            if (!around20.isEmpty()) getMaxHealthMethod = around20.get(0);
        } catch (Throwable ignored) {}

        entityGetName = null;
        try {
            Class<?> base = playerClass;
            for (Class<?> c = base; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getParameterCount() != 0) continue;
                    if (mm.getReturnType() != String.class) continue;
                    if (Modifier.isStatic(mm.getModifiers())) continue;
                    if ("toString".equals(mm.getName())) continue;
                    mm.setAccessible(true);
                    try {
                        Object r = mm.invoke(localPlayer);
                        if (!(r instanceof String)) continue;
                        String plain = stripColor((String) r);
                        if (looksLikeName(plain)) { entityGetName = mm; break; }
                    } catch (Throwable ignored) {}
                }
                if (entityGetName != null) break;
            }
        } catch (Throwable ignored) {}

        if (getGameProfile == null) {
            try {
                for (Class<?> c = localPlayer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method mm : c.getDeclaredMethods()) {
                        if (mm.getParameterCount() != 0) continue;
                        if (Modifier.isStatic(mm.getModifiers())) continue;
                        if (mm.getReturnType().isPrimitive()) continue;
                        mm.setAccessible(true);
                        try {
                            Object r = mm.invoke(localPlayer);
                            if (r == null) continue;
                            for (Method gm : r.getClass().getMethods()) {
                                if (gm.getParameterCount() == 0 && gm.getReturnType() == String.class && "getName".equals(gm.getName())) {
                                    Object nm = gm.invoke(r);
                                    if (nm instanceof String && ((String) nm).length() > 0) {
                                        getGameProfile = mm;
                                        gpGetName = gm;
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        if (getGameProfile != null) break;
                    }
                    if (getGameProfile != null) break;
                }
            } catch (Throwable ignored) {}
        }

        Logger.info("[PlayerESP] discovery: playerCls=" + playerClass.getSimpleName()
                + " playerList=" + (playerListField != null ? playerListField.getName() : "NULL")
                + " getGameProfile=" + (getGameProfile != null ? getGameProfile.getName() : "NULL")
                + " gpGetName=" + (gpGetName != null ? "OK" : "NULL")
                + " entityGetName=" + (entityGetName != null ? entityGetName.getName() : "NULL")
                + " floats=" + (floatCandidates != null ? floatCandidates.length : 0)
                + " hpCandidates=" + (healthCandidates != null ? healthCandidates.length : 0)
                + " getMaxHealth=" + (getMaxHealthMethod != null ? getMaxHealthMethod.getName() : "NULL"));
    }

    private static boolean isUsername(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 1 || n > 16) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static boolean looksLikeName(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 1 || n > 32) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':' || c == '/' || c == '\\' || c == '.') return false;
            if (c < 0x20) return false;
        }
        return true;
    }

    private static String stripColor(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    private volatile int nameDiagCount;
    private String extractName(Object entity) {
        String raw = null;
        String source = "";
        if (getGameProfile != null && gpGetName != null) {
            try {
                Object gp = getGameProfile.invoke(entity);
                if (gp != null) {
                    Object r = gpGetName.invoke(gp);
                    if (r instanceof String) { raw = (String) r; source = "gp"; }
                }
            } catch (Throwable t) { source = "gp:err:" + t.getClass().getSimpleName(); }
        }
        if (raw == null && entityGetName != null) {
            try {
                Object r = entityGetName.invoke(entity);
                if (r instanceof String) { raw = (String) r; source = "entityGetName"; }
            } catch (Throwable t) { source = "egn:err:" + t.getClass().getSimpleName(); }
        }
        String stripped = raw != null ? stripColor(raw) : null;
        boolean pass = stripped != null && looksLikeName(stripped);

        if (nameDiagCount < 6) {
            nameDiagCount++;
            Logger.info("[PlayerESP] name diag entity=" + entity.getClass().getSimpleName()
                    + " raw=[" + raw + "] stripped=[" + stripped + "] source=" + source + " pass=" + pass);
        }
        if (pass) return stripped;
        if (stripped != null && stripped.length() > 0 && stripped.length() < 32) return stripped;
        return "?";
    }

    private double readDouble(Object entity, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(entity); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public static final class PlayerHit {
        public final Object entity;
        public final String name;
        public final double x, y, z;
        public final float health, maxHealth;
        public final double distance;
        public PlayerHit(Object entity, String name, double x, double y, double z, float health, float maxHealth, double distance) {
            this.entity = entity;
            this.name = name; this.x = x; this.y = y; this.z = z;
            this.health = health; this.maxHealth = maxHealth;
            this.distance = distance;
        }
    }

    public static double liveInterp(Object entity, String curKey, String prevKey, double fallback, float partialTicks) {
        if (entity == null) return fallback;
        try {
            Field fc = MappingUtils.getField(curKey);
            if (fc == null) return fallback;
            fc.setAccessible(true);
            double cur = fc.getDouble(entity);
            Field fp = MappingUtils.getField(prevKey);
            if (fp == null) return cur;
            fp.setAccessible(true);
            double prev = fp.getDouble(entity);
            if (partialTicks < 0f || partialTicks > 1f) return cur;
            return prev + (cur - prev) * partialTicks;
        } catch (Throwable ignored) { return fallback; }
    }
}
