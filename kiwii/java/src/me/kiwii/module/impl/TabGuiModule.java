package me.kiwii.module.impl;

import java.util.ArrayList;
import java.util.List;

import me.kiwii.Kiwii;
import me.kiwii.module.Category;
import me.kiwii.module.Module;
import me.kiwii.setting.BooleanOption;
import me.kiwii.setting.NumberOption;
import me.kiwii.setting.OptionBase;
import me.kiwii.setting.StringOption;
import org.lwjgl.input.Keyboard;

public class TabGuiModule extends Module {

    public enum State { CATEGORY, MODULE, SETTINGS }

    private State state = State.CATEGORY;
    private int selectedCategory = 0;
    private int selectedModule = 0;
    private int selectedOption = 0;
    private boolean editing = false;
    private boolean waitingForKeybind = false;
    private boolean keybindReady = false;
    private volatile long lastInteractionTime = System.currentTimeMillis();

    private boolean upWas, downWas, leftWas, rightWas, enterWas;

    public TabGuiModule() {
        super("TabGUI", "Category-based module navigation", Category.RENDER, 0);
        this.enabled = true;
    }

    @Override public void toggle() {}
    @Override public void enable()  { this.enabled = true; }
    @Override public void disable() {}
    @Override public void onEnable() {}

    @Override
    public void onDisable() {
        state = State.CATEGORY;
        editing = false;
        waitingForKeybind = false;
    }

    @Override
    public void onUpdate() {
        if (!Keyboard.isCreated()) return;
        if (me.kiwii.util.GuiHelper.isChatOpen()) { updateKeyStates(); return; }

        if (waitingForKeybind) {
            handleKeybindCapture();
            updateKeyStates();
            return;
        }

        boolean up = Keyboard.isKeyDown(Keyboard.KEY_UP);
        boolean down = Keyboard.isKeyDown(Keyboard.KEY_DOWN);
        boolean left = Keyboard.isKeyDown(Keyboard.KEY_LEFT);
        boolean right = Keyboard.isKeyDown(Keyboard.KEY_RIGHT);
        boolean enter = Keyboard.isKeyDown(Keyboard.KEY_RETURN);

        boolean anyAction = (up && !upWas) || (down && !downWas)
                || (left && !leftWas) || (right && !rightWas) || (enter && !enterWas);
        if (anyAction) {
            lastInteractionTime = System.currentTimeMillis();
        }

        Category[] cats = Category.values();

        switch (state) {
            case CATEGORY:
                handleCategoryState(up, down, right, cats);
                break;
            case MODULE:
                handleModuleState(up, down, left, right, enter, cats);
                break;
            case SETTINGS:
                if (editing) {
                    handleEditMode(left, right, enter, cats);
                } else {
                    handleSettingsState(up, down, left, right, enter, cats);
                }
                break;
        }

        updateKeyStates();
    }

    private void handleCategoryState(boolean up, boolean down, boolean right, Category[] cats) {
        if (up && !upWas) {
            selectedCategory--;
            if (selectedCategory < 0) selectedCategory = cats.length - 1;
            selectedModule = 0;
        }
        if (down && !downWas) {
            selectedCategory++;
            if (selectedCategory >= cats.length) selectedCategory = 0;
            selectedModule = 0;
        }
        if (right && !rightWas) {
            List<Module> mods = getModulesForCategory(cats[selectedCategory]);
            if (!mods.isEmpty()) {
                state = State.MODULE;
                selectedModule = 0;
            }
        }
    }

    private void handleModuleState(boolean up, boolean down, boolean left,
                                   boolean right, boolean enter, Category[] cats) {
        List<Module> mods = getModulesForCategory(cats[selectedCategory]);
        if (mods.isEmpty()) { state = State.CATEGORY; return; }

        if (up && !upWas) {
            selectedModule--;
            if (selectedModule < 0) selectedModule = mods.size() - 1;
        }
        if (down && !downWas) {
            selectedModule++;
            if (selectedModule >= mods.size()) selectedModule = 0;
        }
        if (left && !leftWas) {
            state = State.CATEGORY;
        }
        if (enter && !enterWas) {
            if (selectedModule >= 0 && selectedModule < mods.size()) {
                mods.get(selectedModule).toggle();
            }
        }
        if (right && !rightWas) {
            if (selectedModule >= 0 && selectedModule < mods.size()) {
                state = State.SETTINGS;
                selectedOption = 0;
                editing = false;
            }
        }
    }

