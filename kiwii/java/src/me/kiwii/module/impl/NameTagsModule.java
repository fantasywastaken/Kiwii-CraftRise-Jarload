package me.kiwii.module.impl;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.util.Logger;

public class NameTagsModule extends Module {

    public final NumberOption  scale;
    public final BooleanOption showDistance;
    public final BooleanOption showHealthText;
    public final BooleanOption showBackground;
    public final BooleanOption hideInvisible;

    public NameTagsModule() {
        super("NameTags", "Text tags above nearby players", Category.RENDER, 0);
        scale          = new NumberOption ("Scale",         1.0D, 0.5D, 2.0D, 0.1D, this);
        showDistance   = new BooleanOption("Show Distance", true, this);
        showHealthText = new BooleanOption("Show Health",   true, this);
        showBackground = new BooleanOption("Background",    true, this);
        hideInvisible  = new BooleanOption("Hide Invisible", true, this);
        addOptions(scale, showDistance, showHealthText, showBackground, hideInvisible);
    }

    @Override public void onEnable()  { Logger.info("NameTags enabled"); }
    @Override public void onDisable() { Logger.info("NameTags disabled"); }
    @Override public void onUpdate()  {}
}
