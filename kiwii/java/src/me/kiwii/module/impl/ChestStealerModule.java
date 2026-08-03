package me.kiwii.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class ChestStealerModule extends Module {

    public final NumberOption  delayMs;
    public final NumberOption  jitterPct;
    public final NumberOption  maxPerOpen;
    public final NumberOption  openGraceMs;
    public final BooleanOption randomOrder;
    public final BooleanOption reverseOrder;
    public final BooleanOption stopWhenInvFull;
    public final BooleanOption autoClose;
    public final BooleanOption onlyTierChests;

    private final Random rng = new Random();

    private boolean  lookupDone;
    private boolean  chestFieldsResolved;

    private Field    fCurrentScreen;
    private Field    fPlayerController;
    private Class<?> guiChestClass;
    private Class<?> containerClass;
    private Class<?> itemStackClass;
    private Method   mWindowClick;

    private Field    fInventorySlots;
    private Field    fWindowId;
    private Field    fLowerChestInventory;
    private Method   mGetSizeInventory;
    private Method   mGetStackInSlot;
    private Method   mContainerGetSlot;
    private Method   mSlotGetStack;

    private Field    fPlayerInventory;
    private Field    fMainInventory;
    private Method   mGetInventoryName;

    private int     lastScreenHash;
    private boolean chestWasOpen;
    private boolean invFullNotified;
    private long chestOpenedAtMs;
    private long lastClickMs;
    private long nextGapMs;
    private int  stealsThisOpen;
    private int  currentWindowId = -1;
    private int  currentChestSize;
    private List<Integer> queue = new ArrayList<Integer>();
    private int  queueIdx;
    private boolean queueBuilt;

    @Override
    public String getSuffix() {
        return onlyTierChests.getValue() ? "Tier" : null;
    }

    public ChestStealerModule() {
        super("ChestStealer", "Auto-steal chest contents with humanized timing", Category.MISC, 0);
        delayMs         = new NumberOption ("Delay",             110.0D, 20.0D, 500.0D, 5.0D, this);
        jitterPct       = new NumberOption ("Jitter %",           25.0D,  0.0D,  80.0D, 5.0D, this);
        maxPerOpen      = new NumberOption ("Max Per Open",       27.0D,  1.0D,  54.0D, 1.0D, this);
        openGraceMs     = new NumberOption ("Open Grace",        150.0D,  0.0D, 1000.0D, 25.0D, this);
        randomOrder     = new BooleanOption("Random Order",       true,  this);
        reverseOrder    = new BooleanOption("Reverse Order",      false, this);
        stopWhenInvFull = new BooleanOption("Stop When Inv Full", false, this);
        autoClose       = new BooleanOption("Auto Close",         false, this);
        onlyTierChests  = new BooleanOption("Only Tier Chests",   true,  this);

        delayMs.setGroup("Timing");
        jitterPct.setGroup("Timing");
        openGraceMs.setGroup("Timing");
        maxPerOpen.setGroup("Anti-Ban");
        randomOrder.setGroup("Order");
        reverseOrder.setGroup("Order");
        stopWhenInvFull.setGroup("Behavior");
        autoClose.setGroup("Behavior");
        onlyTierChests.setGroup("Filter");

        addOptions(delayMs, jitterPct, openGraceMs, maxPerOpen,
                   randomOrder, reverseOrder, stopWhenInvFull, autoClose, onlyTierChests);
    }

    @Override
    public void onEnable() {
        Logger.info("[CS] enabled");
        lookupDone          = false;
        chestFieldsResolved = false;
        lastScreenHash      = 0;
        chestWasOpen        = false;
        chestOpenedAtMs     = 0L;
        lastClickMs         = 0L;
        nextGapMs           = delayMs.getValue().longValue();
        stealsThisOpen      = 0;
        currentWindowId     = -1;
        currentChestSize    = 0;
        queue.clear();
        queueIdx            = 0;
        queueBuilt          = false;
    }

    @Override
    public void onDisable() {
        Logger.info("[CS] disabled");
    }

    @Override
    public void onUpdate() {
        try {
            boolean allBasics = fCurrentScreen != null && fPlayerController != null
                    && mWindowClick != null && containerClass != null;
            if (!allBasics) {
                lookupDone = true;
                resolveBasics();
            }
            if (fCurrentScreen == null) return;

            Object mc = MinecraftMapper.getMinecraft();
            if (mc == null) return;
            Object screen = fCurrentScreen.get(mc);
            boolean chestOpenNow = screen != null
                    && (guiChestClass != null ? guiChestClass.isInstance(screen) : hasTwinCtor(screen));
            if (chestWasOpen && !chestOpenNow) {
                Logger.info("[CS] chest closed, restoring ingame focus (collected=" + stealsThisOpen + ")");
                if (stealsThisOpen > 0) {
                    me.kiwii.notification.NotificationManager.postInfo(
                            "ChestStealer",
                            "Collected " + stealsThisOpen + (stealsThisOpen == 1 ? " item" : " items"));
                }
                restoreIngameFocusNow(mc);
            }
            chestWasOpen = chestOpenNow;
            if (!chestOpenNow) return;

            int hash = System.identityHashCode(screen);
            if (hash != lastScreenHash) {
                lastScreenHash      = hash;
                chestOpenedAtMs     = System.currentTimeMillis();
                stealsThisOpen      = 0;
                queue.clear();
                queueIdx            = 0;
                queueBuilt          = false;
                chestFieldsResolved = false;
                currentWindowId     = -1;
                Logger.info("[CS] new chest screen: " + screen.getClass().getName());
            }

            if (!chestFieldsResolved) {
                chestFieldsResolved = true;
                resolveChestFields(screen);
            }
            if (fLowerChestInventory == null || fInventorySlots == null
                    || mGetSizeInventory == null || mGetStackInSlot == null) return;

            Object player = MinecraftMapper.getPlayer();
            if (player == null) return;

            long now = System.currentTimeMillis();
            if (now - chestOpenedAtMs < openGraceMs.getValue().longValue()) return;

            if (stopWhenInvFull.getValue() && isPlayerInventoryFull(player)) {
                if (!invFullNotified) {
                    invFullNotified = true;
                    Logger.info("[CS] stop: inventory full");
                    try { me.kiwii.notification.NotificationManager.postWarning("ChestStealer", "Inventory full — paused"); }
                    catch (Throwable ignored) {}
                }
                if (autoClose.getValue()) sendCloseWindow(mc);
                return;
            } else {
                invFullNotified = false;
            }

            Object lowerInv = fLowerChestInventory.get(screen);
            if (lowerInv == null) return;
            Object container = fInventorySlots.get(screen);
            if (container == null) return;
            currentWindowId = fWindowId != null ? fWindowId.getInt(container) : 0;

            if (onlyTierChests.getValue()) {
                if (!chestTitleMatches(lowerInv, "tier")) return;
            }

            if (!queueBuilt) {
                currentChestSize = ((Integer) mGetSizeInventory.invoke(lowerInv)).intValue();
                List<Integer> found = new ArrayList<Integer>();
                for (int i = 0; i < currentChestSize; i++) {
                    Object stack = readSlotStack(container, lowerInv, i);
                    if (stack != null) found.add(i);
                }
                if (randomOrder.getValue())  Collections.shuffle(found, rng);
                if (reverseOrder.getValue()) Collections.reverse(found);
                queue    = found;
                queueIdx = 0;
                queueBuilt = true;
                Logger.info("[CS] queue built: " + found.size() + "/" + currentChestSize
                        + " windowId=" + currentWindowId);
                if (found.isEmpty()) {
                    if (autoClose.getValue()) sendCloseWindow(mc);
                    return;
                }
            }

            if (now - lastClickMs < nextGapMs) return;

            if (stealsThisOpen >= maxPerOpen.getValue().intValue()) {
                Logger.info("[CS] stop: max-per-open reached (" + stealsThisOpen + ")");
                if (autoClose.getValue()) sendCloseWindow(mc);
                return;
            }
            if (queueIdx >= queue.size()) {
                Logger.info("[CS] stop: queue exhausted (" + queueIdx + "/" + queue.size() + ")");
                if (autoClose.getValue()) sendCloseWindow(mc);
                return;
            }

            int slotId = queue.get(queueIdx);
            Object stackRecheck = readSlotStack(container, lowerInv, slotId);
            if (stackRecheck == null) { Logger.info("[CS] slot=" + slotId + " already empty, skip"); queueIdx++; return; }

            Object controller = fPlayerController != null ? fPlayerController.get(mc) : null;
            if (controller == null || mWindowClick == null) return;
            mWindowClick.invoke(controller, currentWindowId, slotId, 0, 1, player);
            Logger.info("[CS] click slot=" + slotId + " wid=" + currentWindowId
                    + " (" + (queueIdx + 1) + "/" + queue.size() + ")");
            queueIdx++;
            stealsThisOpen++;
            lastClickMs = now;
            nextGapMs = rollDelay();
        } catch (Throwable t) {
            Logger.warn("[CS] tick err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private long rollDelay() {
        double base = delayMs.getValue();
        double pct  = jitterPct.getValue() / 100.0D;
        if (pct <= 0.0D) return (long) base;
        double u1 = Math.max(rng.nextDouble(), 1.0e-6D);
        double u2 = rng.nextDouble();
        double g  = Math.sqrt(-2.0D * Math.log(u1)) * Math.sin(2.0D * Math.PI * u2);
        double d  = base + base * pct * g;
        double lo = base * (1.0D - pct);
        double hi = base * (1.0D + pct);
        if (d < lo) d = lo;
        if (d > hi) d = hi;
        return (long) d;
    }

    private void sendCloseWindow(Object mc) {
        try {
            io.netty.channel.Channel ch = MinecraftMapper.getNetworkChannel();
            if (ch != null && ch.isActive() && currentWindowId >= 0) {
                io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
                buf.writeByte(0x0D);
                buf.writeByte(currentWindowId & 0xFF);
                ch.writeAndFlush(buf);
            }
        } catch (Throwable ignored) {}
        closeScreenViaMc(mc);
        lastScreenHash      = 0;
        queue.clear();
        queueIdx            = 0;
        queueBuilt          = false;
        chestFieldsResolved = false;
        currentWindowId     = -1;
    }

    private volatile Method cachedDisplayGuiScreen;
    private volatile boolean displayGuiScreenLookupTried;

    private boolean closeScreenViaMc(Object mc) {
        if (mc == null) return false;
        if (cachedDisplayGuiScreen == null && !displayGuiScreenLookupTried) {
            displayGuiScreenLookupTried = true;
            Class<?> guiScreenClass = MappingUtils.get("GuiScreen");
            if (guiScreenClass != null) {
                for (Method m : mc.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != guiScreenClass) continue;
                    if (m.getReturnType() != void.class) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    cachedDisplayGuiScreen = m;
                    Logger.info("[CS] displayGuiScreen discovered = " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
                    break;
                }
            }
            if (cachedDisplayGuiScreen == null) Logger.warn("[CS] displayGuiScreen NOT FOUND");
        }
        if (cachedDisplayGuiScreen != null) {
            try {
                cachedDisplayGuiScreen.invoke(mc, new Object[]{null});
                return true;
            } catch (Throwable t) { Logger.warn("[CS] displayGuiScreen invoke err: " + t); }
        }
        try { if (fCurrentScreen != null) fCurrentScreen.set(mc, null); } catch (Throwable ignored) {}
        return false;
    }

    private volatile Field cachedStackSizeField;

    private boolean isPlayerInventoryFull(Object player) {
        if (fPlayerInventory == null || fMainInventory == null) return false;
        try {
            Object inv = fPlayerInventory.get(player);
            if (inv == null) return false;
            Object arr = fMainInventory.get(inv);
            if (!(arr instanceof Object[])) return false;
            Object[] arrE = (Object[]) arr;
            if (cachedStackSizeField == null) {
                cachedStackSizeField = MappingUtils.getField("ItemStack.stackSize");
                if (cachedStackSizeField != null) cachedStackSizeField.setAccessible(true);
            }
            for (Object item : arrE) {
                if (item == null) return false;
                if (cachedStackSizeField != null) {
                    try {
                        int sz = cachedStackSizeField.getInt(item);
                        if (sz <= 0) return false;
                    } catch (Throwable ignored) { return false; }
                }
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    private void resolveBasics() {
        try {
            guiChestClass  = MappingUtils.get("GuiChest");
            containerClass = MappingUtils.get("Container");
            itemStackClass = MappingUtils.get("ItemStack");

            fCurrentScreen    = MappingUtils.getField("Minecraft.currentScreen");
            fPlayerController = MappingUtils.getField("Minecraft.playerController");
            mWindowClick      = MappingUtils.getMethod("PlayerControllerMP.windowClick");
            mContainerGetSlot = MappingUtils.getMethod("Container.getSlot");
            mSlotGetStack     = MappingUtils.getMethod("Slot.getStack");

            if (fCurrentScreen != null)    fCurrentScreen.setAccessible(true);
            if (fPlayerController != null) fPlayerController.setAccessible(true);
            if (mWindowClick != null)      mWindowClick.setAccessible(true);
            if (mContainerGetSlot != null) mContainerGetSlot.setAccessible(true);
            if (mSlotGetStack != null)     mSlotGetStack.setAccessible(true);

            Logger.info("[CS] basics: GuiChest=" + cn(guiChestClass)
                    + " Container=" + cn(containerClass)
                    + " ItemStack=" + cn(itemStackClass)
                    + " screen=" + fi(fCurrentScreen)
                    + " pc=" + fi(fPlayerController)
                    + " wc=" + mi(mWindowClick)
                    + " containerGetSlot=" + mi(mContainerGetSlot)
                    + " slotGetStack=" + mi(mSlotGetStack));

            resolvePlayerInventory();
        } catch (Throwable t) {
            Logger.warn("[CS] resolveBasics FAILED: " + t.getMessage());
        }
    }

    private void resolvePlayerInventory() {
        Object player = MinecraftMapper.getPlayer();
        if (player == null) { Logger.warn("[CS] player null — inventory resolve deferred"); lookupDone = false; return; }
        try {
            for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray() || ft.getName().startsWith("java.")) continue;

                    boolean hasItemArray = false;
                    for (Field inner : ft.getDeclaredFields()) {
                        if (inner.getType().isArray()
                                && !inner.getType().getComponentType().isPrimitive()
                                && itemStackClass != null
                                && inner.getType().getComponentType() == itemStackClass) {
                            hasItemArray = true; break;
                        }
                    }
                    if (!hasItemArray) continue;

                    f.setAccessible(true);
                    Object invObj = f.get(player);
                    if (invObj == null) continue;
                    fPlayerInventory = f;

                    for (Field arrF : ft.getDeclaredFields()) {
                        if (Modifier.isStatic(arrF.getModifiers())) continue;
                        if (!arrF.getType().isArray()) continue;
                        if (arrF.getType().getComponentType().isPrimitive()) continue;
                        arrF.setAccessible(true);
                        Object arr = arrF.get(invObj);
                        if (arr instanceof Object[] && ((Object[]) arr).length == 36 && fMainInventory == null) {
                            fMainInventory = arrF;
                        }
                    }
                    break;
                }
                if (fPlayerInventory != null) break;
            }
            Logger.info("[CS] playerInventory=" + fi(fPlayerInventory) + " mainInventory=" + fi(fMainInventory));
        } catch (Throwable t) { Logger.warn("[CS] resolvePlayerInventory FAILED: " + t.getMessage()); }
    }

    private void resolveChestFields(Object screen) {
        try {
            if (containerClass != null) {
                for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (containerClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            fInventorySlots = f;
                            break;
                        }
                    }
                    if (fInventorySlots != null) break;
                }
            }
            if (fInventorySlots == null) { Logger.warn("[CS] inventorySlots NOT FOUND"); return; }

            Object container = fInventorySlots.get(screen);
            if (container != null) resolveWindowId(container);

            Object pl = MinecraftMapper.getPlayer();
            Class<?> playerInvClass = null;
            if (pl != null && fPlayerInventory != null) {
                try {
                    Object pInv = fPlayerInventory.get(pl);
                    if (pInv != null) playerInvClass = pInv.getClass();
                } catch (Throwable ignored) {}
            }

            List<Field> candidates = new ArrayList<Field>();
            for (Field f : screen.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                if (ft.getName().startsWith("java.")) continue;
                if (containerClass != null && containerClass.isAssignableFrom(ft)) continue;
                f.setAccessible(true);
                candidates.add(f);
            }

            for (Field f : candidates) {
                Object inv = null;
                try { inv = f.get(screen); } catch (Throwable ignored) {}
                if (inv == null) continue;
                if (playerInvClass != null && inv.getClass() == playerInvClass) continue;

                Method bestSize = null;
                int bestSizeVal = -1;
                Method bestGetSlot = null;

                for (Method m : inv.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getDeclaringClass() == Object.class) continue;

                    if (m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                        try {
                            int val = (Integer) m.invoke(inv);
                            if (val > 0 && val < 100 && bestSize == null) {
                                bestSize = m; bestSizeVal = val;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == int.class
                            && !m.getReturnType().isPrimitive()
                            && m.getReturnType() != void.class) {
                        if (itemStackClass != null && m.getReturnType() == itemStackClass) {
                            if (bestGetSlot == null) bestGetSlot = m;
                        } else if (bestGetSlot == null && !m.getReturnType().getName().startsWith("java.")) {
                            bestGetSlot = m;
                        }
                    }
                }

                if (bestSize != null && bestGetSlot != null) {
                    fLowerChestInventory = f;
                    mGetSizeInventory = bestSize;
                    mGetStackInSlot = bestGetSlot;
                    mGetSizeInventory.setAccessible(true);
                    mGetStackInSlot.setAccessible(true);
                    Logger.info("[CS] chest: lowerInv=" + f.getName()
                            + " size=" + bestSize.getName() + "()->" + bestSizeVal
                            + " getSlot=" + bestGetSlot.getName());
                    break;
                }
            }

            if (fLowerChestInventory == null) Logger.warn("[CS] lowerChestInventory NOT FOUND");
        } catch (Throwable t) { Logger.warn("[CS] resolveChestFields FAILED: " + t.getMessage()); }
    }

    private void resolveWindowId(Object container) {
        fWindowId = MappingUtils.getField("Container.windowId");
        if (fWindowId != null) {
            fWindowId.setAccessible(true);
            try {
                int v = fWindowId.getInt(container);
                if (v > 0) return;
            } catch (Throwable ignored) {}
            fWindowId = null;
        }
        if (containerClass != null) {
            for (Field f : containerClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int v = f.getInt(container);
                    if (v > 0 && v < 128 && fWindowId == null) { fWindowId = f; return; }
                } catch (Throwable ignored) {}
            }
        }
        if (fWindowId == null) {
            for (Class<?> c = container.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (c == containerClass) continue;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != int.class) continue;
                    try {
                        f.setAccessible(true);
                        int v = f.getInt(container);
                        if (v > 0 && v < 128 && fWindowId == null) { fWindowId = f; return; }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private Object readSlotStack(Object container, Object lowerInv, int slotIndex) {
        if (mContainerGetSlot != null && container != null) {
            try {
                Object slotObj = mContainerGetSlot.invoke(container, slotIndex);
                if (slotObj != null) {
                    Method getter = ensureSlotGetStack(slotObj);
                    if (getter != null) return getter.invoke(slotObj);
                }
            } catch (Throwable ignored) {}
        }
        if (mGetStackInSlot != null && lowerInv != null) {
            try { return mGetStackInSlot.invoke(lowerInv, slotIndex); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private Method ensureSlotGetStack(Object slotInstance) {
        if (mSlotGetStack != null) return mSlotGetStack;
        if (slotInstance == null) return null;
        for (Class<?> c = slotInstance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive() || rt == void.class) continue;
                if (rt.getName().startsWith("java.")) continue;
                if (itemStackClass != null && rt != itemStackClass) continue;
                m.setAccessible(true);
                mSlotGetStack = m;
                Logger.info("[CS] Slot.getStack runtime discovery -> " + c.getSimpleName() + "." + m.getName());
                return m;
            }
        }
        return null;
    }

    private volatile boolean titleMethodsLogged;
    private boolean chestTitleMatches(Object lowerInv, String needleLower) {
        if (lowerInv == null || needleLower == null) return false;
        boolean anyLogged = !titleMethodsLogged;
        StringBuilder dbg = anyLogged ? new StringBuilder() : null;
        for (Method m : lowerInv.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            if ("toString".equals(m.getName())) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(lowerInv);
                if (v instanceof String) {
                    String s = (String) v;
                    if (dbg != null) dbg.append(m.getName()).append("=\"").append(s).append("\" | ");
                    if (s.toLowerCase().contains(needleLower)) {
                        if (anyLogged) {
                            titleMethodsLogged = true;
                            Logger.info("[CS] title match via " + m.getName() + " -> \"" + s + "\"");
                        }
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (anyLogged && dbg != null) {
            titleMethodsLogged = true;
            Logger.info("[CS] title NO MATCH — string methods: " + dbg);
        }
        return false;
    }

    private void restoreIngameFocusNow(Object mc) {
        if (mc == null) return;
        boolean viaDisplay = closeScreenViaMc(mc);
        boolean focusFieldOk = false;
        boolean focusMethodOk = false;
        try {
            Field fFocus = MappingUtils.getField("Minecraft.inGameHasFocus");
            if (fFocus != null) { fFocus.setAccessible(true); fFocus.setBoolean(mc, false); focusFieldOk = true; }
        } catch (Throwable ignored) {}
        try {
            Method setFocus = MappingUtils.getMethod("Minecraft.setIngameFocus");
            if (setFocus != null) { setFocus.setAccessible(true); setFocus.invoke(mc); focusMethodOk = true; }
        } catch (Throwable ignored) {}
        boolean afterGrabbed = false;
        try {
            org.lwjgl.input.Mouse.setGrabbed(false);
            org.lwjgl.input.Mouse.setGrabbed(true);
            afterGrabbed = org.lwjgl.input.Mouse.isGrabbed();
        } catch (Throwable ignored) {}
        Logger.info("[CS] focus restore: viaDisplay=" + viaDisplay
                + " field=" + focusFieldOk
                + " method=" + focusMethodOk
                + " grabAfter=" + afterGrabbed);
    }

    private static boolean hasTwinCtor(Object screen) {
        for (java.lang.reflect.Constructor<?> c : screen.getClass().getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == p[1] && !p[0].isPrimitive()
                    && !p[0].getName().startsWith("java.")) return true;
        }
        return false;
    }

    private static String cn(Class<?> c) { return c != null ? c.getName() : "null"; }
    private static String fi(Field f) {
        if (f == null) return "null";
        return f.getDeclaringClass().getSimpleName() + "." + f.getName();
    }
    private static String mi(Method m) {
        if (m == null) return "null";
        return m.getDeclaringClass().getSimpleName() + "." + m.getName() + "(" + m.getParameterCount() + ")";
    }
}
