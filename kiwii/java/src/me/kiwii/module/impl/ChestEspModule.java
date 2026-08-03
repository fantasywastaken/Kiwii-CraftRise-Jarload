package me.kiwii.module.impl;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;

public class ChestEspModule extends Module {

    public final NumberOption  range;
    public final BooleanOption showLabel;
    public final BooleanOption stopRenderWithDistance;
    public final NumberOption  stopRenderDistance;

    private volatile Class<?>  chestClass;
    private volatile Class<?>  worldClass;
    private volatile Field     worldListField;
    private volatile Field     tePosField;
    private volatile Method[]  posGetters;

    private volatile boolean discoveryDone;
    private volatile int     discoveryAttempts;
    private volatile long    lastDiscoveryMs;
    private static final int MAX_DISCOVERY_ATTEMPTS = 200;

    private volatile List<ChestHit> cachedHits = java.util.Collections.emptyList();
    private volatile long lastRefreshMs;
    private volatile long lastDiagMs;
    private static final long REFRESH_INTERVAL_MS = 500L;

    public ChestEspModule() {
        super("ChestESP", "Show nearby chests as boxes", Category.RENDER, 0);
        range                  = new NumberOption ("Range",     64.0D, 8.0D, 256.0D, 8.0D, this);
        showLabel              = new BooleanOption("Show Label", true, this);
        stopRenderWithDistance = new BooleanOption("Stop Render With Distance", false, this);
        stopRenderDistance     = new NumberOption ("Stop Render Distance", 5.0D, 1.0D, 50.0D, 1.0D, this);
        stopRenderDistance.setDependency("Stop Render With Distance:true");
        addOptions(range, showLabel, stopRenderWithDistance, stopRenderDistance);
    }

    @Override public void onEnable() {
        Logger.info("ChestESP enabled");
        cachedHits = java.util.Collections.emptyList();
    }
    @Override public void onDisable() {
        Logger.info("ChestESP disabled");
        cachedHits = java.util.Collections.emptyList();
    }

    @Override
    public void onUpdate() {
        long now = System.currentTimeMillis();

        Object curWorld = getTheWorld();
        if (discoveryDone && curWorld != null && worldClass != null && !worldClass.isInstance(curWorld)) {
            resetDiscovery();
        }

        if (!discoveryDone && discoveryAttempts < MAX_DISCOVERY_ATTEMPTS && now - lastDiscoveryMs > 500L) {
            lastDiscoveryMs = now;
            discoveryAttempts++;
            try { discover(); }
            catch (Throwable t) { Logger.warn("[ChestESP] discover: " + t.getMessage()); }
        }

        if (discoveryDone && now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            lastRefreshMs = now;
            try { cachedHits = collect(); }
            catch (Throwable t) { Logger.warn("[ChestESP] collect: " + t.getMessage()); }
        }

    }

    private void resetDiscovery() {
        Logger.info("[ChestESP] world changed, reset discovery");
        discoveryDone = false;
        discoveryAttempts = 0;
        chestClass = null;
        worldClass = null;
        worldListField = null;
        tePosField = null;
        posGetters = null;
        cachedHits = java.util.Collections.emptyList();
    }

    public List<ChestHit> collectChests() {
        return cachedHits;
    }

