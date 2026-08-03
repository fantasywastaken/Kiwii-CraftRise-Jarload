package me.kiwii.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class SafeWalkModule extends Module {

    private Method   cachedGetChunkFromCoords;
    private Method   cachedChunkGetBlock;
    private Method   cachedGetIdFromBlock;
    private Object   cachedAirBlockObj;
    private Field    cachedOnGroundField;
    private Field    cachedMotionX;
    private Field    cachedMotionY;
    private Field    cachedMotionZ;
    private Object   cachedSneakBinding;
    private Field    cachedPressedField;
    private volatile boolean discoveryDone;
    private volatile boolean sneakingSet;
    private volatile int lastLoggedCorners = -1;
    private volatile long lastDiagMs;

    public SafeWalkModule() {
        super("SafeWalk", "Stop at block edges — never fall off", Category.MOVEMENT, 0);
    }

    @Override public void onEnable()  {
        Logger.info("SafeWalk enabled");
        discoveryDone = false; sneakingSet = false;
    }
    @Override public void onDisable() {
        Logger.info("SafeWalk disabled");
        setSneakPressed(false); sneakingSet = false;
    }

    @Override
    public void onUpdate() {
        try {
            Object world = getTheWorld();
            Object player = MinecraftMapper.getPlayer();
            if (world == null || player == null) return;
            if (!discoveryDone) { discover(world, player); discoveryDone = true; }
            if (cachedChunkGetBlock == null || cachedGetChunkFromCoords == null) return;

            double px  = readDouble(player, "Entity.posX");
            double py  = readDouble(player, "Entity.posY");
            double pz  = readDouble(player, "Entity.posZ");
            double ppx = readDouble(player, "Entity.prevPosX");
            double ppy = readDouble(player, "Entity.prevPosY");
            double ppz = readDouble(player, "Entity.prevPosZ");
            double dx  = px - ppx;
            double dy  = py - ppy;
            double dz  = pz - ppz;

            double frac = py - Math.floor(py);
            boolean airborne = frac > 0.02 || Math.abs(dy) > 0.02;
            if (airborne) {
                if (sneakingSet) { setSneakPressed(false); sneakingSet = false; }
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastDiagMs > 2000L) {
                    lastDiagMs = nowMs;
                    Logger.info("[SafeWalk] airborne frac=" + String.format(java.util.Locale.ROOT, "%.3f", frac)
                            + " dy=" + String.format(java.util.Locale.ROOT, "%.3f", dy)
                            + " sneakSet=false");
                }
                return;
            }

            int by = (int) Math.floor(py) - 1;
            double HALF_WIDTH = 0.3D;
            int minBX = (int) Math.floor(px - HALF_WIDTH);
            int maxBX = (int) Math.floor(px + HALF_WIDTH);
            int minBZ = (int) Math.floor(pz - HALF_WIDTH);
            int maxBZ = (int) Math.floor(pz + HALF_WIDTH);

            int totalBlocks = 0, airBlocks = 0;
            for (int bx = minBX; bx <= maxBX; bx++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    totalBlocks++;
                    if (isAirAt(world, bx + 0.5D, by, bz + 0.5D)) airBlocks++;
                }
            }
            boolean trigger = airBlocks > 0;

            if (trigger) {
                if (!sneakingSet) { setSneakPressed(true); sneakingSet = true; }
            } else {
                if (sneakingSet) { setSneakPressed(false); sneakingSet = false; }
            }

            long now = System.currentTimeMillis();
            boolean changed = trigger != (lastLoggedCorners == 1);
            if (changed || (now - lastDiagMs > 2000L)) {
                lastLoggedCorners = trigger ? 1 : 0;
                lastDiagMs = now;
                Logger.info("[SafeWalk] airBlocks=" + airBlocks + "/" + totalBlocks
                        + " trigger=" + trigger
                        + " frac=" + String.format(java.util.Locale.ROOT, "%.3f", frac)
                        + " delta=(" + String.format(java.util.Locale.ROOT, "%.3f,%.3f", dx, dz) + ")"
                        + " sneakSet=" + sneakingSet);
            }

        } catch (Throwable t) {
            Logger.warn("[SafeWalk] onUpdate exc: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private int countAirCorners(Object world, double cx, int by, double cz, double halfW) {
        int air = 0;
        for (double dx = -halfW; dx <= halfW + 0.001; dx += halfW * 2) {
            for (double dz = -halfW; dz <= halfW + 0.001; dz += halfW * 2) {
                if (isAirAt(world, cx + dx, by, cz + dz)) air++;
            }
        }
        return air;
    }

    private boolean isAirAt(Object world, double x, int y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Object block = blockAt(world, bx, y, bz);
        if (block == null) return true;
        if (cachedGetIdFromBlock != null) {
            try {
                Object r = cachedGetIdFromBlock.invoke(null, block);
                if (r instanceof Number) return ((Number) r).intValue() == 0;
            } catch (Throwable ignored) {}
        }
        if (cachedAirBlockObj != null) return block == cachedAirBlockObj;
        return false;
    }

    private Object blockAt(Object world, int bx, int by, int bz) {
        try {
            Object chunk = cachedGetChunkFromCoords.invoke(world, bx >> 4, bz >> 4);
            if (chunk == null) return null;
            return cachedChunkGetBlock.invoke(chunk, bx & 15, by, bz & 15);
        } catch (Throwable ignored) { return null; }
    }

    private double readDouble(Object entity, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(entity); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private double readDoubleField(Object entity, Field f) {
        try { return f.getDouble(entity); } catch (Throwable ignored) { return 0.0; }
    }

    private void writeDoubleField(Object entity, Field f, double val) {
        try { f.setDouble(entity, val); } catch (Throwable ignored) {}
    }

    private boolean readOnGround(Object player) {
        if (cachedOnGroundField == null) return true;
        try { return cachedOnGroundField.getBoolean(player); } catch (Throwable ignored) { return true; }
    }

    private void setSneakPressed(boolean value) {
        if (cachedSneakBinding == null || cachedPressedField == null) return;
        try { cachedPressedField.setBoolean(cachedSneakBinding, value); }
        catch (Throwable ignored) {}
    }

    private void discover(Object world, Object player) {
        cachedGetChunkFromCoords = MappingUtils.getMethod("World.getChunkFromChunkCoords");
        cachedChunkGetBlock       = MappingUtils.getMethod("Chunk.getBlock");
        if (cachedGetChunkFromCoords != null) cachedGetChunkFromCoords.setAccessible(true);
        if (cachedChunkGetBlock != null) cachedChunkGetBlock.setAccessible(true);

        try {
            Field fx = MappingUtils.getField("Entity.motionX");
            Field fy = MappingUtils.getField("Entity.motionY");
            Field fz = MappingUtils.getField("Entity.motionZ");
            if (fx != null) { fx.setAccessible(true); cachedMotionX = fx; }
            if (fy != null) { fy.setAccessible(true); cachedMotionY = fy; }
            if (fz != null) { fz.setAccessible(true); cachedMotionZ = fz; }
        } catch (Throwable ignored) {}

        if (cachedMotionX == null || cachedMotionZ == null) {
            try {
                java.util.ArrayList<Field> doubles = new java.util.ArrayList<Field>();
                for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != double.class) continue;
                        f.setAccessible(true);
                        doubles.add(f);
                    }
                }
                Field[] arr = doubles.toArray(new Field[0]);
                for (int i = 0; i + 2 < arr.length; i++) {
                    double a = arr[i].getDouble(player);
                    double b = arr[i + 1].getDouble(player);
                    double c = arr[i + 2].getDouble(player);
                    if (Math.abs(a) < 5 && Math.abs(b) < 5 && Math.abs(c) < 5
                            && !(a == 0 && b == 0 && c == 0)) {
                        if (arr[i].getName().toLowerCase().contains("motion") ||
                            arr[i + 1].getName().toLowerCase().contains("motion") ||
                            arr[i + 2].getName().toLowerCase().contains("motion")) {
                            cachedMotionX = arr[i]; cachedMotionY = arr[i + 1]; cachedMotionZ = arr[i + 2];
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            int probeY = (int) readDouble(player, "Entity.posY") + 20;
            Object airProbe = blockAt(world, (int) readDouble(player, "Entity.posX"), probeY,
                                             (int) readDouble(player, "Entity.posZ"));
            if (airProbe == null) {
                airProbe = blockAt(world, (int) readDouble(player, "Entity.posX"), 200,
                                          (int) readDouble(player, "Entity.posZ"));
            }
            if (airProbe != null) {
                cachedAirBlockObj = airProbe;
                Class<?> blockCls = airProbe.getClass();
                for (Class<?> bc = blockCls; bc != null && bc != Object.class; bc = bc.getSuperclass()) {
                    for (Method mm : bc.getDeclaredMethods()) {
                        if (!Modifier.isStatic(mm.getModifiers())) continue;
                        if (mm.getReturnType() != int.class) continue;
                        Class<?>[] pt = mm.getParameterTypes();
                        if (pt.length != 1) continue;
                        if (!pt[0].isInstance(airProbe)) continue;
                        mm.setAccessible(true);
                        try {
                            Object r = mm.invoke(null, airProbe);
                            if (r instanceof Number && ((Number) r).intValue() == 0) {
                                cachedGetIdFromBlock = mm; break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (cachedGetIdFromBlock != null) break;
                }
            }
        } catch (Throwable ignored) {}

        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != boolean.class) continue;
                f.setAccessible(true);
                try { if (f.getBoolean(player)) { cachedOnGroundField = f; break; } }
                catch (Throwable ignored) {}
            }
            if (cachedOnGroundField != null) break;
        }

        try {
            Object mc = MinecraftMapper.getMinecraft();
            Class<?> gsCls = MappingUtils.get("GameSettings");
            Object gs = null;
            if (mc != null && gsCls != null) {
                for (Class<?> mcc = mc.getClass(); mcc != null && mcc != Object.class && gs == null; mcc = mcc.getSuperclass()) {
                    for (Field f : mcc.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() == gsCls) { f.setAccessible(true); gs = f.get(mc); break; }
                    }
                }
            }
            if (gs != null) {
                Field kbSneak = MappingUtils.getField("GameSettings.keyBindSneak");
                if (kbSneak != null) {
                    kbSneak.setAccessible(true);
                    cachedSneakBinding = kbSneak.get(gs);
                }
                if (cachedSneakBinding == null) {
                    Class<?> kbCls = MappingUtils.get("KeyBinding");
                    Field kcField = MappingUtils.getField("KeyBinding.keyCode");
                    if (kbCls != null && kcField != null) {
                        kcField.setAccessible(true);
                        for (Class<?> gsC = gs.getClass(); gsC != null && gsC != Object.class && cachedSneakBinding == null; gsC = gsC.getSuperclass()) {
                            for (Field f : gsC.getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers())) continue;
                                if (f.getType() != kbCls) continue;
                                f.setAccessible(true);
                                Object kb = f.get(gs);
                                if (kb == null) continue;
                                try {
                                    int kc = kcField.getInt(kb);
                                    if (kc == 42) { cachedSneakBinding = kb; Logger.info("[SafeWalk] sneak keybind via keyCode=42 on " + f.getName()); break; }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                if (cachedSneakBinding == null) {
                    Class<?> kbCls = MappingUtils.get("KeyBinding");
                    if (kbCls != null) {
                        for (Class<?> gsC = gs.getClass(); gsC != null && gsC != Object.class && cachedSneakBinding == null; gsC = gsC.getSuperclass()) {
                            for (Field f : gsC.getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers())) continue;
                                if (f.getType() != kbCls) continue;
                                if (!f.getName().toLowerCase().contains("sneak")) continue;
                                f.setAccessible(true);
                                Object kb = f.get(gs);
                                if (kb != null) {
                                    cachedSneakBinding = kb;
                                    Logger.info("[SafeWalk] sneak keybind via field-name on " + f.getName());
                                    break;
                                }
                            }
                        }
                    }
                }
                if (cachedSneakBinding == null) Logger.warn("[SafeWalk] no sneak keybind found");
            }
            Field pressedF = MappingUtils.getField("KeyBinding.pressed");
            if (pressedF != null) { pressedF.setAccessible(true); cachedPressedField = pressedF; }
            if (cachedPressedField == null && cachedSneakBinding != null) {
                for (Class<?> c = cachedSneakBinding.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != boolean.class) continue;
                        if (!f.getName().toLowerCase().contains("press")) continue;
                        f.setAccessible(true);
                        cachedPressedField = f;
                        Logger.info("[SafeWalk] pressed field via name on " + f.getName());
                        break;
                    }
                    if (cachedPressedField != null) break;
                }
            }
            if (cachedPressedField == null) Logger.warn("[SafeWalk] KeyBinding.pressed field NULL");
        } catch (Throwable t) { Logger.warn("[SafeWalk] keybind discovery: " + t.getMessage()); }

        Logger.info("[SafeWalk] discovery: getChunk=" + (cachedGetChunkFromCoords != null ? "OK" : "NULL")
                + " getBlock=" + (cachedChunkGetBlock != null ? "OK" : "NULL")
                + " getIdFromBlock=" + (cachedGetIdFromBlock != null ? cachedGetIdFromBlock.getName() : "NULL")
                + " air=" + (cachedAirBlockObj != null ? "OK" : "NULL")
                + " onGround=" + (cachedOnGroundField != null ? cachedOnGroundField.getName() : "NULL")
                + " motionXYZ=" + (cachedMotionX != null && cachedMotionZ != null ? "OK" : "NULL")
                + " sneakBind=" + (cachedSneakBinding != null ? "OK" : "NULL")
                + " pressed=" + (cachedPressedField != null ? cachedPressedField.getName() : "NULL"));
    }
}
