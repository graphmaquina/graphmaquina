package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies which class files {@link OwnedClassFiles} attributes to an owned unit: on class files javac really wrote,
 * every binary of the unit, however deep the chain of member, local and anonymous classes that leads from it to the
 * unit, and none of a top-level class whose name merely starts like the unit's; on hand-built class files whose
 * chains lead back to a class they already reached, none.
 */
class OwnedClassFilesTest {

    @TempDir
    Path directory;

    /**
     * A member class of a local class, and a local or anonymous class inside an anonymous or local one, reach the
     * unit only through the {@code EnclosingMethod} of another class file than their own, which is followed too.
     */
    @Test
    void attributesEveryBinaryOfAUnitThroughChainsOfLocalAndAnonymousClasses() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Docs", """
                package fx;
                final class Docs {
                    static final class Transition { }
                    void local() {
                        class Outer {
                            class Member {
                                class Deep { }
                                Object anonymous = new Object() { };
                            }
                        }
                    }
                    Runnable task = new Runnable() {
                        @Override
                        public void run() {
                            class Local {
                                Object anonymous = new Object() { };
                            }
                        }
                    };
                }
                """);
        sources.put("fx.Docs$Manual", """
                package fx;
                final class Docs$Manual {
                    void local() {
                        class Helper {
                            class Member { }
                        }
                    }
                }
                """);
        Path classes = directory.resolve("classes");
        SourceFiles.Compilation compilation =
                SourceFiles.compileStrict(SourceFiles.write(directory.resolve("src"), sources), classes);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(new TreeSet<>(List.of("Docs$1$1Local$1.class", "Docs$1$1Local.class", "Docs$1.class",
                "Docs$1Outer$Member$1.class", "Docs$1Outer$Member$Deep.class", "Docs$1Outer$Member.class",
                "Docs$1Outer.class", "Docs$Manual$1Helper$Member.class", "Docs$Manual$1Helper.class",
                "Docs$Manual.class", "Docs$Transition.class", "Docs.class")), names(classes.resolve("fx")));

        List<Path> owned = OwnedClassFiles.enumerate(classes, Set.of("fx.Docs"));

        assertEquals(new TreeSet<>(List.of("Docs$1$1Local$1.class", "Docs$1$1Local.class", "Docs$1.class",
                "Docs$1Outer$Member$1.class", "Docs$1Outer$Member$Deep.class", "Docs$1Outer$Member.class",
                "Docs$1Outer.class", "Docs$Transition.class", "Docs.class")), fileNames(owned));
        assertEquals(new TreeSet<>(List.of("Docs$Manual$1Helper$Member.class", "Docs$Manual$1Helper.class",
                "Docs$Manual.class")), fileNames(OwnedClassFiles.enumerate(classes, Set.of("fx.Docs$Manual"))));
    }

    /**
     * Class files that javac would never write, whose {@code InnerClasses} and {@code EnclosingMethod} lead from
     * each one to the other and back, lead to no unit: the walk ends, and neither file is attributed.
     */
    @Test
    void attributesNoBinaryWhoseChainComesBackToAClassItAlreadyReached() throws IOException {
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes.resolve("fx"));
        Files.write(classes.resolve("fx/Docs$1.class"),
                classFile("fx/Docs$1", "Docs.java", Map.of("fx/Docs$1", ""), "fx/Docs$2"));
        Files.write(classes.resolve("fx/Docs$2.class"),
                classFile("fx/Docs$2", "Docs.java", Map.of("fx/Docs$2", "fx/Docs$1", "fx/Docs$1", ""), ""));

        List<Path> owned = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> OwnedClassFiles.enumerate(classes, Set.of("fx.Docs")));

        assertEquals(List.of(), owned);
    }

    private static Set<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return fileNames(files.toList());
        }
    }

    private static Set<String> fileNames(List<Path> files) {
        Set<String> names = new TreeSet<>();
        for (Path file : files) {
            names.add(file.getFileName().toString());
        }
        return names;
    }

    /**
     * The bytes of a class file that declares {@code thisClass} and carries no member: its {@code SourceFile} is
     * {@code sourceFile}, its {@code InnerClasses} map each class of {@code outerOf} to its outer class, none for an
     * empty one, and its {@code EnclosingMethod}, present when {@code enclosing} is not empty, names that class.
     * Class names are internal ones, with slashes.
     */
    private static byte[] classFile(String thisClass, String sourceFile, Map<String, String> outerOf,
            String enclosing) throws IOException {
        Pool pool = new Pool();
        int self = pool.type(thisClass);
        int superClass = pool.type("java/lang/Object");
        ByteArrayOutputStream attributeBytes = new ByteArrayOutputStream();
        DataOutputStream attributes = new DataOutputStream(attributeBytes);
        int count = 1;
        writeSourceFile(attributes, pool, sourceFile);
        if (!outerOf.isEmpty()) {
            count++;
            writeInnerClasses(attributes, pool, outerOf);
        }
        if (!enclosing.isEmpty()) {
            count++;
            writeEnclosingMethod(attributes, pool, enclosing);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(65);
        out.writeShort(pool.next);
        pool.entries.writeTo(out);
        out.writeShort(0x0020);
        out.writeShort(self);
        out.writeShort(superClass);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(count);
        attributeBytes.writeTo(out);
        return bytes.toByteArray();
    }

    /** The {@code SourceFile} attribute naming {@code sourceFile}. */
    private static void writeSourceFile(DataOutputStream attributes, Pool pool, String sourceFile)
            throws IOException {
        attributes.writeShort(pool.utf8("SourceFile"));
        attributes.writeInt(2);
        attributes.writeShort(pool.utf8(sourceFile));
    }

    /**
     * The {@code InnerClasses} attribute: each class of {@code outerOf} with its outer class, or with none for an
     * entry whose outer class is empty.
     */
    private static void writeInnerClasses(DataOutputStream attributes, Pool pool, Map<String, String> outerOf)
            throws IOException {
        attributes.writeShort(pool.utf8("InnerClasses"));
        attributes.writeInt(2 + 8 * outerOf.size());
        attributes.writeShort(outerOf.size());
        for (Map.Entry<String, String> entry : new TreeMap<>(outerOf).entrySet()) {
            attributes.writeShort(pool.type(entry.getKey()));
            attributes.writeShort(entry.getValue().isEmpty() ? 0 : pool.type(entry.getValue()));
            attributes.writeShort(0);
            attributes.writeShort(0);
        }
    }

    /** The {@code EnclosingMethod} attribute naming the class {@code enclosing}. */
    private static void writeEnclosingMethod(DataOutputStream attributes, Pool pool, String enclosing)
            throws IOException {
        attributes.writeShort(pool.utf8("EnclosingMethod"));
        attributes.writeInt(4);
        attributes.writeShort(pool.type(enclosing));
        attributes.writeShort(0);
    }

    /** The constant pool of a class file being written: each string and each class entered once. */
    private static final class Pool {

        private final ByteArrayOutputStream entries = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(entries);
        private final Map<String, Integer> strings = new HashMap<>();
        private final Map<String, Integer> types = new HashMap<>();
        private int next = 1;

        int utf8(String value) throws IOException {
            Integer known = strings.get(value);
            if (known != null) {
                return known;
            }
            out.writeByte(1);
            out.writeUTF(value);
            strings.put(value, next);
            return next++;
        }

        int type(String internalName) throws IOException {
            Integer known = types.get(internalName);
            if (known != null) {
                return known;
            }
            int name = utf8(internalName);
            out.writeByte(7);
            out.writeShort(name);
            types.put(internalName, next);
            return next++;
        }
    }
}
