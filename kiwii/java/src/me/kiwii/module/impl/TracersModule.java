package me.kiwii.module.impl;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;

public class TracersModule extends Module {

    public final NumberOption range;
    public final NumberOption lineWidth;
    public final me.kiwii.setting.BooleanOption hideInvisible;

    public TracersModule() {
        super("Tracers", "Line from screen bottom to nearby players", Category.RENDER, 0);
        range         = new NumberOption("Range",      64.0D,  8.0D, 256.0D, 8.0D, this);
        lineWidth     = new NumberOption("Line Width",  1.0D,  0.5D,   3.0D, 0.5D, this);
        hideInvisible = new me.kiwii.setting.BooleanOption("Hide Invisible", true, this);
        addOptions(range, lineWidth, hideInvisible);
    }

    @Override public void onEnable()  { Logger.info("Tracers enabled"); }
    @Override public void onDisable() { Logger.info("Tracers disabled"); }
    @Override public void onUpdate()  {}
}
