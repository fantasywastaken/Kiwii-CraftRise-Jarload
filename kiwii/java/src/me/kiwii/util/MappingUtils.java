package me.kiwii.util;

import me.kiwii.mapping.AutoMapper;
import me.kiwii.mapping.MappingConfiguration;
import me.kiwii.mapping.TypeConverter;
import me.kiwii.mapping.TypeReference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;


public class MappingUtils {
    
    
    public static void registerObfuscatedClass(String name, Class<?> clazz) {
        AutoMapper.put(name, clazz);
    }
    
    
    public static void registerClass(String name, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            AutoMapper.put(name, clazz);
        } catch (ClassNotFoundException e) {
            Logger.error("Failed to load class: " + className);
        }
    }
    
    
    public static Class<?> get(String name) {
        return AutoMapper.get(name);
    }
    
    
    public static Field getField(String fullName) {
        return AutoMapper.getField(fullName);
    }
    
    
    public static Method getMethod(String fullName) {
        return AutoMapper.getMethod(fullName);
    }
    
    
    public static boolean isClassLoaded(String name) {
        return AutoMapper.contains(name);
    }
    
    
    public static boolean isFieldMapped(String fullName) {
        return AutoMapper.containsField(fullName);
    }
    
    
    public static boolean isMethodMapped(String fullName) {
        return AutoMapper.containsMethod(fullName);
    }
    
    
    public static void exportMappings(String filePath) {
        AutoMapper.exportToFile(filePath);
    }
    
    
    public static String getStats() {
        return String.format("Classes: %d, Fields: %d, Methods: %d",
            AutoMapper.getClassCount(),
            AutoMapper.getFieldCount(),
            AutoMapper.getMethodCount());
    }

    public static <T> T map(Object source, Class<T> targetType) {
        return AutoMapper.map(source, targetType);
    }

    public static <T> T map(Object source, TypeReference<T> targetType) {
        return AutoMapper.map(source, targetType);
    }

    public static <T> List<T> mapList(Collection<?> source, Class<T> elementType) {
        return AutoMapper.mapList(source, elementType);
    }

    public static Object mapArray(Object source, Class<?> componentType) {
        return AutoMapper.mapArray(source, componentType);
    }

    public static void configure(MappingConfiguration configuration) {
        AutoMapper.setConfiguration(configuration);
    }

    public static void registerConverter(TypeConverter<?, ?> converter) {
        AutoMapper.registerConverter(converter);
    }

    public static String getObjectMappingCacheStats() {
        return AutoMapper.getObjectMappingCacheStats();
    }
}

