package me.kiwii.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;
import org.lwjgl.input.Keyboard;


public class ReachModule extends Module {

    private final me.kiwii.setting.BooleanOption randomize;
    private final NumberOption reach;
    private final NumberOption minReach;
    private final NumberOption maxReach;
    private static final float MIN_TARGET_HP = 1.0f;
    private final java.util.Random rng = new java.util.Random();

    private Class<?> c0aClass = null;

    @SuppressWarnings("rawtypes")
    private Enum cachedAttackEnum = null;

    private Method cachedEntityIdMethod = null;
    private Field  cachedEntityIdField  = null;
    private boolean entityIdChecked     = false;

    private Method cachedDistMethod   = null;
    private boolean distMethodChecked = false;

    private Method cachedCanSeeMethod  = null;
    private boolean canSeeChecked      = false;

    private Method  cachedGetHealth    = null;
    private Method  cachedGetEyeHeight = null;
    private Field   cachedPosX, cachedPosY, cachedPosZ;
    private Field   cachedYaw,  cachedPitch;
    private Method  cachedNativeAttack;
    private Class<?> cachedNativeAttackOwner;
    private boolean warmupDone = false;

    public ReachModule() {
        super("Reach", "Extends attack reach with optional min/max randomization", Category.COMBAT, Keyboard.KEY_NONE);
        randomize = new me.kiwii.setting.BooleanOption("Randomize", true, this);
        reach     = new NumberOption("Reach",     3.50D, 3.10D, 6.0D, 0.05D, this);
        minReach  = new NumberOption("Min Reach", 3.15D, 3.10D, 6.0D, 0.05D, this);
        maxReach  = new NumberOption("Max Reach", 3.50D, 3.10D, 6.0D, 0.05D, this);
        randomize.setGroup("Combat");
        reach.setGroup("Combat");
        minReach.setGroup("Combat");
        maxReach.setGroup("Combat");
        reach.setDependency("Randomize:false");
        minReach.setDependency("Randomize:true");
        maxReach.setDependency("Randomize:true");
        addOptions(randomize, reach, minReach, maxReach);
    }

    @Override
    public String getSuffix() {
        if (randomize.getValue()) {
            return String.format(java.util.Locale.ROOT, "%.1f-%.1f", minReach.getValue(), maxReach.getValue());
        }
        return String.format(java.util.Locale.ROOT, "%.1f", reach.getValue());
    }


    private double rollReach() {
        if (!randomize.getValue()) return reach.getValue();
        double lo = Math.min(minReach.getValue(), maxReach.getValue());
        double hi = Math.max(minReach.getValue(), maxReach.getValue());
        return lo + (hi - lo) * rng.nextDouble();
    }

    private double effectiveMaxReach() {
        if (!randomize.getValue()) return reach.getValue();
        return Math.max(minReach.getValue(), maxReach.getValue());
    }

    @Override
    public void onEnable() {
        clearCache();
        warmupMappings();
        Logger.info("Reach enabled: reach=" + reach.getValue() + " min=" + minReach.getValue() + " max=" + maxReach.getValue() + " randomize=" + randomize.getValue()
                + " (warmup c0a=" + (c0aClass != null)
                + " dist=" + (cachedDistMethod != null)
                + " canSee=" + (cachedCanSeeMethod != null)
                + " getHealth=" + (cachedGetHealth != null)
                + " eyeH=" + (cachedGetEyeHeight != null)
                + " entityId=" + (cachedEntityIdMethod != null || cachedEntityIdField != null)
                + " attackEnum=" + (cachedAttackEnum != null)
                + " posFields=" + (cachedPosX != null && cachedPosY != null && cachedPosZ != null)
                + " yawPitch=" + (cachedYaw != null && cachedPitch != null) + ")");
    }

    @Override
    public void onDisable() {
        clearCache();
        Logger.info("Reach disabled");
    }