    private void discover() {
        Object world = getTheWorld();
        if (world == null) return;
        Object player = getThePlayer();
        if (player == null) return;

        double px = readDouble(player, "posX");
        double py = readDouble(player, "posY");
        double pz = readDouble(player, "posZ");

        for (Class<?> wc = world.getClass(); wc != null && wc != Object.class; wc = wc.getSuperclass()) {
            for (Field lf : wc.getDeclaredFields()) {
                if (Modifier.isStatic(lf.getModifiers())) continue;
                if (!List.class.isAssignableFrom(lf.getType())) continue;
                lf.setAccessible(true);
                Object v;
                try { v = lf.get(world); } catch (Throwable t) { continue; }
                if (!(v instanceof List)) continue;
                List<?> lst = (List<?>) v;
                if (lst.isEmpty()) continue;

                Class<?> foundChestCls = null;
                ArrayList<Object> chestSamples = new ArrayList<Object>();
                for (Object el : lst) {
                    if (el == null) continue;
                    if (foundChestCls == null) {
                        if (!isVanillaChestClass(el.getClass(), el)) continue;
                        foundChestCls = el.getClass();
                    }
                    if (el.getClass() == foundChestCls) chestSamples.add(el);
                    if (chestSamples.size() >= 30) break;
                }
                if (foundChestCls == null) continue;

                Field bestPosField = null;
                Method[] bestGetters = null;
                long bestScore = Long.MIN_VALUE;
                String bestPosLog = "";

                for (Class<?> sc = foundChestCls; sc != null && sc != Object.class; sc = sc.getSuperclass()) {
                    for (Field pf : sc.getDeclaredFields()) {
                        if (Modifier.isStatic(pf.getModifiers())) continue;
                        Class<?> t = pf.getType();
                        if (t.isPrimitive() || t.isArray() || t == String.class) continue;
                        if (t.getPackage() != null && t.getPackage().getName().startsWith("java.")) continue;
                        pf.setAccessible(true);

                        Object anyPos = null;
                        for (Object s : chestSamples) {
                            try { Object p = pf.get(s); if (p != null) { anyPos = p; break; } }
                            catch (Throwable ig) {}
                        }
                        if (anyPos == null) continue;

                        Method[] mg = findPosGetters(t, anyPos, px, py, pz);
                        if (mg == null) continue;

                        long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE;
                        long minY = Long.MAX_VALUE, maxY = Long.MIN_VALUE;
                        long minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
                        long sumY = 0;
                        int nonNull = 0;
                        for (Object s : chestSamples) {
                            try {
                                Object p = pf.get(s);
                                if (p == null) continue;
                                int x = ((Number) mg[0].invoke(p)).intValue();
                                int y = ((Number) mg[1].invoke(p)).intValue();
                                int z = ((Number) mg[2].invoke(p)).intValue();
                                if (Math.abs(x) > 30_000_000 || Math.abs(y) > 500 || Math.abs(z) > 30_000_000) continue;
                                nonNull++;
                                sumY += y;
                                if (x < minX) minX = x; if (x > maxX) maxX = x;
                                if (y < minY) minY = y; if (y > maxY) maxY = y;
                                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
                            } catch (Throwable ig) {}
                        }
                        if (nonNull < 1) continue;
                        long variance = (maxX - minX) + (maxY - minY) + (maxZ - minZ);
                        long avgY = sumY / nonNull;
                        long distY = Math.abs(avgY - (long) py);
                        long distX = Math.abs(((minX + maxX) / 2) - (long) px);
                        long distZ = Math.abs(((minZ + maxZ) / 2) - (long) pz);
                        long playerProximity = 1000 - Math.min(1000, distX + distY + distZ);
                        long score = variance * 10 + playerProximity;
                        if (avgY < -30 || avgY > 260) score -= 100000;
                        Logger.info("[ChestESP] posField candidate: " + pf.getName() + ":" + t.getSimpleName()
                                + " nonNull=" + nonNull + "/" + chestSamples.size()
                                + " variance=" + variance + " proximity=" + playerProximity + " score=" + score
                                + " range=[x:" + minX + "~" + maxX + ",y:" + minY + "~" + maxY + ",z:" + minZ + "~" + maxZ + "]");
                        if (score > bestScore) {
                            bestScore = score;
                            bestPosField = pf;
                            bestGetters = mg;
                            bestPosLog = pf.getName() + ":" + t.getSimpleName() + " score=" + score;
                        }
                    }
                }
                if (bestPosField == null) continue;

                chestClass = foundChestCls;
                worldClass = world.getClass();
                worldListField = lf;
                tePosField = bestPosField;
                posGetters = bestGetters;
                discoveryDone = true;
                Logger.info("[ChestESP] DISCOVERED chest=" + foundChestCls.getSimpleName()
                        + " list=" + lf.getName()
                        + " samples=" + chestSamples.size()
                        + " picked=" + bestPosLog
                        + " (attempt=" + discoveryAttempts + ")");
                return;
            }
        }
    }

