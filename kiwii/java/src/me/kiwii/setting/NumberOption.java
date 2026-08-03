package me.kiwii.setting;

import me.kiwii.module.Module;

public final class NumberOption extends OptionBase<Double> {
    private final double min;
    private final double max;
    private final double increment;

    public NumberOption(String name, double defaultValue, double min, double max, double increment, Module module) {
        super(name, defaultValue, module);
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.increment = increment <= 0.0D ? 1.0D : increment;
        setValue(defaultValue);
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getIncrement() {
        return increment;
    }

    @Override
    public void setValue(Double value) {
        if (value == null) {
            return;
        }
        double clamped = Math.max(min, Math.min(max, value));
        double stepped = Math.round(clamped / increment) * increment;
        super.setValue(Math.max(min, Math.min(max, stepped)));
    }

    @Override
    public String formatValue() {
        Double v = getValue();
        if (v == null) return "null";
        double d = v.doubleValue();
        if (increment >= 1.0D) return String.valueOf((int) d);
        if (increment >= 0.1D) return String.format(java.util.Locale.ROOT, "%.1f", d);
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    @Override
    public void fromConfigValue(Object value) {
        if (value instanceof Number) {
            setValue(((Number) value).doubleValue());
        } else if (value instanceof String) {
            try {
                setValue(Double.parseDouble((String) value));
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