    private void handleSettingsState(boolean up, boolean down, boolean left,
                                     boolean right, boolean enter, Category[] cats) {
        Module mod = getSelectedModuleObj(cats);
        if (mod == null) { state = State.MODULE; return; }

        List<OptionBase<?>> options = mod.getVisibleOptions();
        int totalEntries = options.size() + 1;

        if (up && !upWas) {
            selectedOption--;
            if (selectedOption < 0) selectedOption = totalEntries - 1;
        }
        if (down && !downWas) {
            selectedOption++;
            if (selectedOption >= totalEntries) selectedOption = 0;
        }
        if (left && !leftWas) {
            state = State.MODULE;
        }
        if (enter && !enterWas) {
            boolean isOnKeybind = selectedOption >= options.size();
            if (isOnKeybind) {
                waitingForKeybind = true;
                keybindReady = false;
            } else {
                editing = true;
            }
        }
    }

    private void handleEditMode(boolean left, boolean right, boolean enter, Category[] cats) {
        if (enter && !enterWas) {
            editing = false;
            return;
        }

        Module mod = getSelectedModuleObj(cats);
        if (mod == null) { editing = false; return; }

        List<OptionBase<?>> options = mod.getVisibleOptions();
        if (selectedOption >= options.size()) { editing = false; return; }

        OptionBase<?> opt = options.get(selectedOption);

        if (left && !leftWas) {
            if (opt instanceof NumberOption) {
                adjustNumber((NumberOption) opt, -1);
            } else if (opt instanceof BooleanOption) {
                ((BooleanOption) opt).toggle();
            } else if (opt instanceof StringOption) {
                cycleString((StringOption) opt, -1);
            }
        }
        if (right && !rightWas) {
            if (opt instanceof NumberOption) {
                adjustNumber((NumberOption) opt, 1);
            } else if (opt instanceof BooleanOption) {
                ((BooleanOption) opt).toggle();
            } else if (opt instanceof StringOption) {
                cycleString((StringOption) opt, 1);
            }
        }
    }

    private void handleKeybindCapture() {
        if (!keybindReady) {
            if (!Keyboard.isKeyDown(Keyboard.KEY_RETURN) && !Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
                keybindReady = true;
            }
            return;
        }

        for (int k = 1; k < 256; k++) {
            if (k == Keyboard.KEY_UP || k == Keyboard.KEY_DOWN) continue;
            try {
                if (Keyboard.isKeyDown(k)) {
                    Category[] cats = Category.values();
                    Module mod = getSelectedModuleObj(cats);
                    if (mod != null) {
                        if (k == Keyboard.KEY_ESCAPE || k == Keyboard.KEY_BACK || k == Keyboard.KEY_DELETE) {
                            mod.setKeyBind(0);
                        } else {
                            mod.setKeyBind(k);
                            try { me.kiwii.Kiwii.getInstance().getModuleManager().suppressKeybind(mod); }
                            catch (Throwable ignored) {}
                        }
                    }
                    waitingForKeybind = false;
                    keybindReady = false;
                    lastInteractionTime = System.currentTimeMillis();
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private void updateKeyStates() {
        upWas = Keyboard.isKeyDown(Keyboard.KEY_UP);
        downWas = Keyboard.isKeyDown(Keyboard.KEY_DOWN);
        leftWas = Keyboard.isKeyDown(Keyboard.KEY_LEFT);
        rightWas = Keyboard.isKeyDown(Keyboard.KEY_RIGHT);
        enterWas = Keyboard.isKeyDown(Keyboard.KEY_RETURN);
    }

    private Module getSelectedModuleObj(Category[] cats) {
        if (selectedCategory < 0 || selectedCategory >= cats.length) return null;
        List<Module> mods = getModulesForCategory(cats[selectedCategory]);
        if (selectedModule < 0 || selectedModule >= mods.size()) return null;
        return mods.get(selectedModule);
    }

    private static void adjustNumber(NumberOption opt, int direction) {
        double val = opt.getValue() + opt.getIncrement() * direction;
        if (val > opt.getMax()) val = opt.getMin();
        if (val < opt.getMin()) val = opt.getMax();
        opt.setValue(val);
    }

    private static void cycleString(StringOption opt, int direction) {
        List<String> modes = opt.getModes();
        int idx = modes.indexOf(opt.getValue());
        idx = (idx + direction + modes.size()) % modes.size();
        opt.setValue(modes.get(idx));
    }

    public State getState() { return state; }
    public int getSelectedCategory() { return selectedCategory; }
    public int getSelectedModule() { return selectedModule; }
    public int getSelectedOption() { return selectedOption; }
    public boolean isEditing() { return editing; }
    public boolean isWaitingForKeybind() { return waitingForKeybind; }
    public long getLastInteractionTime() { return lastInteractionTime; }

    public Module getSelectedModuleForRender() {
        return getSelectedModuleObj(Category.values());
    }

    public static List<Module> getModulesForCategory(Category category) {
        List<Module> result = new ArrayList<Module>();
        for (Module m : Kiwii.getInstance().getModuleManager().getModules()) {
            if (m.getCategory() == category && !(m instanceof TabGuiModule)) {
                result.add(m);
            }
        }
        return result;
    }
}
