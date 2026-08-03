package me.kiwii;

import me.kiwii.event.EventBus;
import me.kiwii.module.ModuleManager;
import me.kiwii.packethook.PacketHook;
import me.kiwii.util.Logger;

public class Kiwii {

    private static Kiwii instance;
    private final EventBus eventBus;
    private final ModuleManager moduleManager;
    private volatile boolean initialized;

    private Kiwii() {
        this.eventBus = new EventBus();
        this.moduleManager = new ModuleManager();
    }

    public static void initialize() {
        if (instance == null) instance = new Kiwii();
        instance.init();
    }

    private void init() {
        try {
            moduleManager.loadModules();
            moduleManager.registerEventListeners(eventBus);
            PacketHook.get().init();
            me.kiwii.config.ConfigManager.getInstance().attachAutoSave(moduleManager);
            initialized = true;
            startEventLoop();
            int count = moduleManager.getModules().size();
            Logger.info("Kiwii initialized (" + count + " modules)");
            try {
                me.kiwii.notification.NotificationManager.post("Kiwii",
                        count + " modules ready",
                        me.kiwii.notification.Notification.Type.INFO, 10000);
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            initialized = false;
            Logger.error("Failed to initialize Kiwii: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startEventLoop() {
        Thread eventThread = new Thread(() -> {
            while (initialized) {
                try {
                    moduleManager.handleKeyBinds();
                    moduleManager.updateModules();
                    me.kiwii.util.BotTracker.observeAll();
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    Logger.warn("Kiwii event loop error: " + t.getMessage());
                }
            }
        });
        eventThread.setName("Kiwii-EventLoop");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    public static Kiwii getInstance() {
        if (instance == null) instance = new Kiwii();
        return instance;
    }

    public EventBus getEventBus() { return eventBus; }
    public ModuleManager getModuleManager() { return moduleManager; }
    public boolean isInitialized() { return initialized; }

    public void shutdown() {
        initialized = false;
        PacketHook.get().shutdown();
        moduleManager.disableAllModules();
    }
}
