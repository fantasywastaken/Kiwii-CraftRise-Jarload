package me.kiwii.module.impl;

import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.notification.NotificationManager;
import me.kiwii.util.Logger;

public class NotificationsModule extends Module {

    public NotificationsModule() {
        super("Notifications", "Show toast notifications on module events", Category.MISC, 0);
        this.enabled = true;
        NotificationManager.setEnabled(true);
    }

    @Override public void onEnable()  { NotificationManager.setEnabled(true);  Logger.info("Notifications enabled"); }
    @Override public void onDisable() { NotificationManager.setEnabled(false); Logger.info("Notifications disabled"); }
    @Override public void onUpdate()  {}
}
