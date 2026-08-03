package me.kiwii.setting;

import me.kiwii.module.Module;

public abstract class OptionBase<T> {
    private final String name;
    private final T defaultValue;
    private final Module module;
    private String group = "";
    private String dependency = "";
    private java.util.function.BooleanSupplier visibility;
    protected T value;

    protected OptionBase(String name, T defaultValue, Module module) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.module = module;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    private static final long SETTING_NOTIF_MUTE_UNTIL = System.currentTimeMillis() + 4000L;

    public void setValue(T value) {
        T old = this.value;
        this.value = value;
        me.kiwii.config.ConfigManager.markDirty();
        boolean changed = (old == null) ? (value != null) : !old.equals(value);
        if (changed && System.currentTimeMillis() >= SETTING_NOTIF_MUTE_UNTIL) {
            try {
                String modName = module != null ? module.getName() : "Settings";
                me.kiwii.notification.NotificationManager.postInfo(modName, name + ": " + formatValue());
            } catch (Throwable ignored) {}
        }
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public Module getModule() {
        return module;
    }

    public String getGroup() {
        return group;
    }

    public OptionBase<T> setGroup(String group) {
        this.group = group == null ? "" : group;
        return this;
    }

    public String getDependency() {
        return dependency;
    }

    public OptionBase<T> setDependency(String dependency) {
        this.dependency = dependency == null ? "" : dependency;
        return this;
    }

    public Object toConfigValue() {
        return value;
    }

    public OptionBase<T> setVisibility(java.util.function.BooleanSupplier v) {
        this.visibility = v;
        return this;
    }

    public boolean isVisible() {
        if (visibility == null) return true;
        try { return visibility.getAsBoolean(); }
        catch (Throwable t) { return true; }
    }

    public String formatValue() {
        return value == null ? "null" : String.valueOf(value);
    }

    public abstract void fromConfigValue(Object value);
}