    private static boolean isVanillaChestClass(Class<?> cls, Object sample) {
        int selfCount = 0;
        java.util.ArrayList<Field> objArrays = new java.util.ArrayList<Field>();
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == cls) selfCount++;
            else if (t.isArray() && !t.getComponentType().isPrimitive()) objArrays.add(f);
        }
        if (selfCount < 4) return false;
        for (Field arr : objArrays) {
            try {
                arr.setAccessible(true);
                Object a = arr.get(sample);
                if (a != null && Array.getLength(a) == 27) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Method[] findPosGetters(Class<?> posType, Object samplePos, double px, double py, double pz) {
        java.util.ArrayList<Method> methods = new java.util.ArrayList<Method>();
        for (Class<?> c = posType; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                methods.add(m);
                if (methods.size() >= 8) break;
            }
            if (methods.size() >= 8) break;
        }
        if (methods.size() < 3) return null;

        int[] vals = new int[methods.size()];
        boolean[] valid = new boolean[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            try {
                Object r = methods.get(i).invoke(samplePos);
                if (r instanceof Number) {
                    vals[i] = ((Number) r).intValue();
                    if (Math.abs(vals[i]) < 30_000_000) valid[i] = true;
                }
            } catch (Throwable ignored) {}
        }

        long[] targets = { (long) px, (long) py, (long) pz };
        int[] picked = { -1, -1, -1 };
        for (int axis = 0; axis < 3; axis++) {
            int bestI = -1;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < methods.size(); i++) {
                if (!valid[i]) continue;
                if (i == picked[0] || i == picked[1] || i == picked[2]) continue;
                long d = Math.abs((long) vals[i] - targets[axis]);
                if (d < bestD) { bestD = d; bestI = i; }
            }
            if (bestI < 0) return null;
            if (bestD > 500) return null;
            picked[axis] = bestI;
        }
        return new Method[] { methods.get(picked[0]), methods.get(picked[1]), methods.get(picked[2]) };
    }

    private List<ChestHit> collect() {
        ArrayList<ChestHit> hits = new ArrayList<ChestHit>();
        if (chestClass == null || worldListField == null) return hits;

        int total = 0, matched = 0, posNull = 0, outOfRange = 0;
        double px = 0, py = 0, pz = 0;
        double firstX = Double.NaN, firstY = Double.NaN, firstZ = Double.NaN;

        try {
            Object world = getTheWorld();
            if (world == null || !worldClass.isInstance(world)) return hits;
            Object listObj = worldListField.get(world);
            if (!(listObj instanceof List)) return hits;
            List<?> lst = (List<?>) listObj;

            Object player = getThePlayer();
            if (player == null) return hits;
            px = readDouble(player, "posX");
            py = readDouble(player, "posY");
            pz = readDouble(player, "posZ");
            double rSq = range.getValue() * range.getValue();
            boolean cull = stopRenderWithDistance.getValue();
            double cullSq = cull ? stopRenderDistance.getValue() * stopRenderDistance.getValue() : 0.0D;

            for (Object te : lst) {
                if (te == null) continue;
                total++;
                if (!chestClass.isInstance(te)) continue;
                matched++;
                Object posObj = tePosField.get(te);
                if (posObj == null) { posNull++; continue; }
                int x = ((Number) posGetters[0].invoke(posObj)).intValue();
                int y = ((Number) posGetters[1].invoke(posObj)).intValue();
                int z = ((Number) posGetters[2].invoke(posObj)).intValue();
                if (Double.isNaN(firstX)) { firstX = x; firstY = y; firstZ = z; }
                double dx = x + 0.5 - px;
                double dy = y + 0.5 - py;
                double dz = z + 0.5 - pz;
                double dsq = dx * dx + dy * dy + dz * dz;
                if (dsq > rSq) { outOfRange++; continue; }
                if (cull && dsq < cullSq) continue;
                hits.add(new ChestHit(ChestType.CHEST, x, y, z, Math.sqrt(dsq)));
            }
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        if (now - lastDiagMs > 3000L) {
            lastDiagMs = now;
            ArrayList<ChestHit> sorted = new ArrayList<ChestHit>(hits);
            java.util.Collections.sort(sorted, new java.util.Comparator<ChestHit>() {
                public int compare(ChestHit a, ChestHit b) { return Double.compare(a.distance, b.distance); }
            });
            StringBuilder near = new StringBuilder();
            for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                ChestHit h = sorted.get(i);
                if (i > 0) near.append(" | ");
                near.append("(").append((int)h.x).append(",").append((int)h.y).append(",").append((int)h.z).append(")d=").append(String.format(java.util.Locale.ROOT, "%.1f", h.distance));
            }
            Logger.info("[ChestESP] collect: total=" + total + " matched=" + matched
                    + " posNull=" + posNull + " outOfRange=" + outOfRange + " hits=" + hits.size()
                    + " r=" + range.getValue().intValue()
                    + " plr=(" + fmt(px) + "," + fmt(py) + "," + fmt(pz) + ")"
                    + " near10=[" + near + "]");
        }
        return hits;
    }

    private double readDouble(Object entity, String vanillaField) {
        try {
            Field f = me.kiwii.util.MappingUtils.getField("Entity." + vanillaField);
            if (f != null) { f.setAccessible(true); return f.getDouble(entity); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    public enum ChestType { CHEST }

    public static final class ChestHit {
        public final ChestType type;
        public final double x, y, z;
        public final double distance;
        public ChestHit(ChestType type, double x, double y, double z, double distance) {
            this.type = type; this.x = x; this.y = y; this.z = z; this.distance = distance;
        }
    }
}
