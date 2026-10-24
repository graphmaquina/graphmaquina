package io.github.graphmaquina.codegen.output;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import lombok.NonNull;

/**
 * Links the class files a compilation really produced to the compilation units that own them, by reading what
 * each class file says about itself.
 *
 * <p>A build must retire the binaries of a generated type that no longer exists, and javac never does that for
 * it. Deleting by name prefix would be wrong: {@code fx/OldRefs$Manual.class} may well be an application class
 * that has nothing to do with {@code fx.OldRefs}. javac writes every binary of a top-level type {@code p.T},
 * nested, local and anonymous classes included, as {@code p/T.class} or {@code p/T$….class}, so only the files
 * named that way in an owned unit's package directory are candidates. Each candidate is parsed and kept only when
 * its outermost class is one of the owned units and its {@code SourceFile} attribute is that unit's own file
 * name. Any other file under the class output, a resource that merely ends in {@code .class} or the binary of an
 * unrelated type, is never opened. Nothing is loaded, initialized or resolved: the parser reads the constant
 * pool and the {@code SourceFile}, {@code InnerClasses} and {@code EnclosingMethod} attributes, and stops
 * there.</p>
 *
 * <p>{@code java.lang.classfile} is not available at {@code --release 21} and this module carries no bytecode
 * library, so the format is read directly, with {@link ConstantPool} reading the pool this walk resolves its
 * names through. A candidate that is not a class file at all, or that ends before its structure does, is an
 * {@link IOException} naming it: a build cannot decide what it owns from a binary it cannot read.</p>
 */
final class OwnedClassFiles {

    private static final int MAGIC = 0xCAFEBABE;
    private static final String SUFFIX = ".class";
    /** The value {@link ClassFile#outerOf()} carries for a class that declares no outer class of its own. */
    private static final String NO_OUTER = "";

    private OwnedClassFiles() {
    }

    /**
     * Every candidate class file under {@code classesOutput} whose outermost class, as
     * {@link ClassFile#outermost(Map)} traces it through the candidates that stand where javac writes the class
     * they declare, is one of {@code ownedTopLevelNames} and whose {@code SourceFile} is that unit's own file,
     * sorted.
     *
     * @param classesOutput the class output of the compilation; an absent directory contributes nothing
     * @param ownedTopLevelNames the canonical names of the top-level types this compilation owns
     * @throws IOException if a package directory of an owned unit cannot be listed or a class file named after
     *     an owned unit cannot be read
     */
    static List<Path> enumerate(@NonNull Path classesOutput,
            @NonNull Set<String> ownedTopLevelNames) throws IOException {
        if (ownedTopLevelNames.isEmpty() || !Files.isDirectory(classesOutput)) {
            return List.of();
        }
        Map<Path, ClassFile> parsed = new LinkedHashMap<>();
        Map<String, ClassFile> declaring = new HashMap<>();
        for (Path file : candidates(classesOutput, ownedTopLevelNames)) {
            ClassFile classFile = read(file);
            parsed.put(file, classFile);
            String relative = classesOutput.relativize(file).toString()
                    .replace(file.getFileSystem().getSeparator(), "/");
            if (relative.equals(classFile.thisClass().replace('.', '/') + SUFFIX)) {
                declaring.put(classFile.thisClass(), classFile);
            }
        }
        List<Path> owned = new ArrayList<>();
        for (Map.Entry<Path, ClassFile> candidate : parsed.entrySet()) {
            Optional<String> outermost = candidate.getValue().outermost(declaring);
            if (outermost.isPresent() && ownedTopLevelNames.contains(outermost.get())
                    && candidate.getValue().sourceFile().equals(fileNameOf(outermost.get()))) {
                owned.add(candidate.getKey());
            }
        }
        return List.copyOf(owned);
    }

