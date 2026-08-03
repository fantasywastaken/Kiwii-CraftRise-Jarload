package me.kiwii.mapping;

import me.kiwii.util.Logger;
import java.lang.reflect.*;
import java.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

public class GameAccessor {

    // ─────────────────────────────────────────────────────────────────────
    // Core accessors
    // ─────────────────────────────────────────────────────────────────────

    public static Object getMinecraft() {
        try {
            Field theMinecraft = AutoMapper.getField("Minecraft.theMinecraft");
            if (theMinecraft != null) {
                return theMinecraft.get(null);
            }
            Method getInstance = AutoMapper.getMethod("Minecraft.getInstance");
            if (getInstance != null) {
                return getInstance.invoke(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Object getPlayer() {
        try {
            Object mc = getMinecraft();
            if (mc != null) {
                Method getThePlayer = AutoMapper.getMethod("Minecraft.getThePlayer");
                if (getThePlayer != null) {
                    return getThePlayer.invoke(mc);
                }
                Field thePlayer = AutoMapper.getField("Minecraft.thePlayer");
                if (thePlayer != null) {
                    return thePlayer.get(mc);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Object getThePlayer() {
        return getPlayer();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inventory
    // ─────────────────────────────────────────────────────────────────────

    public static Object getInventory() {
        try {
            Object player = getPlayer();
            if (player != null) {
                Field inventory = AutoMapper.getField("EntityPlayer.inventory");
                if (inventory != null) {
                    return inventory.get(player);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Attack entity + helpers
    // ─────────────────────────────────────────────────────────────────────

    public static boolean attackEntity(Object entity) {
        ByteBuf packetBuffer = null;
        try {
            if (entity == null) {
                Logger.warn("[Mapper] Skipping attackEntity: entity is null");
                return false;
            }

            int entityId = getEntityIdForAttackPacket(entity);
            if (entityId < 0) {
                Logger.warn("[Mapper] Skipping attackEntity: entity id could not be resolved");
                return false;
            }

            Channel channel = getNetworkChannel();
            if (channel == null) {
                Logger.warn("[Mapper] Skipping attackEntity: network channel is not available");
                return false;
            }
            if (!channel.isActive()) {
                Logger.warn("[Mapper] Skipping attackEntity: network channel is not active");
                return false;
            }

            Class<?> actionClass = AutoMapper.get("C02PacketUseEntityAction");
            if (actionClass == null) {
                Logger.warn("[Mapper] Skipping attackEntity: C02PacketUseEntityAction is not mapped");
                return false;
            }

            Enum<?> attackEnum = getAttackActionEnum(actionClass);
            if (attackEnum == null) {
                Logger.warn("[Mapper] Skipping attackEntity: ATTACK action enum is not available");
                return false;
            }

            float hitHeight = 1.1F + (float) (Math.random() * 0.65D);
            packetBuffer = Unpooled.buffer();
            packetBuffer.writeByte(2);
            writeVarInt(packetBuffer, entityId);
            writeEnumValueToByteBuf(packetBuffer, attackEnum);
            packetBuffer.writeFloat(hitHeight);

            logAttackEntityOut(entity, entityId, attackEnum, hitHeight, channel, packetBuffer);
            channel.writeAndFlush(packetBuffer);
            packetBuffer = null;
            return true;
        } catch (Throwable t) {
            Logger.warn("[Mapper] Skipping attackEntity: " + String.valueOf(t.getMessage()));
            return false;
        } finally {
            if (packetBuffer != null) {
                packetBuffer.release();
            }
        }
    }

    private static void logAttackEntityOut(Object entity, int entityId, Enum<?> actionEnum, float hitHeight, Channel channel, ByteBuf packetBuffer) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("\n========== ATTACK ENTITY OUT ==========\n");
            out.append("source=GameAccessor.attackEntity\n");
            out.append("rawByteBuf=true\n");
            out.append("thread=").append(Thread.currentThread().getName()).append('\n');
            out.append("packetId=0x02\n");
            out.append("entityId=").append(entityId).append('\n');
            out.append("actionId=").append(actionEnum != null ? actionEnum.ordinal() : -1).append(" (ATTACK)\n");
            out.append("actionName=").append(actionEnum != null ? actionEnum.name() : "null").append('\n');
            out.append("hitHeight=").append(hitHeight).append('\n');
            out.append("bufferReaderIndex=").append(packetBuffer != null ? packetBuffer.readerIndex() : -1).append('\n');
            out.append("bufferWriterIndex=").append(packetBuffer != null ? packetBuffer.writerIndex() : -1).append('\n');
            out.append("bufferReadableBytes=").append(packetBuffer != null ? packetBuffer.readableBytes() : -1).append('\n');
            out.append("bufferCapacity=").append(packetBuffer != null ? packetBuffer.capacity() : -1).append('\n');
            out.append("packetBytes=").append(byteBufToHex(packetBuffer)).append('\n');
            out.append("targetClass=").append(entity != null ? entity.getClass().getName() : "null").append('\n');
            out.append("targetName=").append(safeEntityName(entity)).append('\n');
            out.append("targetHash=").append(entity != null ? System.identityHashCode(entity) : 0).append('\n');
            out.append("targetToString=").append(safeToString(entity)).append('\n');
            out.append("targetPos=").append(describeEntityPosition(entity)).append('\n');
            out.append("targetFields=").append(describeObjectFields(entity, 64)).append('\n');

            Object player = getThePlayer();
            out.append("playerClass=").append(player != null ? player.getClass().getName() : "null").append('\n');
            out.append("playerName=").append(safeEntityName(player)).append('\n');
            out.append("playerPos=").append(describeEntityPosition(player)).append('\n');
            out.append("distance=").append(describeDistance(player, entity)).append('\n');

            if (channel != null) {
                out.append("channelClass=").append(channel.getClass().getName()).append('\n');
                out.append("channelActive=").append(channel.isActive()).append('\n');
                out.append("channelOpen=").append(channel.isOpen()).append('\n');
                out.append("channelWritable=").append(channel.isWritable()).append('\n');
                out.append("channelLocal=").append(String.valueOf(channel.localAddress())).append('\n');
                out.append("channelRemote=").append(String.valueOf(channel.remoteAddress())).append('\n');
            } else {
                out.append("channel=null\n");
            }

            out.append("=======================================\n");
            System.out.println(out.toString());
        } catch (Throwable t) {
            System.out.println("[ATTACK ENTITY OUT] log failed: " + String.valueOf(t.getMessage()));
        }
    }

    private static String byteBufToHex(ByteBuf buffer) {
        if (buffer == null) {
            return "null";
        }

        StringBuilder hex = new StringBuilder();
        int readerIndex = buffer.readerIndex();
        int writerIndex = buffer.writerIndex();
        for (int i = readerIndex; i < writerIndex; i++) {
            if (hex.length() > 0) {
                hex.append(' ');
            }
            int value = buffer.getUnsignedByte(i);
            if (value < 0x10) {
                hex.append('0');
            }
            hex.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return hex.toString();
    }

    private static String safeEntityName(Object entity) {
        if (entity == null) {
            return "null";
        }
        try {
            return getName(entity);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
    }

    private static String describeEntityPosition(Object entity) {
        if (entity == null) {
            return "null";
        }

        Double x = readMappedDouble(entity, "Entity.posX");
        Double y = readMappedDouble(entity, "Entity.posY");
        Double z = readMappedDouble(entity, "Entity.posZ");
        if (x == null || y == null || z == null) {
            return "unknown";
        }
        return "x=" + x + ", y=" + y + ", z=" + z;
    }

    private static String describeObjectFields(Object value, int maxFields) {
        if (value == null) {
            return "null";
        }

        StringBuilder fieldsOut = new StringBuilder("[");
        int count = 0;
        Class<?> current = value.getClass();
        while (current != null && current != Object.class && count < maxFields) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (count >= maxFields) {
                    break;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                if (count > 0) {
                    fieldsOut.append(", ");
                }

                fieldsOut.append(current.getSimpleName()).append('.').append(field.getName()).append('=');
                try {
                    field.setAccessible(true);
                    fieldsOut.append(shortValue(field.get(value)));
                } catch (Throwable t) {
                    fieldsOut.append("<").append(t.getClass().getSimpleName()).append('>');
                }
                count++;
            }
            current = current.getSuperclass();
        }
        if (current != null && current != Object.class) {
            fieldsOut.append(", ...");
        }
        fieldsOut.append(']');
        return fieldsOut.toString();
    }

    private static String shortValue(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            return valueClass.getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum) {
            return String.valueOf(value);
        }
        if (value instanceof CharSequence) {
            return '"' + String.valueOf(value) + '"';
        }
        return valueClass.getName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }

    private static String describeDistance(Object from, Object to) {
        Double fromX = readMappedDouble(from, "Entity.posX");
        Double fromY = readMappedDouble(from, "Entity.posY");
        Double fromZ = readMappedDouble(from, "Entity.posZ");
        Double toX = readMappedDouble(to, "Entity.posX");
        Double toY = readMappedDouble(to, "Entity.posY");
        Double toZ = readMappedDouble(to, "Entity.posZ");
        if (fromX == null || fromY == null || fromZ == null || toX == null || toY == null || toZ == null) {
            return "unknown";
        }

        double dx = fromX.doubleValue() - toX.doubleValue();
        double dy = fromY.doubleValue() - toY.doubleValue();
        double dz = fromZ.doubleValue() - toZ.doubleValue();
        return String.valueOf(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static Double readMappedDouble(Object target, String mappingName) {
        if (target == null) {
            return null;
        }

        try {
            Field field = AutoMapper.getField(mappingName);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getEntityIdForAttackPacket(Object entity) throws Exception {
        // 1) Try field first (Entity.entityId)
        Field entityIdField = AutoMapper.getField("Entity.entityId");
        if (entityIdField != null) {
            entityIdField.setAccessible(true);
            Object value = entityIdField.get(entity);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }

        // 2) Try method
        Method getEntityId = AutoMapper.getMethod("Entity.getEntityId");
        if (getEntityId == null) {
            ensureC02EntityIdMappings();
            getEntityId = AutoMapper.getMethod("Entity.getEntityId");
        }

        if (getEntityId != null) {
            getEntityId.setAccessible(true);
            Object value = getEntityId.invoke(entity);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }

        // 3) hashCode fallback
        int hc = System.identityHashCode(entity);
        if (hc > 0) {
            Logger.warn("[Mapper] Entity.getEntityId not mapped, using identityHashCode=" + hc);
            return hc;
        }

        throw new NoSuchMethodException("Entity.getEntityId");
    }

    private static void ensureC02EntityIdMappings() {
        if (AutoMapper.getMethod("Entity.getEntityId") != null) {
            return;
        }

        try {
            Class<?> c02Class = AutoMapper.get("C02PacketUseEntity");
            Class<?> entityClass = AutoMapper.get("Entity");
            if (c02Class == null || entityClass == null) {
                return;
            }

            byte[] bytes = AutoMapper.getClassBytes(c02Class);
            if (bytes == null || bytes.length == 0) {
                return;
            }

            ClassNode classNode = new ClassNode();
            new ClassReader(bytes).accept(classNode, 0);

            String c02Owner = org.objectweb.asm.Type.getInternalName(c02Class);
            String entityOwner = org.objectweb.asm.Type.getInternalName(entityClass);

            for (MethodNode methodNode : classNode.methods) {
                if (!"<init>".equals(methodNode.name)) {
                    continue;
                }

                MethodInsnNode getEntityIdCall = null;
                FieldInsnNode entityIdField = null;

                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode methodInsn = (MethodInsnNode) insn;
                        if (entityOwner.equals(methodInsn.owner) && "()I".equals(methodInsn.desc)) {
                            getEntityIdCall = methodInsn;
                        }
                    } else if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                        if (getEntityIdCall != null
                                && c02Owner.equals(fieldInsn.owner)
                                && "I".equals(fieldInsn.desc)
                                && fieldInsn.getOpcode() == PUTFIELD) {
                            entityIdField = fieldInsn;
                            break;
                        }
                    }
                }

                if (getEntityIdCall == null) {
                    continue;
                }

                Method reflectedGetEntityId = entityClass.getDeclaredMethod(getEntityIdCall.name);
                reflectedGetEntityId.setAccessible(true);
                AutoMapper.putMethod("Entity.getEntityId", reflectedGetEntityId);

                if (entityIdField != null) {
                    Field reflectedEntityIdField = c02Class.getDeclaredField(entityIdField.name);
                    reflectedEntityIdField.setAccessible(true);
                    AutoMapper.putField("C02PacketUseEntity.entityId", reflectedEntityIdField);
                }
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    public static Channel getNetworkChannel() throws Exception {
        Object netHandler = getNetHandlerPlayClient();
        if (netHandler == null) {
            return null;
        }

        Object networkManager = getNetworkManager(netHandler);
        if (networkManager == null) {
            return null;
        }

        Field channelField = AutoMapper.getField("NetworkManager.channel");
        if (channelField == null) {
            channelField = getFieldByType(networkManager.getClass(), Channel.class);
        }
        if (channelField == null) {
            return null;
        }

        channelField.setAccessible(true);
        Object channel = channelField.get(networkManager);
        return channel instanceof Channel ? (Channel) channel : null;
    }

    private static Object getNetHandlerPlayClient() throws Exception {
        Object player = getThePlayer();
        if (player == null) {
            return null;
        }

        Field sendQueue = AutoMapper.getField("EntityPlayerSP.sendQueue");
        if (sendQueue == null) {
            sendQueue = getFieldByType(player.getClass(), AutoMapper.get("NetHandlerPlayClient"));
        }
        if (sendQueue == null) {
            return null;
        }

        sendQueue.setAccessible(true);
        return sendQueue.get(player);
    }

    private static Object getNetworkManager(Object netHandler) throws Exception {
        Field networkManagerField = AutoMapper.getField("NetHandlerPlayClient.networkManager");
        if (networkManagerField == null) {
            networkManagerField = getFieldByType(netHandler.getClass(), AutoMapper.get("NetworkManager"));
        }
        if (networkManagerField == null) {
            return null;
        }

        networkManagerField.setAccessible(true);
        return networkManagerField.get(netHandler);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Enum<?> getAttackActionEnum(Class<?> actionClass) throws Exception {
        try {
            return Enum.valueOf((Class) actionClass, "ATTACK");
        } catch (IllegalArgumentException valueOfFailed) {
            Field attackField;
            try {
                attackField = actionClass.getField("ATTACK");
            } catch (NoSuchFieldException ignored) {
                attackField = actionClass.getDeclaredField("ATTACK");
            }
            attackField.setAccessible(true);
            Object value = attackField.get(null);
            return value instanceof Enum ? (Enum<?>) value : null;
        }
    }

    private static int getC02AttackActionId() {
        try {
            Class<?> actionClass = AutoMapper.get("C02PacketUseEntityAction");
            if (actionClass == null) {
                return 1;
            }

            Field attackField;
            try {
                attackField = actionClass.getField("ATTACK");
            } catch (NoSuchFieldException ignored) {
                attackField = actionClass.getDeclaredField("ATTACK");
            }
            attackField.setAccessible(true);

            Object attack = attackField.get(null);
            if (attack instanceof Enum) {
                return ((Enum<?>) attack).ordinal();
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static void writeEnumValueToByteBuf(ByteBuf buffer, Enum<?> value) {
        writeVarInt(buffer, value.ordinal());
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & -128) != 0) {
            buffer.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    public static void addChatMessage(String msg) {
        Logger.info("[CHAT MOCKED] " + msg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Font renderer
    // ─────────────────────────────────────────────────────────────────────

    public static Object getFontRenderer() {
        try {
            Object minecraftInstance = getMinecraft();
            Method getFontRendererMethod = AutoMapper.getMethod("Minecraft.getFontRendererObj");
            if (minecraftInstance == null)
                return null;

            if (getFontRendererMethod != null) {
                return getFontRendererMethod.invoke(minecraftInstance);
            }

            Field fontRendererField = AutoMapper.getField("Minecraft.fontRendererObj");
            if (fontRendererField != null) {
                return fontRendererField.get(minecraftInstance);
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Player entities & name
    // ─────────────────────────────────────────────────────────────────────

    public static List<Object> getPlayerEntitiesInWorld() {
        List<Object> emptyList = Collections.emptyList();
        try {
            Object player = getThePlayer();
            Object worldObj = getWorldObj();
            Field playerEntitiesField = AutoMapper.getField("World.playerEntities");
            if (player == null || worldObj == null || playerEntitiesField == null) {
                Logger.warn("[Mapper] Skipping getPlayerEntitiesInWorld: player, world, or World.playerEntities is not available");
                return emptyList;
            }

            Object entityList = playerEntitiesField.get(worldObj);
            if (!(entityList instanceof List)) {
                return emptyList;
            }

            List<Object> entities = new ArrayList<>();
            for (Object entity : (List<?>) entityList) {
                if (entity != player) {
                    entities.add(entity);
                }
            }

            return entities;
        } catch (Exception e) {
            Logger.warn("[Mapper] Skipping getPlayerEntitiesInWorld: " + e.getMessage());
        }
        return emptyList;
    }

    public static String getName(Object entity) {
        try {
            String s = String.valueOf(entity);
            int start = s.indexOf('\'') + 1;
            int end = s.indexOf('\'', start);
            if (start > 0 && end > start) {
                return s.substring(start, end);
            }
        } catch (Exception e) {
            System.out.println("[GameAccessor] Could not extract name: " + e.getMessage());
        }
        return "Bilinmiyor";
    }

    // ─────────────────────────────────────────────────────────────────────
    // World object
    // ─────────────────────────────────────────────────────────────────────

    public static Object getWorldObj() {
        try {
            Field worldObjField = AutoMapper.getField("EntityPlayerSP.worldObj");
            if (worldObjField == null) {
                return null;
            }

            Object player = getThePlayer();
            if (player == null) {
                return null;
            }

            worldObjField.setAccessible(true);

            return worldObjField.get(player);

        } catch (IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Motion / position getters & setters
    // ─────────────────────────────────────────────────────────────────────

    private static void updateMotionField(Field field, Object player, double value) throws Exception {
        Object current = field.get(player);
        if (current == null) return;
        Constructor<?> ctor = current.getClass().getDeclaredConstructor(double.class);
        ctor.setAccessible(true);
        field.set(player, ctor.newInstance(value));
    }

    public static double getMotionX() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.motionX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            if (obj instanceof Double) return (Double) obj;
            Method getValue = AutoMapper.getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getMotionY() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.motionY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            if (obj instanceof Double) return (Double) obj;
            Method getValue = AutoMapper.getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getMotionZ() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.motionZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            if (obj instanceof Double) return (Double) obj;
            Method getValue = AutoMapper.getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static void setMotionX(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = AutoMapper.getField("Entity.motionX");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void setMotionY(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = AutoMapper.getField("Entity.motionY");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void setMotionZ(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = AutoMapper.getField("Entity.motionZ");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static double getPosX() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.posX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosY() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.posY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosZ() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = AutoMapper.getField("Entity.posZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosX(Object entity) {
        try {
            Field f = AutoMapper.getField("Entity.posX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosY(Object entity) {
        try {
            Field f = AutoMapper.getField("Entity.posY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosZ(Object entity) {
        try {
            Field f = AutoMapper.getField("Entity.posZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static float getRotationYaw() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = AutoMapper.getField("Entity.rotationYaw");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static float getRotationPitch() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = AutoMapper.getField("Entity.rotationPitch");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static void setOnGround(boolean value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = AutoMapper.getField("Entity.onGround");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Boolean) {
                f.set(player, value);
            } else if (current != null) {
                for (Field bf : current.getClass().getDeclaredFields()) {
                    if (bf.getType() == boolean.class) {
                        bf.setAccessible(true);
                        bf.setBoolean(current, value);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void setFallDistance(float value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = AutoMapper.getField("Entity.fallDistance");
            if (f == null) return;
            f.setAccessible(true);
            f.setFloat(player, Math.max(0f, value));
        } catch (Exception ignored) {}
    }

    public static float getFallDistance() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = AutoMapper.getField("Entity.fallDistance");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static boolean isOnGround() {
        try {
            Object player = getPlayer();
            if (player == null) return false;
            Field f = AutoMapper.getField("Entity.onGround");
            if (f == null) return false;
            f.setAccessible(true);
            Object val = f.get(player);
            if (val instanceof Boolean) return (Boolean) val;
            if (val != null) {
                Method getValue = AutoMapper.getMethod("BooleanContainer.getValue");
                if (getValue != null) return (boolean) getValue.invoke(val);
                for (Field bf : val.getClass().getDeclaredFields()) {
                    if (bf.getType() == boolean.class) { bf.setAccessible(true); return bf.getBoolean(val); }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utility (copied from MinecraftMapper since used primarily here)
    // ─────────────────────────────────────────────────────────────────────

    public static Field getFieldByType(Class<?> clazz, Class<?> type) {
        if (clazz == null || type == null) {
            return null;
        }
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == type) {
                return field;
            }
        }
        return null;
    }
}
