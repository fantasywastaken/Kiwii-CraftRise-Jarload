package me.kiwii.loader;

public interface BytecodeTransformer {
    String getName();

    boolean matches(TransformerContext context);

    byte[] transform(TransformerContext context) throws Exception;
}
