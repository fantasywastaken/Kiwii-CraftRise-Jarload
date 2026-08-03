package me.kiwii.module.impl;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.StringOption;

public class HudModule extends Module {

    public final BooleanOption showLogo;
    public final BooleanOption showArrayList;
    public final StringOption  arrayListStyle;
    public final BooleanOption showSuffix;
    public final BooleanOption idle;
    public final me.kiwii.setting.NumberOption idleOpacity;
    public final me.kiwii.setting.NumberOption idleTimer;

    public HudModule() {
        super("HUD", "On-screen HUD elements", Category.RENDER, 0);
        this.enabled = true;
        showLogo       = new BooleanOption("Kiwi Logo", true,  this);
        showArrayList  = new BooleanOption("ArrayList", true,  this);
        arrayListStyle = new StringOption ("List Style", "Bar", this, "Simple", "Bar", "Accent");
        showSuffix     = new BooleanOption("Show Suffix", true, this);
        idle           = new BooleanOption("Idle", false, this);
        idleOpacity    = new me.kiwii.setting.NumberOption("Idle Opacity", 60.0D, 10.0D, 255.0D, 5.0D, this);
        idleTimer      = new me.kiwii.setting.NumberOption("Idle Timer",   10.0D,  1.0D,  60.0D, 1.0D, this);
        idleOpacity.setDependency("Idle:true");
        idleTimer.setDependency("Idle:true");
        addOptions(showLogo, showArrayList, arrayListStyle, showSuffix, idle, idleOpacity, idleTimer);
    }

    @Override public void onEnable()  {}
    @Override public void onDisable() {}
    @Override public void onUpdate()  {}

    @Override public void toggle()  {}
    @Override public void enable()  { this.enabled = true; }
    @Override public void disable() {}
}
