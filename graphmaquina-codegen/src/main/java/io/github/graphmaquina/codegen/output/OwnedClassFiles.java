package io.github.graphmaquina.codegen.output;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Links the class files a compilation really produced to the compilation units that own them, by reading what
 * each class file says about itself.
 *
 * <p>A build must retire the binaries of a generated type that no longer exists, and javac never does that for
 * it. Deleting by name prefix would be wrong: {@code fx/OldRefs$Manual.class} may well be an application class
 * that has nothing to do with {@code fx.OldRefs}. So every {@code .class} file under the class output is parsed
 * and kept only when its outermost class is one of the owned units and its {@code SourceFile} attribute is that
 * unit's own file name. Nothing is loaded, initialized or resolved: the parser reads the constant pool and the
 * {@code SourceFile}, {@code InnerClasses} and {@code EnclosingMethod} attributes, and stops there.</p>
 *
 * <p>{@code java.lang.classfile} is not available at {@code --release 21} and this module carries no bytecode
 * library, so the format is read directly, with {@link ConstantPool} reading the pool this walk resolves its
 * names through. A file that is not a class file at all, or that ends before its structure does, is an
 * {@link IOException} naming it: a build cannot decide what it owns from a class output it cannot read.</p>
 */
final class OwnedClassFiles {

    private static final int MAGIC = 0xCAFEBABE;
    private static final String SUFFIX = ".class";
    /** The value {@link ClassFile#outerOf()} carries for a class that declares no outer class of its own. */
    private static final String NO_OUTER = "";

    private OwnedClassFiles() {
    }

    /**
     * Every class file under {@code classesOutput} that belongs to one of {@code ownedTopLevelNames}, sorted.
     *
     * @param classesOutput the class output of the compilation; an absent directory contributes nothing
     * @param ownedTopLevelNames the canonical names of the top-level types this compilation owns
     * @throws IOException if the class output cannot be walked or one of its class files cannot be read
     */
    static List<Path> enumerate(Path classesOutput, Set<String> ownedTopLevelNames) throws IOException {
        Objects.requireNonNull(classesOutput, "classesOutput");
        Objects.requireNonNull(ownedTopLevelNames, "ownedTopLevelNames");
        if (ownedTopLevelNames.isEmpty() || !Files.isDirectory(classesOutput)) {
            return List.of();
        }
        List<Path> owned = new ArrayList<>();
        for (Path file : classFiles(classesOutput)) {
            ClassFile parsed = read(file);
            String outermost = parsed.outermost();
            if (ownedTopLevelNames.contains(outermost) && parsed.sourceFile().equals(fileNameOf(outermost))) {
                owned.add(file);
            }
        }
        return List.copyOf(owned);
    }

    /** The {@code .class} files under {@code root}, in a stable order. */
    private static List<Path> classFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    /** The simple source file name a top-level type is declared in, for example {@code DocsGraph.java}. */
    private static String fileNameOf(String canonicalName) {
        return canonicalName.substring(canonicalName.lastIndexOf('.') + 1) + ".java";
    }

    /**
     * What one class file says about its identity.
     *
     * @param thisClass the canonical name of the class this file declares
     * @param sourceFile the value of the {@code SourceFile} attribute, empty when the file carries none
     * @param outerOf every nested class the file names, mapped to its outer class or to {@link #NO_OUTER}
     * @param enclosing the canonical name of the class {@code EnclosingMethod} names, empty when there is none
     */
    private record ClassFile(String thisClass, String sourceFile, Map<String, String> outerOf,
            String enclosing) {

        /**
         * The top-level class this file belongs to, followed outwards through {@code InnerClasses} and, for an
         * anonymous, local or synthetic class those entries name no outer class for, through
         * {@code EnclosingMethod}. A chain that leads nowhere yields the last name reached, which matches no
         * owned unit, so such a file is left alone rather than deleted on a guess.
         */
        String outermost() {
            String current = thisClass;
            for (int step = 0; step <= outerOf.size(); step++) {
                String outer = outerOf.get(current);
                if (outer == null) {
                    return current;
                }
                if (NO_OUTER.equals(outer)) {
                    if (!current.equals(thisClass) || enclosing.isEmpty()) {
                        return current;
                    }
                    current = enclosing;
                } else {
                    current = outer;
                }
            }
            return current;
        }
    }

    /** Parses {@code file} far enough to answer what it declares, and no further. */
    private static ClassFile read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
                DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
            if (data.readInt() != MAGIC) {
                throw new IOException("not a class file: " + file);
            }
            data.readUnsignedShort();
            data.readUnsignedShort();
            ConstantPool pool = ConstantPool.read(data, file);
            data.readUnsignedShort();
            String thisClass = pool.className(data.readUnsignedShort(), file);
            data.readUnsignedShort();
            skipShorts(data, data.readUnsignedShort());
            skipMembers(data, file);
            skipMembers(data, file);
            return attributes(data, pool, thisClass, file);
        } catch (EOFException failure) {
            throw new IOException("truncated class file: " + file, failure);
        }
    }

    private static void skipShorts(DataInputStream data, int count) throws IOException {
        data.skipNBytes(2L * count);
    }

    /** Skips the fields or the methods of a class, each with its own attributes. */
    private static void skipMembers(DataInputStream data, Path file) throws IOException {
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            skipShorts(data, 3);
            int attributes = data.readUnsignedShort();
            for (int attribute = 0; attribute < attributes; attribute++) {
                data.readUnsignedShort();
                data.skipNBytes(length(data, file));
            }
        }
    }

    /** Reads the attributes of the class itself, keeping the three that say where its declaration lives. */
    private static ClassFile attributes(DataInputStream data, ConstantPool pool, String thisClass, Path file)
            throws IOException {
        String sourceFile = "";
        String enclosing = "";
        Map<String, String> outerOf = new HashMap<>();
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = pool.utf8(data.readUnsignedShort(), file);
            byte[] body = data.readNBytes((int) length(data, file));
            switch (name) {
                case "SourceFile" -> sourceFile = pool.utf8(shortAt(body, 0, file), file);
                case "EnclosingMethod" -> enclosing = pool.className(shortAt(body, 0, file), file);
                case "InnerClasses" -> readInnerClasses(body, pool, outerOf, file);
                default -> { }
            }
        }
        return new ClassFile(thisClass, sourceFile, Map.copyOf(outerOf), enclosing);
    }

    /** Maps every nested class the file names to its outer class, or to {@link #NO_OUTER} when it has none. */
    private static void readInnerClasses(byte[] body, ConstantPool pool, Map<String, String> outerOf, Path file)
            throws IOException {
        int count = shortAt(body, 0, file);
        for (int index = 0; index < count; index++) {
            int offset = 2 + index * 8;
            String inner = pool.className(shortAt(body, offset, file), file);
            int outer = shortAt(body, offset + 2, file);
            outerOf.put(inner, outer == 0 ? NO_OUTER : pool.className(outer, file));
        }
    }

    /** The unsigned 16-bit value of {@code body} at {@code offset}. */
    private static int shortAt(byte[] body, int offset, Path file) throws IOException {
        if (offset < 0 || offset + 2 > body.length) {
            throw new IOException("truncated class file attribute in " + file);
        }
        return ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
    }

    /** The length of the attribute that starts here, refused when it does not fit in this JVM's arrays. */
    private static long length(DataInputStream data, Path file) throws IOException {
        long length = Integer.toUnsignedLong(data.readInt());
        if (length > Integer.MAX_VALUE) {
            throw new IOException("class file attribute of " + length + " bytes in " + file);
        }
        return length;
    }
}