    /**
     * The files javac can have written for one of {@code ownedTopLevelNames}, in a stable order: those named
     * {@code T.class} or {@code T$….class} in the package directory of an owned {@code p.T}. A package directory
     * is only looked for through real directories below the class output, as a walk of it would reach them.
     */
    private static List<Path> candidates(Path classesOutput, Set<String> ownedTopLevelNames) throws IOException {
        Map<Path, Set<String>> simpleNamesByDirectory = new HashMap<>();
        for (String name : ownedTopLevelNames) {
            int separator = name.lastIndexOf('.');
            String packageName = separator < 0 ? "" : name.substring(0, separator);
            Optional<Path> directory = packageDirectory(classesOutput, packageName);
            if (directory.isPresent()) {
                simpleNamesByDirectory.computeIfAbsent(directory.get(), (Path unused) -> new HashSet<>())
                        .add(name.substring(separator + 1));
            }
        }
        List<Path> candidates = new ArrayList<>();
        for (Map.Entry<Path, Set<String>> entry : simpleNamesByDirectory.entrySet()) {
            try (Stream<Path> files = Files.list(entry.getKey())) {
                files.filter((Path file) -> namedAfter(file, entry.getValue()))
                        .filter(Files::isRegularFile)
                        .forEach(candidates::add);
            }
        }
        candidates.sort(Comparator.naturalOrder());
        return candidates;
    }

    /**
     * The directory of {@code packageName} under {@code root}, absent when one of its segments is not a Java
     * identifier, so no name can lead outside the class output, or is not a directory of its own there.
     */
    private static Optional<Path> packageDirectory(Path root, String packageName) {
        Path directory = root;
        if (!packageName.isEmpty()) {
            for (String segment : packageName.split("\\.", -1)) {
                if (!SourceVersion.isIdentifier(segment)) {
                    return Optional.empty();
                }
                directory = directory.resolve(segment);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(directory);
    }

    /** Whether {@code file} is named {@code T.class} or {@code T$….class} for one of {@code simpleNames}. */
    private static boolean namedAfter(Path file, Set<String> simpleNames) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(SUFFIX)) {
            return false;
        }
        String binaryName = fileName.substring(0, fileName.length() - SUFFIX.length());
        for (String simpleName : simpleNames) {
            if (binaryName.equals(simpleName) || binaryName.startsWith(simpleName + "$")) {
                return true;
            }
        }
        return false;
    }

    /** The simple source file name a top-level type is declared in, for example {@code DocsGraph.java}. */
    private static String fileNameOf(String canonicalName) {
        return canonicalName.substring(canonicalName.lastIndexOf('.') + 1) + ".java";
    }

    /**
     * What one class file says about its identity.
     *
     * @param thisClass the binary name of the class this file declares, with dots for slashes, such as
     *     {@code fx.DocsGraph$Definition}
     * @param sourceFile the value of the {@code SourceFile} attribute, empty when the file carries none
     * @param outerOf every nested class the file names, mapped to its outer class or to {@link #NO_OUTER}
     * @param enclosing the binary name of the class {@code EnclosingMethod} names, empty when there is none
     */
    private record ClassFile(String thisClass, String sourceFile, Map<String, String> outerOf,
            String enclosing) {

        /**
         * The top-level class this file belongs to, when a chain of declarations leads from its own class to one.
         * Each class the chain reaches is read in the class file that declares it: this file for its own class,
         * the one {@code declaring} maps it to for any other. A class steps out to the outer class its
         * {@code InnerClasses} entry names: the entry in its own class file or, when that file is missing or holds
         * none, the entry in this file. When that entry names no outer class, as for an anonymous, local or
         * synthetic class, it steps out to the class its {@code EnclosingMethod} names, which only its own class file
         * carries. A class that neither file holds an entry for is the top-level class. A member class of a local
         * class, or a local class inside an anonymous one, is therefore traced through the class files of the classes
         * around it. A chain leads nowhere, and yields nothing, when it reaches a class whose entry names no outer
         * class and whose own class file is missing or names no enclosing class, or when it comes back to a class it
         * already reached; such a file is left alone rather than deleted on a guess.
         *
         * @param declaring the class files a chain may continue in, by the binary name, with dots for slashes, of
         *     the class each one declares
         */
        Optional<String> outermost(Map<String, ClassFile> declaring) {
            Set<String> reached = new HashSet<>();
            String current = thisClass;
            while (reached.add(current)) {
                ClassFile own = current.equals(thisClass) ? this : declaring.get(current);
                String outer = own != null && own.outerOf().containsKey(current)
                        ? own.outerOf().get(current)
                        : outerOf.get(current);
                if (outer == null) {
                    return Optional.of(current);
                }
                if (!NO_OUTER.equals(outer)) {
                    current = outer;
                } else if (own != null && !own.enclosing().isEmpty()) {
                    current = own.enclosing();
                } else {
                    return Optional.empty();
                }
            }
            return Optional.empty();
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
