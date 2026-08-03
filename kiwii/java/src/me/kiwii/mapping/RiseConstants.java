package me.kiwii.mapping;

public final class RiseConstants {

    private RiseConstants() {}

    public static final String[] CLIENT_UTILS_CLASS_HINTS = { "crsecond." };
    public static final String   HUD_HOOK_INTERNAL         = "craftrise/ГЩ$Ki";

    public static final int C0A_ANIMATION_PACKET_ID = 0x0A;
    public static final int C0D_CLOSE_WINDOW_ID     = 0x0D;
    public static final int C0E_WINDOW_CLICK_ID     = 0x0E;
    public static final int C02_USE_ENTITY_ID       = 0x02;
    public static final int C05_PLAYER_LOOK_ID      = 0x05;

    public static final int MAIN_INVENTORY_SIZE = 36;
    public static final int ARMOR_INVENTORY_SIZE = 4;
    public static final int PLAYER_CONTAINER_TOTAL_SLOTS = 45;

    public static final float DEFAULT_HIT_HEIGHT_MIN = 1.10f;
    public static final float DEFAULT_HIT_HEIGHT_JITTER = 0.65f;

    public static final int WM_LBUTTONDOWN = 0x0201;
    public static final int WM_LBUTTONUP   = 0x0202;
    public static final int WM_RBUTTONDOWN = 0x0204;
    public static final int WM_RBUTTONUP   = 0x0205;

    public static final long JVM_GetEnv_JDK8_OFFSET             = 0x144080L;
    public static final long JNI_GetCreatedJavaVMs_JDK8_OFFSET  = 0x13a7a0L;
}
