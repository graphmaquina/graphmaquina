package io.github.graphmaquina.codegen.output;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The constant pool of one class file, kept only as far as this reader needs it: the UTF-8 entries and,
 * for every {@code CONSTANT_Class}, the index of the UTF-8 entry naming it.
 *
 * @param utf8 the UTF-8 entries, indexed by constant pool index
 * @param classNameIndex for a {@code CONSTANT_Class} entry, the index of the UTF-8 entry naming it
 */
record ConstantPool(String[] utf8, int[] classNameIndex) {

    private static final int UTF8 = 1;
    private static final int INTEGER = 3;
    private static final int FLOAT = 4;
    private static final int LONG = 5;
    private static final int DOUBLE = 6;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELD_REF = 9;
    private static final int METHOD_REF = 10;
    private static final int INTERFACE_METHOD_REF = 11;
    private static final int NAME_AND_TYPE = 12;
    private static final int METHOD_HANDLE = 15;
    private static final int METHOD_TYPE = 16;
    private static final int DYNAMIC = 17;
    private static final int INVOKE_DYNAMIC = 18;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;

    /** Reads the pool, honouring that a {@code Long} or a {@code Double} takes two of its slots. */
    static ConstantPool read(DataInputStream data, Path file) throws IOException {
        int count = data.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int index = 1; index < count; index++) {
            int tag = data.readUnsignedByte();
            switch (tag) {
                case UTF8 -> utf8[index] = data.readUTF();
                case CLASS -> classNameIndex[index] = data.readUnsignedShort();
                case STRING, METHOD_TYPE, MODULE, PACKAGE -> data.skipNBytes(2);
                case INTEGER, FLOAT, FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE,
                        DYNAMIC, INVOKE_DYNAMIC -> data.skipNBytes(4);
                case METHOD_HANDLE -> data.skipNBytes(3);
                case LONG, DOUBLE -> {
                    data.skipNBytes(8);
                    index++;
                }
                default -> throw new IOException(
                    "unknown constant pool tag " + tag + " at entry " + index + " in " + file);
            }
        }
        return new ConstantPool(utf8, classNameIndex);
    }

    /** The UTF-8 entry at {@code index}. */
    String utf8(int index, Path file) throws IOException {
        if (index <= 0 || index >= utf8.length || utf8[index] == null) {
            throw new IOException("constant pool entry " + index + " is not a string in " + file);
        }
        return utf8[index];
    }

    /** The canonical name of the class the {@code CONSTANT_Class} entry at {@code index} names. */
    String className(int index, Path file) throws IOException {
        if (index <= 0 || index >= classNameIndex.length || classNameIndex[index] == 0) {
            throw new IOException("constant pool entry " + index + " is not a class in " + file);
        }
        return utf8(classNameIndex[index], file).replace('/', '.');
    }
}
