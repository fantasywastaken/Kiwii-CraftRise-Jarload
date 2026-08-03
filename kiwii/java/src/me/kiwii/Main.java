package me.kiwii;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import me.kiwii.loader.CraftRiseTransformerClassLoader;
import me.kiwii.mapping.MinecraftMapper;
import me.kiwii.util.ChatUtils;
import me.kiwii.util.HardcodedUtils;
import me.kiwii.util.Logger;
import me.kiwii.util.MappingUtils;

public class Main {

    
    public static final Runnable SAFERUNNABLE = () -> {  };

    private static List<Class<?>> jclasses;

    
    public static void StartClient(List<Class<?>> classes) {
        jclasses = classes;
        try {
            System.out.println("[KIWII] StartClient called from C++");
            System.out.println("[KIWII] Classes count: " + classes.size());
            
            Logger.info("StartClient called from C++");
            Logger.info("Loaded classes count: " + classes.size());
            

            java.io.File logsDir = new java.io.File("C:\\kiwii\\logs");
            if (!logsDir.exists()) logsDir.mkdirs();

            FileWriter writer = new FileWriter("C:\\kiwii\\logs\\jar_loaded.txt");
            writer.write("JAR LOADED SUCCESSFULLY!\n");
            writer.write("Timestamp: " + System.currentTimeMillis() + "\n");
            writer.write("Classes loaded: " + classes.size() + "\n");
            writer.close();

            Logger.info("Installing CraftRise transformer classloader...");
            CraftRiseTransformerClassLoader.install(classes);
            

            Logger.info("Starting AutoMapper scan...");
            MinecraftMapper.scanAndMap(classes);
            Logger.info("AutoMapper scan complete");
            Logger.info("Mapping stats: " + MappingUtils.getStats());


            Logger.info("Applying hardcoded mappings...");
            HardcodedUtils.apply();
            Logger.info("Mapping stats after hardcoded: " + MappingUtils.getStats());

            System.out.println("[+] JAR loaded - mappings created");




            Logger.info("Installing anti-cheat bypass...");
            installAntiCheatBypass();


            Logger.info("Initializing Kiwii...");
            Kiwii.initialize();
            

            Logger.info("StartClient completed");
            System.out.println("[KIWII] StartClient completed successfully");
            
        } catch (IOException e) {
            System.err.println("[KIWII ERROR] IO Error: " + e.getMessage());
            Logger.error("IO Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Throwable e) {
            System.err.println("[KIWII ERROR] Error in StartClient: " + e.getMessage());
            Logger.error("Error in StartClient: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void main(String[] args) {
        Logger.info("Main method called (not used by C++ loader)");
    }

    
    private static void installAntiCheatBypass() {
        try {
            MinecraftMapper.put("main", Main.class);
            try {
                MinecraftMapper.putField("main.SAFE_RUNNABLE",
                        Main.class.getDeclaredField("SAFERUNNABLE"));
            } catch (NoSuchFieldException | SecurityException e) {
                Logger.error("SAFERUNNABLE field lookup failed: " + e);
            }
            MinecraftMapper.ensureEntityPlayerSPClass_public();
            MinecraftMapper.discoverClientUtils(jclasses);

            boolean swapped = MinecraftMapper.swapAntiCheatRunnable();
            if (!swapped) {
                Logger.warn("[Main] anti-cheat bypass swap FAILED — AC Runnable still live");
            } else {
                Logger.info("[Main] anti-cheat bypass installed — AC Runnable neutralised");
            }




            int loaderSwaps = MinecraftMapper.swapLoaderRunnables();
            Logger.info("[Main] loader Runnable swaps: " + loaderSwaps);
        } catch (Throwable t) {
            Logger.error("[Main] antiCheat bypass failed: " + t);
        }
    }
}
