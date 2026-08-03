package me.kiwii.ui;

import me.kiwii.util.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Runtime code-gen that creates a subclass of the game's GuiIngame class.
 * The generated subclass overrides the render method to call super.render(...)
 * (draws vanilla HUD) then invokes IngameHud.renderFrame() (draws Kiwii HUD).
 *
 * Installing this subclass into mc.ingameGUI lets our HUD render inside
 * Minecraft's own render pipeline — no C++ wglSwapBuffers hook needed,
 * no per-frame JNI overhead, no frame-limiter side effects.
 *
 * Ported from atapiro's IngameHudFactory.
 */
public final class IngameHudFactory implements Opcodes {

    // Masquerade as a craftrise-native class so anti-cheat class-name checks
    // (e.g. mc.ingameGUI.getClass().getName()) see a plausible-looking name.
    // The AvamHook filter returns the pre-JAR class snapshot from JVMTI, so
    // GetLoadedClasses won't reveal this new class either.
    private static final String HOOK_INTERNAL = "craftrise/ГЩ$Ki";
    private static final String HOOK_BINARY   = "craftrise.ГЩ$Ki";
    private static final String HUD_INTERNAL  = "me/kiwii/ui/IngameHud";

    private static Class<?>       generatedClass;
    private static String         loadedSuperName;
    private static Constructor<?> bestCtor;
    public  static String         debugInfo = "not attempted";

    private IngameHudFactory() {}

    public static synchronized Object wrap(Object mc, Object current) {
        if (current == null) { debugInfo = "current=null"; return null; }
        try {
            Class<?> superClass = current.getClass();
            if (generatedClass == null || loadedSuperName == null || !loadedSuperName.equals(superClass.getName())) {
                generatedClass = defineSubclass(superClass, mc);
                loadedSuperName = superClass.getName();
            }
            if (generatedClass == null) return null;

            Object inst = allocateInstance(generatedClass, mc);
            if (inst == null) { debugInfo = "alloc=null"; return null; }

            copyAllFields(current, inst);
            debugInfo = "ok super=" + superClass.getName() + " gen=" + generatedClass.getName();
            return inst;
        } catch (Throwable t) {
            debugInfo = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            try { Logger.warn("IngameHudFactory wrap fail: " + debugInfo); } catch (Throwable ignored) {}
            return null;
        }
    }

    private static Class<?> defineSubclass(Class<?> superClass, Object mc) throws Exception {
        Method renderM = findBestRenderMethod(superClass);
        if (renderM == null) { debugInfo = "renderMethod=null super=" + superClass.getName(); return null; }

        String superName = Type.getInternalName(superClass);
        bestCtor = pickBestConstructor(superClass, mc);
        if (bestCtor == null) { debugInfo = "ctor=null super=" + superClass.getName(); return null; }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC, HOOK_INTERNAL, null, superName, null);

        // ── Constructor: forward all args to super ──────────────────────────
        Class<?>[] p = bestCtor.getParameterTypes();
        String ctorDesc = Type.getConstructorDescriptor(bestCtor);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        int slot = 1;
        for (Class<?> t : p) {
            emitLoadRaw(mv, t, slot);
            slot += slotSize(t);
            if (!t.isPrimitive() && t != Object.class) {
                mv.visitTypeInsn(CHECKCAST, Type.getInternalName(t));
            }
        }
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", ctorDesc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 1 + computeLocals(p));
        mv.visitEnd();

        // ── render override: super.render(args); IngameHud.renderFrame(); ──
        String name = renderM.getName();
        String renderDesc = Type.getMethodDescriptor(renderM);
        Class<?>[] rp = renderM.getParameterTypes();
        boolean returnsBoolean = renderM.getReturnType() == boolean.class;
        int paramSlots = 1 + computeLocals(rp);
        int superResultSlot = returnsBoolean ? paramSlots : -1;
        int maxLocals = returnsBoolean ? paramSlots + 1 : paramSlots;

        MethodVisitor rmv = cw.visitMethod(ACC_PUBLIC, name, renderDesc, null, null);
        rmv.visitCode();

        // super.render(args)
        rmv.visitVarInsn(ALOAD, 0);
        int rawSlot = 1;
        for (Class<?> t : rp) {
            emitLoadRaw(rmv, t, rawSlot);
            rawSlot += slotSize(t);
        }
        rmv.visitMethodInsn(INVOKESPECIAL, superName, name, renderDesc, false);
        if (returnsBoolean) rmv.visitVarInsn(ISTORE, superResultSlot);

        // try { IngameHud.renderFrame(); } catch (Throwable ignored) {}
        Label tryStart = new Label();
        Label tryEnd   = new Label();
        Label handler  = new Label();
        Label after    = new Label();
        rmv.visitLabel(tryStart);
        rmv.visitMethodInsn(INVOKESTATIC, HUD_INTERNAL, "renderFrame", "()V", false);
        rmv.visitLabel(tryEnd);
        rmv.visitJumpInsn(GOTO, after);
        rmv.visitLabel(handler);
        rmv.visitInsn(POP);
        rmv.visitLabel(after);
        rmv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");

