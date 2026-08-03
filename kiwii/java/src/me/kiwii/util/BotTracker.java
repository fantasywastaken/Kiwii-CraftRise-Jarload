package me.kiwii.util;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.util.MappingUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;


public final class BotTracker {


    private static final WeakHashMap<Object, EntityState> entityStates =
            new WeakHashMap<Object, EntityState>();


    private static final Object LOCK = new Object();

    private static long lastCleanup = 0;

    private BotTracker() {}

    private static final class EntityState {
        int lastHurtTime = 0;
        int hurtTimeChanges = 0;
        int observationCount = 0;
        long firstSeenTime = System.currentTimeMillis();
        boolean confirmedReal = false;
        boolean confirmedBot = false;
    }



    
    public static String getUUIDForLog(Object entity) {
        String uuid = getEntityUUID(entity);
        if (uuid == null) return "?";
        if (uuid.startsWith("id_")) return uuid;
        return uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
    }

    
    public static void observeAll() {
        try {
            for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
                observe(entity);
            }
        } catch (Throwable ignored) {}
    }

    
    public static void observe(Object entity) {
        if (entity == null) return;
        synchronized (LOCK) {
            EntityState state = entityStates.get(entity);
            if (state == null) {
                state = new EntityState();
                entityStates.put(entity, state);
            }

            state.observationCount++;

            int ht = getHurtTime(entity);
            if (ht != state.lastHurtTime) {
                if (ht > 0 && !state.confirmedReal) {

                    state.hurtTimeChanges++;
                    state.confirmedReal = true;
                    state.confirmedBot = false;
                    Logger.info("[BotTracker] REAL (hurtTime=" + ht + ") uuid=" + getUUIDForLog(entity));
                }
                state.lastHurtTime = ht;
            }
        }
    }

    
    public static boolean isBot(Object entity) {
        if (entity == null) return false;

        synchronized (LOCK) {
            EntityState state = entityStates.get(entity);
            if (state != null && state.confirmedReal) return false;
            if (state != null && state.confirmedBot)  return true;


            String uuid = getEntityUUID(entity);
            if (uuid != null && !uuid.startsWith("id_")) {
                try {
                    int version = UUID.fromString(uuid).version();
                    if (version == 3) {
                        if (state != null) state.confirmedBot = true;
                        Logger.info("[BotTracker] BOT (uuid v3) uuid=" + uuid.substring(0, 8));
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return false;
    }

    
    public static void reset() {
        synchronized (LOCK) {
            entityStates.clear();
        }
    }



    private static String getEntityUUID(Object entity) {
        if (entity == null) return null;
        try {
            try {
                Method m = entity.getClass().getMethod("getUniqueID");
                Object r = m.invoke(entity);
                if (r instanceof UUID) return r.toString();
            } catch (NoSuchMethodException ignored) {}

            try {
                Method gp = entity.getClass().getMethod("getGameProfile");
                Object profile = gp.invoke(entity);
                if (profile != null) {
                    Method getUUID = MappingUtils.getMethod("GameProfile.getUUID");
                    if (getUUID != null) {
                        getUUID.setAccessible(true);
                        Object r = getUUID.invoke(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                    try {
                        Method getId = profile.getClass().getMethod("getId");
                        Object r = getId.invoke(profile);
                        if (r instanceof UUID) return r.toString();
                    } catch (Exception ignored) {}
                    Field uuidField = MappingUtils.getField("GameProfile.uuid");
                    if (uuidField != null) {
                        uuidField.setAccessible(true);
                        Object r = uuidField.get(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                }
            } catch (Exception ignored) {}

            Field gpField = MappingUtils.getField("EntityPlayer.gameProfile");
            if (gpField != null) {
                gpField.setAccessible(true);
                Object profile = gpField.get(entity);
                if (profile != null) {
                    Field uuidField = MappingUtils.getField("GameProfile.uuid");
                    if (uuidField != null) {
                        uuidField.setAccessible(true);
                        Object r = uuidField.get(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                }
            }

            Class<?> clazz = entity.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType().equals(UUID.class)) {
                        f.setAccessible(true);
                        Object r = f.get(entity);
                        if (r instanceof UUID) return r.toString();
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return "id_" + System.identityHashCode(entity);
    }

    private static int getHurtTime(Object entity) {
        try {
            Field f = MappingUtils.getField("EntityLivingBase.hurtTime");
            if (f != null) {
                f.setAccessible(true);
                return f.getInt(entity);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> clazz = entity.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == int.class && f.getName().equals("hurtTime")) {
                        f.setAccessible(true);
                        return f.getInt(entity);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return 0;
    }
}
