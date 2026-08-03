package me.kiwii.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.kiwii.util.MappingUtils;
import me.kiwii.setting.OptionBase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.kiwii.util.Logger;

public abstract class Module {
    protected String name;
    protected String description;
    protected Category category;
    protected int keyBind;
    protected boolean enabled = false;
    private final List<OptionBase<?>> options = new ArrayList<OptionBase<?>>();

    public Module(String name, String description) {
        this(name, description, Category.MISC, 0);
    }

    public Module(String name, String description, Category category, int keyBind) {
        this.name = name;
        this.description = description;
        this.category = category == null ? Category.MISC : category;
        this.keyBind = keyBind;
    }
    
    public abstract void onEnable();
    public abstract void onDisable();
    public abstract void onUpdate();
    
    public void toggle() {
        if (enabled) {
            disable();
        } else {
            enable();
        }
    }
    
    public void enable() {
        if (!enabled) {
            try {
                onEnable();
                enabled = true;
                me.kiwii.config.ConfigManager.markDirty();
                postToggleNotif(true);
            } catch (Throwable t) {
                enabled = false;
                Logger.warn("Module enable failed for " + name + " - " + String.valueOf(t.getMessage()));
            }
        }
    }

    public void disable() {
        if (enabled) {
            try {
                onDisable();
            } catch (Throwable t) {
                Logger.warn("Module disable failed for " + name + " - " + String.valueOf(t.getMessage()));
            } finally {
                enabled = false;
                me.kiwii.config.ConfigManager.markDirty();
                postToggleNotif(false);
            }
        }
    }

    private static final long STARTUP_MUTE_UNTIL = System.currentTimeMillis() + 4000L;

    private void postToggleNotif(boolean on) {
        if (System.currentTimeMillis() < STARTUP_MUTE_UNTIL) return;
        if ("Notifications".equals(name)) return;
        try { me.kiwii.notification.NotificationManager.postModule(name, on); }
        catch (Throwable ignored) {}
    }
    
    public String getName() {
        return name;
    }

    public String getDisplayName() {
        String n = name;
        if (n == null || n.length() < 2) return n;
        StringBuilder sb = new StringBuilder(n.length() + 4);
        sb.append(n.charAt(0));
        for (int i = 1; i < n.length(); i++) {
            char c = n.charAt(i);
            char prev = n.charAt(i - 1);
            boolean nextLower = (i + 1 < n.length()) && Character.isLowerCase(n.charAt(i + 1));
            if (Character.isUpperCase(c)
                    && (Character.isLowerCase(prev)
                        || (Character.isUpperCase(prev) && nextLower))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isEnabled() {
        return enabled;
    }

    public Category getCategory() {
        return category;
    }

    public int getKeyBind() {
        return keyBind;
    }

    public void setKeyBind(int keyBind) {
        int old = this.keyBind;
        this.keyBind = keyBind;
        me.kiwii.config.ConfigManager.markDirty();
        if (old != keyBind && System.currentTimeMillis() >= STARTUP_MUTE_UNTIL) {
            try {
                String kn = keyBind > 0 ? org.lwjgl.input.Keyboard.getKeyName(keyBind) : "NONE";
                me.kiwii.notification.NotificationManager.postInfo(name, "Bound to " + kn);
            } catch (Throwable ignored) {}
        }
    }

    public List<OptionBase<?>> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public String getSuffix() { return null; }

    public List<OptionBase<?>> getVisibleOptions() {
        List<OptionBase<?>> out = new java.util.ArrayList<OptionBase<?>>();
        for (OptionBase<?> opt : options) {
            if (isOptionVisible(opt)) out.add(opt);
        }
        return out;
    }

    public boolean isOptionVisible(OptionBase<?> opt) {
        if (!opt.isVisible()) return false;
        String dep = opt.getDependency();
        if (dep == null || dep.isEmpty()) return true;
        int colon = dep.indexOf(':');
        if (colon <= 0) return true;
        String depName = dep.substring(0, colon).trim();
        String expected = dep.substring(colon + 1).trim();
        OptionBase<?> depOpt = getOption(depName);
        if (depOpt == null) return true;
        Object v = depOpt.getValue();
        if (v == null) return "null".equalsIgnoreCase(expected);
        return expected.equalsIgnoreCase(String.valueOf(v));
    }

    public OptionBase<?> getOption(String name) {
        if (name == null) {
            return null;
        }
        for (OptionBase<?> option : options) {
            if (option.getName().equalsIgnoreCase(name)) {
                return option;
            }
        }
        return null;
    }

    protected void addOption(OptionBase<?> option) {
        if (option != null) {
            options.add(option);
        }
    }

    protected void addOptions(OptionBase<?>... options) {
        if (options == null) {
            return;
        }
        for (OptionBase<?> option : options) {
            addOption(option);
        }
    }
    

    
    
    protected Object getMinecraft() {
        try {
            Class<?> mc = MappingUtils.get("Minecraft");
            if (mc == null) return null;

            Method getInstance = MappingUtils.getMethod("Minecraft.getInstance");
            if (getInstance != null) {
                getInstance.setAccessible(true);
                return getInstance.invoke(null);
            }

            Field theMinecraft = MappingUtils.getField("Minecraft.theMinecraft");
            if (theMinecraft == null) return null;

            theMinecraft.setAccessible(true);
            return theMinecraft.get(null);
        } catch (Throwable e) {
            return null;
        }
    }
    
    
    protected Object getThePlayer() {
        try {
            Object mc = getMinecraft();
            if (mc == null) return null;

            Method getPlayer = MappingUtils.getMethod("Minecraft.getThePlayer");
            if (getPlayer != null) {
                getPlayer.setAccessible(true);
                return getPlayer.invoke(mc);
            }

            Field thePlayer = MappingUtils.getField("Minecraft.thePlayer");
            if (thePlayer == null) return null;

            thePlayer.setAccessible(true);
            return thePlayer.get(mc);
        } catch (Throwable e) {
            return null;
        }
    }
    
    
    protected Object getTheWorld() {
        try {
            Object mc = getMinecraft();
            if (mc == null) return null;
            
            Method getWorld = MappingUtils.getMethod("Minecraft.getTheWorld");
            if (getWorld != null) {
                getWorld.setAccessible(true);
                return getWorld.invoke(mc);
            }

            Object player = getThePlayer();
            if (player == null) return null;

            Field worldObj = MappingUtils.getField("EntityPlayerSP.worldObj");
            if (worldObj == null) return null;

            worldObj.setAccessible(true);
            return worldObj.get(player);
        } catch (Throwable e) {
            return null;
        }
    }
}