    private void clearCache() {
        cachedAttackEnum     = null;
        cachedEntityIdMethod = null;
        cachedEntityIdField  = null;
        entityIdChecked      = false;
        cachedDistMethod     = null;
        distMethodChecked    = false;
        cachedCanSeeMethod   = null;
        canSeeChecked        = false;
        cachedGetHealth      = null;
        cachedGetEyeHeight   = null;
        cachedPosX = cachedPosY = cachedPosZ = null;
        cachedYaw  = cachedPitch = null;
        warmupDone = false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void warmupMappings() {
        if (warmupDone) return;
        warmupDone = true;

        try { c0aClass = MappingUtils.get("C0APacketAnimation"); } catch (Throwable ignored) {}

        try {
            cachedDistMethod = MappingUtils.getMethod("Entity.getDistanceToEntity");
            if (cachedDistMethod != null) cachedDistMethod.setAccessible(true);
            distMethodChecked = true;
        } catch (Throwable ignored) {}

        try {
            cachedCanSeeMethod = MappingUtils.getMethod("EntityLivingBase.canEntityBeSeen");
            if (cachedCanSeeMethod != null) cachedCanSeeMethod.setAccessible(true);
            canSeeChecked = true;
        } catch (Throwable ignored) {}

        try {
            cachedGetHealth = MappingUtils.getMethod("EntityLivingBase.getHealth");
            if (cachedGetHealth != null) cachedGetHealth.setAccessible(true);
        } catch (Throwable ignored) {}

        try {
            cachedGetEyeHeight = MappingUtils.getMethod("Entity.getEyeHeight");
            if (cachedGetEyeHeight != null) cachedGetEyeHeight.setAccessible(true);
        } catch (Throwable ignored) {}

        try {
            cachedEntityIdMethod = MappingUtils.getMethod("Entity.getEntityId");
            if (cachedEntityIdMethod != null) cachedEntityIdMethod.setAccessible(true);
            else {
                cachedEntityIdField = MappingUtils.getField("Entity.entityId");
                if (cachedEntityIdField != null) cachedEntityIdField.setAccessible(true);
            }
            entityIdChecked = true;
        } catch (Throwable ignored) {}

        try {
            Class<?> ac = MappingUtils.get("C02PacketUseEntityAction");
            if (ac != null) {
                try { cachedAttackEnum = Enum.valueOf((Class<Enum>) ac, "ATTACK"); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}


        try {
            Field fx = MappingUtils.getField("Entity.posX");
            Field fy = MappingUtils.getField("Entity.posY");
            Field fz = MappingUtils.getField("Entity.posZ");
            if (fx != null) { fx.setAccessible(true); cachedPosX = fx; }
            if (fy != null) { fy.setAccessible(true); cachedPosY = fy; }
            if (fz != null) { fz.setAccessible(true); cachedPosZ = fz; }
        } catch (Throwable ignored) {}
        try {
            Field yaw   = MappingUtils.getField("Entity.rotationYaw");
            Field pitch = MappingUtils.getField("Entity.rotationPitch");
            if (yaw != null)   { yaw.setAccessible(true);   cachedYaw   = yaw; }
            if (pitch != null) { pitch.setAccessible(true); cachedPitch = pitch; }
        } catch (Throwable ignored) {}


        try {
            Object player = getThePlayer();
            if (player != null) {
                if (cachedPosX != null) cachedPosX.getDouble(player);
                if (cachedGetEyeHeight != null) cachedGetEyeHeight.invoke(player);
                if (cachedGetHealth != null) cachedGetHealth.invoke(player);
                if (cachedDistMethod != null) cachedDistMethod.invoke(player, player);
                if (cachedEntityIdMethod != null) cachedEntityIdMethod.invoke(player);
            }
        } catch (Throwable ignored) {}


        try { MinecraftMapper.ensureC02EntityIdMappings(); }
        catch (Throwable t) { Logger.warn("[Reach] warmup ensureC02EntityIdMappings: " + t.getMessage()); }


        try {
            java.util.List<Object> ents = MinecraftMapper.getPlayerEntitiesInWorld();
            Logger.info("[Reach] warmup getPlayerEntitiesInWorld ready, size=" + (ents == null ? -1 : ents.size()));
        } catch (Throwable t) {
            Logger.warn("[Reach] warmup getPlayerEntitiesInWorld: " + t.getMessage());
        }


    }


    @SuppressWarnings("unused")
    private void discoverNativeAttackMethod() {
        Object player = getThePlayer();
        if (player == null) return;
        Class<?> entityBase = MappingUtils.get("Entity");
        Class<?> playerBase = MappingUtils.get("EntityPlayer");

        Method best = null;
        Class<?> bestOwner = null;
        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {

            if (entityBase != null && c == entityBase) break;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?> pt = m.getParameterTypes()[0];
                if (entityBase != null) {
                    if (!entityBase.isAssignableFrom(pt) && pt != entityBase) continue;
                } else {

                    if (pt.getPackage() == null || !pt.getPackage().getName().startsWith("craftrise")) continue;
                    if (pt.getSimpleName().length() > 8) continue;
                }
                if (playerBase != null && !playerBase.isAssignableFrom(c)) continue;

                m.setAccessible(true);
                best = m;
                bestOwner = c;
                break;
            }
            if (best != null) break;
        }
        cachedNativeAttack = best;
        cachedNativeAttackOwner = bestOwner;
        Logger.info("[Reach] native attack method: "
                + (best != null ? bestOwner.getSimpleName() + "." + best.getName() + "(" + best.getParameterTypes()[0].getSimpleName() + ")" : "NOT FOUND — fallback to raw C02"));
    }

    private boolean tryNativeAttack(Object target) {
        if (cachedNativeAttack == null) return false;
        try {
            Object player = getThePlayer();
            if (player == null) return false;
            cachedNativeAttack.invoke(player, target);
            return true;
        } catch (Throwable t) {
            Logger.warn("[Reach] native attack invoke failed, disabling: " + t.getMessage());
            cachedNativeAttack = null;
            return false;
        }
    }

    @Override
    public void onUpdate() {
        try {
            double lo = minReach.getValue();
            double hi = maxReach.getValue();
            if (lo > hi) {

                minReach.setValue(hi);
            }
        } catch (Throwable ignored) {}
    }



    private long diagC0aSeen, diagTargetFound, diagNoTarget, diagAttacked, diagLastDiagMs;

    public void onPacketSend(Object packet) {
        if (!isEnabled() || packet == null) return;
        if (c0aClass == null) c0aClass = MappingUtils.get("C0APacketAnimation");
        if (c0aClass == null || !c0aClass.isInstance(packet)) return;

        diagC0aSeen++;

        double effMax = rollReach();
        Object target = findCrosshairTarget(effMax);
        if (target == null) {
            diagNoTarget++;
            maybeLogDiag(effMax);
            return;
        }
        diagTargetFound++;


        boolean ok = MinecraftMapper.attackEntity(target);
        if (ok) diagAttacked++;
        else Logger.warn("[Reach] attackEntity returned false — target=" + target.getClass().getSimpleName());
        maybeLogDiag(effMax);
    }

    private void maybeLogDiag(double effMax) {
        long now = System.currentTimeMillis();
        if (now - diagLastDiagMs < 2000L) return;
        diagLastDiagMs = now;
        Logger.info("[Reach DIAG] c0aSeen=" + diagC0aSeen
                + " targetFound=" + diagTargetFound
                + " noTarget=" + diagNoTarget
                + " attacked=" + diagAttacked
                + " effMax=" + String.format("%.2f", effMax));
        diagC0aSeen = diagTargetFound = diagNoTarget = diagAttacked = 0;
    }

    private Object cachedNetHandler;
    private Method cachedSendPacketMethod;

    private Field[] cachedWorldEntityFields;
    private Class<?> cachedWorldEntitiesFieldOwner;

    private long lastIterDiagMs;
    private long iterCalls, iterNoWorld, iterCacheHit, iterFreshDiscover, iterNoPlayer, iterEmptyResult;

    private java.util.List<?> iteratePlayerEntities() {
        iterCalls++;
        long now = System.currentTimeMillis();
        boolean shouldLog = (now - lastIterDiagMs > 2000L);
        if (shouldLog) lastIterDiagMs = now;

        try {
            Object world = getTheWorld();
            if (world == null) {
                iterNoWorld++;
                if (shouldLog) Logger.info("[Reach iter DIAG] calls=" + iterCalls + " noWorld=" + iterNoWorld
                        + " noPlayer=" + iterNoPlayer + " cacheHit=" + iterCacheHit
                        + " freshDisc=" + iterFreshDiscover + " emptyResult=" + iterEmptyResult);
                return java.util.Collections.emptyList();
            }


            if (cachedWorldEntityFields == null || cachedWorldEntitiesFieldOwner != world.getClass()) {
                java.util.ArrayList<Field> found = new java.util.ArrayList<Field>();
                for (Class<?> c = world.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        try { f.setAccessible(true); found.add(f); } catch (Throwable ignored) {}
                    }
                }
                cachedWorldEntityFields = found.toArray(new Field[0]);
                cachedWorldEntitiesFieldOwner = world.getClass();
                iterFreshDiscover++;
                Logger.info("[Reach] cached " + cachedWorldEntityFields.length
                        + " List<?> fields on world class " + world.getClass().getSimpleName());
            }


            java.util.ArrayList<Object> union = new java.util.ArrayList<Object>();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<Object, Boolean>();
            Object thePlayer = getThePlayer();
            Class<?> playerCls = thePlayer != null ? thePlayer.getClass() : null;
            if (playerCls == null) iterNoPlayer++;


            java.util.HashSet<Class<?>> playerAncestors = new java.util.HashSet<Class<?>>();
            if (playerCls != null) {
                for (Class<?> c = playerCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    playerAncestors.add(c);
                }
            }

            int totalNonNull = 0;
            int totalPlayerLike = 0;
            for (Field f : cachedWorldEntityFields) {
                try {
                    Object v = f.get(world);
                    if (!(v instanceof java.util.List)) continue;
                    for (Object e : (java.util.List<?>) v) {
                        if (e == null) continue;
                        if (seen.containsKey(e)) continue;
                        seen.put(e, Boolean.TRUE);
                        totalNonNull++;

                        boolean playerLike = false;
                        for (Class<?> ec = e.getClass(); ec != null && ec != Object.class; ec = ec.getSuperclass()) {
                            if (playerAncestors.contains(ec)) { playerLike = true; break; }
                        }
                        if (playerLike) totalPlayerLike++;
                        union.add(e);
                    }
                } catch (Throwable ignored) {}
            }
            iterCacheHit++;
            if (shouldLog) Logger.info("[Reach iter DIAG] calls=" + iterCalls
                    + " cacheHit=" + iterCacheHit + " freshDisc=" + iterFreshDiscover
                    + " unionSize=" + union.size() + " playerLike=" + totalPlayerLike
                    + " totalNonNull=" + totalNonNull
                    + " fields=" + cachedWorldEntityFields.length);
            return union;
        } catch (Throwable t) {
            Logger.warn("[Reach iter] threw: " + t.getMessage());
        }
        return java.util.Collections.emptyList();
    }

    private void sendSilentLookAt(Object target) {
        try {
            Object player = getThePlayer();
            if (player == null || target == null) return;

            double px = dbl(player, "Entity.posX");
            double py = dbl(player, "Entity.posY") + getEyeHeight(player);
            double pz = dbl(player, "Entity.posZ");
            double tx = dbl(target, "Entity.posX");
            double ty = dbl(target, "Entity.posY") + getEyeHeight(target);
            double tz = dbl(target, "Entity.posZ");

            double dx = tx - px, dy = ty - py, dz = tz - pz;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw   = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

            Class<?> c05 = MappingUtils.get("C05PacketPlayerLook");
            if (c05 == null) return;
            boolean onGround = true;
            try {
                Field og = MappingUtils.getField("Entity.onGround");
                if (og != null) { og.setAccessible(true); onGround = og.getBoolean(player); }
            } catch (Throwable ignored) {}
            Object pkt = c05.getConstructor(float.class, float.class, boolean.class)
                    .newInstance(yaw, pitch, onGround);


            sendPacketViaNetHandler(pkt);
        } catch (Throwable ignored) {}
    }

    private void sendPacketViaNetHandler(Object pkt) {
        try {
            if (cachedSendPacketMethod == null) {
                cachedSendPacketMethod = MappingUtils.getMethod("NetHandlerPlayClient.sendPacket");
                if (cachedSendPacketMethod != null) cachedSendPacketMethod.setAccessible(true);
            }
            if (cachedNetHandler == null) {
                Object player = getThePlayer();
                if (player != null) {
                    for (Method m : player.getClass().getMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        String rn = m.getReturnType().getName().toLowerCase();
                        if (rn.contains("nethandler") || rn.contains("networkmanager") || rn.contains("nethandlerplay")) {
                            try { cachedNetHandler = m.invoke(player); if (cachedNetHandler != null) break; }
                            catch (Throwable ignored) {}
                        }
                    }
                }

                if (cachedNetHandler == null) {
                    Class<?> nhClass = MappingUtils.get("NetHandlerPlayClient");
                    if (nhClass != null) {
                        Object pl = getThePlayer();
                        for (Class<?> c = pl != null ? pl.getClass() : null; c != null && c != Object.class; c = c.getSuperclass()) {
                            for (Field f : c.getDeclaredFields()) {
                                if (nhClass.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    Object v = f.get(pl);
                                    if (v != null) { cachedNetHandler = v; break; }
                                }
                            }
                            if (cachedNetHandler != null) break;
                        }
                    }
                }
            }
            if (cachedSendPacketMethod != null && cachedNetHandler != null) {
                cachedSendPacketMethod.invoke(cachedNetHandler, pkt);
            }
        } catch (Throwable ignored) {}
    }



    private Object findCrosshairTarget(double maxRange) {
        Object player = getThePlayer();
        if (player == null) return null;

        double px = dbl(player, "Entity.posX");
        double py = dbl(player, "Entity.posY") + getEyeHeight(player);
        double pz = dbl(player, "Entity.posZ");

        float yaw   = getFloat(player, "Entity.rotationYaw");
        float pitch = getFloat(player, "Entity.rotationPitch");


        double yawRad   = Math.toRadians(yaw + 90.0);
        double pitchRad = Math.toRadians(-pitch);
        double lx = Math.cos(pitchRad) * Math.cos(yawRad);
        double ly = Math.sin(pitchRad);
        double lz = Math.cos(pitchRad) * Math.sin(yawRad);

        Object best    = null;
        double bestDot = 0.93;
        int considered = 0, distSkipped = 0, invalidSkipped = 0, fovSkipped = 0;

        try {
            for (Object entity : iteratePlayerEntities()) {
                considered++;
                if (!isValidTarget(entity)) { invalidSkipped++; continue; }

                double dist = fastDist(player, entity);
                if (dist > maxRange || dist < 0.1) { distSkipped++; continue; }

                double tx = dbl(entity, "Entity.posX");
                double ty = dbl(entity, "Entity.posY") + getEyeHeight(entity);
                double tz = dbl(entity, "Entity.posZ");

                double dx = tx - px, dy = ty - py, dz = tz - pz;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.001) continue;

                double dot = (dx * lx + dy * ly + dz * lz) / len;
                if (dot <= bestDot) { fovSkipped++; continue; }

                if (!canSee(player, entity)) continue;

                bestDot = dot;
                best    = entity;
            }
        } catch (Throwable ignored) {}

        if (best == null && considered > 0) {

            long now = System.currentTimeMillis();
            if (now - lastNoTargetLogMs > 3000L) {
                lastNoTargetLogMs = now;
                Logger.info("[Reach] findCrosshairTarget: considered=" + considered
                        + " invalidSkipped=" + invalidSkipped
                        + " distSkipped=" + distSkipped
                        + " fovSkipped=" + fovSkipped
                        + " maxRange=" + String.format("%.2f", maxRange));
            }
        }
        return best;
    }

