package io.github.graphmaquina.processor;

/**
 * Sources of enums whose constants are a letter and a running number ({@code S0, S1, S2, ...}), for tests that
 * generate a machine of the size they choose.
 */
final class EnumSources {

    private EnumSources() { }

    /** The enum {@code name} of {@code packageName} whose {@code count} constants are {@code prefix} and an index. */
    static String numbered(String packageName, String name, char prefix, int count) {
        StringBuilder source = new StringBuilder("package " + packageName + ";\npublic enum " + name + " { ");
        for (int index = 0; index < count; index++) {
            source.append(index == 0 ? "" : ", ").append(prefix).append(index);
        }
        return source.append(" }\n").toString();
    }
}
