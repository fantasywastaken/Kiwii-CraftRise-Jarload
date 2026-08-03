package me.kiwii.module;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import me.kiwii.event.EventBus;
import me.kiwii.util.GuiHelper;
import me.kiwii.module.impl.AutoFnsModule;
import me.kiwii.module.impl.AutoRodModule;
import me.kiwii.module.impl.ChestEspModule;
import me.kiwii.module.impl.ChestStealerModule;
import me.kiwii.module.impl.FastPlaceModule;
import me.kiwii.module.impl.HudModule;
import me.kiwii.module.impl.InvMoveModule;
import me.kiwii.module.impl.NameTagsModule;
import me.kiwii.module.impl.NotificationsModule;
import me.kiwii.module.impl.PlayerEspModule;
import me.kiwii.module.impl.ReachModule;
import me.kiwii.module.impl.SafeWalkModule;
import me.kiwii.module.impl.TabGuiModule;
import me.kiwii.module.impl.TracersModule;
import me.kiwii.util.Logger;

public class ModuleManager {

    private final List<Module> modules = new ArrayList<Module>();
    private final Map<Module, Boolean> keyStates = new IdentityHashMap<Module, Boolean>();

    public void loadModules() {
        modules.clear();
        keyStates.clear();

        try { addModule(new TabGuiModule());       } catch (Throwable t) { Logger.warn("ModuleManager: TabGUI failed - " + t); }
        try { addModule(new HudModule());          } catch (Throwable t) { Logger.warn("ModuleManager: HUD failed - " + t); }
        try { addModule(new ReachModule());        } catch (Throwable t) { Logger.warn("ModuleManager: Reach failed - " + t); }
        try { addModule(new FastPlaceModule());    } catch (Throwable t) { Logger.warn("ModuleManager: FastPlace failed - " + t); }
        try { addModule(new AutoRodModule());      } catch (Throwable t) { Logger.warn("ModuleManager: AutoRod failed - " + t); }
        try { addModule(new AutoFnsModule());      } catch (Throwable t) { Logger.warn("ModuleManager: AutoFns failed - " + t); }
        try { addModule(new ChestEspModule());     } catch (Throwable t) { Logger.warn("ModuleManager: ChestESP failed - " + t); }
        try { addModule(new ChestStealerModule()); } catch (Throwable t) { Logger.warn("ModuleManager: ChestStealer failed - " + t); }
        try { addModule(new PlayerEspModule());    } catch (Throwable t) { Logger.warn("ModuleManager: PlayerESP failed - " + t); }
        try { addModule(new NameTagsModule());     } catch (Throwable t) { Logger.warn("ModuleManager: NameTags failed - " + t); }
        try { addModule(new SafeWalkModule());     } catch (Throwable t) { Logger.warn("ModuleManager: SafeWalk failed - " + t); }
        try { addModule(new InvMoveModule());      } catch (Throwable t) { Logger.warn("ModuleManager: InvMove failed - " + t); }
        try { addModule(new TracersModule());      } catch (Throwable t) { Logger.warn("ModuleManager: Tracers failed - " + t); }
        try { addModule(new NotificationsModule());} catch (Throwable t) { Logger.warn("ModuleManager: Notifications failed - " + t); }

        Logger.info("ModuleManager: loaded " + modules.size() + " modules");
    }

    public void registerEventListeners(EventBus eventBus) {
    }

    public void suppressKeybind(Module module) {
        if (module != null) keyStates.put(module, Boolean.TRUE);
    }

    public void handleKeyBinds() {
        try {
            if (!Keyboard.isCreated()) return;
            if (GuiHelper.isChatOpen()) return;
            for (Module module : modules) {
                int key = module.getKeyBind();
                if (key <= 0) { keyStates.put(module, Boolean.FALSE); continue; }
                boolean down = Keyboard.isKeyDown(key);
                boolean wasDown = Boolean.TRUE.equals(keyStates.get(module));
                if (down && !wasDown) {
                    Logger.info("ModuleManager: toggling " + module.getName() + " (" + Keyboard.getKeyName(key) + ")");
                    module.toggle();
                }
                keyStates.put(module, Boolean.valueOf(down));
            }
        } catch (Throwable t) {
            Logger.warn("ModuleManager: keybind handling failed - " + t.getMessage());
        }
    }

    public void updateModules() {
        for (Module module : new ArrayList<Module>(modules)) {
            if (!module.isEnabled()) continue;
            try { module.onUpdate(); }
            catch (Throwable t) { Logger.warn("ModuleManager: update failed for " + module.getName() + " - " + t.getMessage()); }
        }
    }

    public void disableAllModules() {
        for (Module module : modules) if (module.isEnabled()) module.disable();
    }

    public void addModule(Module module) {
        modules.add(module);
    }

    public Module getModule(String name) {
        for (Module module : modules) if (module.getName().equalsIgnoreCase(name)) return module;
        return null;
    }

    public <T extends Module> T getModule(Class<T> moduleClass) {
        for (Module module : modules) if (moduleClass.isInstance(module)) return moduleClass.cast(module);
        return null;
    }

    public List<Module> getModules() {
        return new ArrayList<Module>(modules);
    }
}