    private long lastNoTargetLogMs;

    private boolean isValidTarget(Object entity) {
        if (entity == null) return false;
        try {
            if (entity == getThePlayer()) return false;

            try {
                Method flag = MappingUtils.getMethod("Entity.getFlag");
                if (flag != null) {
                    Object inv = flag.invoke(entity, Integer.valueOf(5));
                    if (inv instanceof Boolean && (Boolean) inv) return false;
                }
            } catch (Throwable ignored) {}

            if (cachedGetHealth != null) {
                try {
                    Object h = cachedGetHealth.invoke(entity);
                    if (h instanceof Number) {
                        float hp = ((Number) h).floatValue();
                        if (hp <= 0f) return false;
                        if (hp <= MIN_TARGET_HP) return false;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private boolean canSee(Object player, Object entity) {
        if (!canSeeChecked) {
            canSeeChecked = true;
            cachedCanSeeMethod = MappingUtils.getMethod("EntityLivingBase.canEntityBeSeen");
            if (cachedCanSeeMethod != null) cachedCanSeeMethod.setAccessible(true);
        }
        if (cachedCanSeeMethod != null) {
            try {
                Object result = cachedCanSeeMethod.invoke(player, entity);
                if (result instanceof Boolean) return (Boolean) result;
            } catch (Throwable ignored) {}
        }

        return rayHasClearPath(player, entity);
    }


    private Method cachedGetChunkFromCoords;
    private Method cachedChunkGetBlock;
    private Class<?> cachedAirBlockClass;
    private boolean walltraceInitTried;

    private boolean rayHasClearPath(Object player, Object entity) {
        try {
            if (!walltraceInitTried) {
                walltraceInitTried = true;
                cachedGetChunkFromCoords = MappingUtils.getMethod("World.getChunkFromChunkCoords");
                cachedChunkGetBlock       = MappingUtils.getMethod("Chunk.getBlock");
                if (cachedGetChunkFromCoords != null) cachedGetChunkFromCoords.setAccessible(true);
                if (cachedChunkGetBlock != null) cachedChunkGetBlock.setAccessible(true);
            }
            if (cachedGetChunkFromCoords == null || cachedChunkGetBlock == null) return true;

            Object world = getTheWorld();
            if (world == null) return true;


            if (cachedAirBlockClass == null) {
                Object airBlock = blockAt(world, (int) dbl(player, "Entity.posX"),
                        260, (int) dbl(player, "Entity.posZ"));
                if (airBlock != null) cachedAirBlockClass = airBlock.getClass();
                if (cachedAirBlockClass == null) return true;
            }

            double px = dbl(player, "Entity.posX");
            double py = dbl(player, "Entity.posY") + getEyeHeight(player);
            double pz = dbl(player, "Entity.posZ");
            double tx = dbl(entity, "Entity.posX");
            double ty = dbl(entity, "Entity.posY") + getEyeHeight(entity);
            double tz = dbl(entity, "Entity.posZ");

            double dx = tx - px, dy = ty - py, dz = tz - pz;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 0.001) return true;

            int steps = Math.max(3, (int) (length / 0.25));
            double stepX = dx / steps, stepY = dy / steps, stepZ = dz / steps;


            for (int i = 1; i < steps; i++) {
                double x = px + stepX * i;
                double y = py + stepY * i;
                double z = pz + stepZ * i;
                Object block = blockAt(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (block == null) continue;
                if (!cachedAirBlockClass.equals(block.getClass())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private Object blockAt(Object world, int bx, int by, int bz) {
        try {
            Object chunk = cachedGetChunkFromCoords.invoke(world, bx >> 4, bz >> 4);
            if (chunk == null) return null;
            return cachedChunkGetBlock.invoke(chunk, bx & 15, by, bz & 15);
        } catch (Throwable ignored) { return null; }
    }






    private double fastDist(Object player, Object entity) {
        if (!distMethodChecked) {
            distMethodChecked = true;
            cachedDistMethod = MappingUtils.getMethod("Entity.getDistanceToEntity");
            if (cachedDistMethod != null) cachedDistMethod.setAccessible(true);
        }
        if (cachedDistMethod != null) {
            try { return ((Number) cachedDistMethod.invoke(player, entity)).doubleValue(); }
            catch (Throwable ignored) {}
        }
        double dx = dbl(player, "Entity.posX") - dbl(entity, "Entity.posX");
        double dy = dbl(player, "Entity.posY") - dbl(entity, "Entity.posY");
        double dz = dbl(player, "Entity.posZ") - dbl(entity, "Entity.posZ");
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double dbl(Object e, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(e); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private float getFloat(Object e, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getFloat(e); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private double getEyeHeight(Object entity) {
        try {
            Method m = MappingUtils.getMethod("Entity.getEyeHeight");
            if (m != null) { m.setAccessible(true); return ((Number) m.invoke(entity)).doubleValue(); }
        } catch (Throwable ignored) {}
        return 1.62;
    }

    private int getEntityIdUnused(Object entity) {
        if (!entityIdChecked) {
            entityIdChecked = true;
            cachedEntityIdMethod = MappingUtils.getMethod("Entity.getEntityId");
            if (cachedEntityIdMethod != null) cachedEntityIdMethod.setAccessible(true);
            else {
                cachedEntityIdField = MappingUtils.getField("Entity.entityId");
                if (cachedEntityIdField != null) cachedEntityIdField.setAccessible(true);
            }
        }
        try {
            if (cachedEntityIdMethod != null) return ((Number) cachedEntityIdMethod.invoke(entity)).intValue();
            if (cachedEntityIdField  != null) return cachedEntityIdField.getInt(entity);
        } catch (Throwable ignored) {}
        return -1;
    }

}