        if (returnsBoolean) {
            rmv.visitVarInsn(ILOAD, superResultSlot);
            rmv.visitInsn(IRETURN);
        } else {
            rmv.visitInsn(RETURN);
        }
        rmv.visitMaxs(6, maxLocals);
        rmv.visitEnd();

        byte[] bytes = cw.toByteArray();
        ClassLoader loader = superClass.getClassLoader();
        if (loader == null) loader = Thread.currentThread().getContextClassLoader();
        return defineClass(bytes, loader);
    }

    private static Class<?> defineClass(byte[] bytes, ClassLoader loader) throws Exception {
        if (loader == null) loader = IngameHudFactory.class.getClassLoader();
        try {
            Method define = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
            define.setAccessible(true);
            return (Class<?>) define.invoke(loader, HOOK_BINARY, bytes, 0, bytes.length);
        } catch (Throwable t) {
            ClassLoader our = IngameHudFactory.class.getClassLoader();
            Method define = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
            define.setAccessible(true);
            return (Class<?>) define.invoke(our, HOOK_BINARY, bytes, 0, bytes.length);
        }
    }

    private static Constructor<?> pickBestConstructor(Class<?> superClass, Object mc) {
        Constructor<?> best = null;
        int bestScore = -1;
        for (Constructor<?> c : superClass.getDeclaredConstructors()) {
            try {
                c.setAccessible(true);
                Class<?>[] p = c.getParameterTypes();
                int score = 0;
                if (p.length == 0) score += 200;
                if (p.length == 1 && mc != null && p[0].isAssignableFrom(mc.getClass())) score += 150;
                if (Modifier.isPublic(c.getModifiers())) score += 10;
                if (score > bestScore) { bestScore = score; best = c; }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static Object allocateInstance(Class<?> cls, Object mc) {
        // Preferred: sun.misc.Unsafe.allocateInstance — no constructor side effects
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
            return alloc.invoke(unsafe, cls);
        } catch (Throwable ignored) {}

        // Fallback: try to invoke the "best" constructor with sensible args
        try {
            if (bestCtor != null) {
                Class<?>[] p = bestCtor.getParameterTypes();
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    Class<?> t = p[i];
                    if (!t.isPrimitive() && mc != null && t.isAssignableFrom(mc.getClass())) args[i] = mc;
                    else args[i] = defaultValue(t);
                }
                Constructor<?> ctor = cls.getDeclaredConstructor(p);
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        } catch (Throwable ignored) {}

        try { return cls.newInstance(); } catch (Throwable ignored) { return null; }
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == byte.class || t == short.class || t == int.class) return 0;
        if (t == char.class) return '\0';
        if (t == long.class) return 0L;
        if (t == float.class) return 0.0F;
        if (t == double.class) return 0.0;
        return null;
    }

    private static void copyAllFields(Object src, Object dst) {
        for (Class<?> cur = src.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    f.set(dst, f.get(src));
                } catch (Throwable ignored) {}
            }
        }
    }

    private static Method findBestRenderMethod(Class<?> c) {
        Method best = null;
        int bestScore = -1;
        for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    Class<?> ret = m.getReturnType();
                    if (ret != void.class && ret != boolean.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p == null || p.length > 6) continue;

                    int score = 0;
                    String n = m.getName() == null ? "" : m.getName().toLowerCase();
                    if (n.contains("render"))  score += 120;
                    if (n.contains("overlay") || n.contains("hud")) score += 120;
                    if (n.contains("game"))    score +=  60;

                    boolean hasFloat = false;
                    int floatCount = 0, intCount = 0, boolCount = 0;
                    for (Class<?> t : p) {
                        if (t == float.class || t == double.class) { hasFloat = true; floatCount++; }
                        else if (t == int.class)     intCount++;
                        else if (t == boolean.class) boolCount++;
                    }
                    if (hasFloat) score += 120;
                    if (p.length == 1 && floatCount == 1) score += 200;
                    if (p.length == 4 && floatCount == 1 && boolCount == 1 && intCount == 2) score += 180;
                    if (p.length == 3 && floatCount == 1 && intCount == 2) score += 140;
                    if (ret == void.class) score += 20;
                    if (Modifier.isPublic(m.getModifiers())) score += 10;

                    if (score > bestScore) { bestScore = score; best = m; }
                } catch (Throwable ignored) {}
            }
        }
        if (best != null) { try { best.setAccessible(true); } catch (Throwable ignored) {} }
        return best;
    }

    private static int slotSize(Class<?> t) {
        return (t == long.class || t == double.class) ? 2 : 1;
    }

    private static int computeLocals(Class<?>[] p) {
        int n = 0;
        for (Class<?> t : p) n += slotSize(t);
        return n;
    }

    private static void emitLoadRaw(MethodVisitor mv, Class<?> t, int idx) {
        if (t == int.class || t == boolean.class || t == byte.class || t == short.class || t == char.class) {
            mv.visitVarInsn(ILOAD, idx);
        } else if (t == float.class) {
            mv.visitVarInsn(FLOAD, idx);
        } else if (t == long.class) {
            mv.visitVarInsn(LLOAD, idx);
        } else if (t == double.class) {
            mv.visitVarInsn(DLOAD, idx);
        } else {
            mv.visitVarInsn(ALOAD, idx);
        }
    }
}
