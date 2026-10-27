package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ClassTree;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that original sources are read syntactically, with no attribution of their bodies. */
class SourceDeclarationsTest {

    @TempDir
    Path directory;

    @Test
    void parsesDeclarationsWhoseImportsAnnotationsAndBodiesNameTypesThatDoNotExistYet() {
        Map<String, String> sources = Map.of(
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import fx.OrdersGraph.Definition;
                    import fx.OrdersGraph.*;
                    import static fx.PaymentActionsRefs.*;
                    @GenerateGraphApi
                    @Definition(state = PaymentOrderRefs.Status.class, id = PaymentOrderRefs.Id.class)
                    public interface Orders extends Base<Order, Status> {
                        default void probe() { MissingGeneratedType.run(Authorize.class); }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);

        List<SourceUnit> units = SourceDeclarations.parse(directory, files);

        assertEquals(1, units.size());
        assertEquals("fx/Orders.java", units.get(0).origin().path());
        assertEquals(1, units.get(0).tree().getTypeDecls().size());
        assertEquals("Orders", ((ClassTree) units.get(0).tree().getTypeDecls().get(0)).getSimpleName().toString());
    }

    @Test
    void failsWithJavacsOwnErrorInsteadOfReadingATruncatedTree() {
        Map<String, String> sources = Map.of(
                "fx.Orders", """
                    package fx;
                    public interface Orders extends Base<Order, Status> {
                        void broken(
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);

        CodegenException failure =
                assertThrows(CodegenException.class, () -> SourceDeclarations.parse(directory, files));

        assertEquals(DiagnosticCode.SG601.name(), failure.code());
        assertEquals("the original sources cannot be read: fx/Orders.java:4: illegal start of type",
                failure.getMessage());
        assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        assertEquals(4L, failure.location().orElseThrow().line());
        assertEquals(1L, failure.location().orElseThrow().column());
    }

    /**
     * A source header records the modifiers javac records for the same declaration read from a binary: a
     * member type of an interface is public and static, and a nested enum, record or interface is static.
     */
    @Test
    void recordsTheModifiersTheLanguageImpliesForANestedDeclaration() {
        Map<String, String> sources = Map.of("fx.Outer", """
                package fx;
                public class Outer {
                    class Inner {}
                    enum Phase { OPEN }
                    record Pair(int left) {}
                    interface Api {
                        class Ctx {}
                        enum Mode { ON }
                    }
                    @interface Marker {
                        class Value {}
                    }
                }
                """);
        List<SourceUnit> units = SourceDeclarations.parse(directory, SourceFiles.write(directory, sources));
        Map<String, Set<Modifier>> modifiers = new LinkedHashMap<>();
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            for (TypeHeader header : index.sourceHeaders()) {
                modifiers.put(header.canonicalName(), header.modifiers());
            }
        }

        assertEquals(Set.of(Modifier.PUBLIC), modifiers.get("fx.Outer"));
        assertEquals(Set.of(), modifiers.get("fx.Outer.Inner"));
        assertEquals(Set.of(Modifier.STATIC), modifiers.get("fx.Outer.Phase"));
        assertEquals(Set.of(Modifier.STATIC), modifiers.get("fx.Outer.Pair"));
        assertEquals(Set.of(Modifier.STATIC), modifiers.get("fx.Outer.Api"));
        assertEquals(Set.of(Modifier.PUBLIC, Modifier.STATIC), modifiers.get("fx.Outer.Api.Ctx"));
        assertEquals(Set.of(Modifier.PUBLIC, Modifier.STATIC), modifiers.get("fx.Outer.Api.Mode"));
        assertEquals(Set.of(Modifier.STATIC), modifiers.get("fx.Outer.Marker"));
        assertEquals(Set.of(Modifier.PUBLIC, Modifier.STATIC), modifiers.get("fx.Outer.Marker.Value"));
    }

    /**
     * An origin path is published in the export index, so it reads the same on every platform: the separator
     * of the file system it was read from becomes a forward slash. The separator is an argument, so Windows' is
     * given explicitly and the test runs the same on any host.
     */
    @Test
    void spellsOriginPathsWithForwardSlashesWhateverTheSeparatorOfTheFileSystem() {
        assertEquals("fx/deep/Nested.java", SourceDeclarations.portable("fx\\deep\\Nested.java", "\\"));
        assertEquals("fx/deep/Nested.java", SourceDeclarations.portable("fx/deep/Nested.java", "/"));
    }

    /**
     * A source on another drive of a Windows host cannot be relativized against the project directory, so it keeps
     * its absolute path, still written with forward slashes. A POSIX host's file systems all separate names with
     * {@code /}, so a stand-in path of a file system that separates them with {@code \} plays that source, and the
     * test runs the same on any host.
     */
    @Test
    void writesTheAbsolutePathOfASourceOnAnotherDriveWithForwardSlashes() {
        assertEquals("D:/sources/fx/Doc.java",
                SourceDeclarations.relative(directory, backslashPath("D:\\sources\\fx\\Doc.java")));
    }

    /**
     * A path that renders as {@code text}, is already absolute and normal, and belongs to a file system that
     * separates names with a backslash. No other question is ever asked of it.
     */
    private static Path backslashPath(String text) {
        FileSystem backslash = new BackslashFileSystem();
        InvocationHandler behaviour = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "toAbsolutePath", "normalize" -> proxy;
            case "getFileSystem" -> backslash;
            default -> answerObjectMethod(text, proxy, method, arguments);
        };
        return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class}, behaviour);
    }

    /**
     * The answer of the path {@code proxy}, which renders as {@code text}, to a question of {@code Object}: its
     * {@code toString}, {@code hashCode} and {@code equals}. Any other method throws
     * {@code UnsupportedOperationException}, since no other question is ever asked of it.
     */
    private static Object answerObjectMethod(String text, Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> text;
            case "hashCode" -> text.hashCode();
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    /** A file system that only answers its separator, a backslash as on Windows. */
    private static final class BackslashFileSystem extends FileSystem {

        @Override
        public String getSeparator() {
            return "\\";
        }

        @Override
        public FileSystemProvider provider() {
            throw new UnsupportedOperationException("provider");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("close");
        }

        @Override
        public boolean isOpen() {
            throw new UnsupportedOperationException("isOpen");
        }

        @Override
        public boolean isReadOnly() {
            throw new UnsupportedOperationException("isReadOnly");
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            throw new UnsupportedOperationException("getRootDirectories");
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            throw new UnsupportedOperationException("getFileStores");
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            throw new UnsupportedOperationException("supportedFileAttributeViews");
        }

        @Override
        public Path getPath(String first, String... more) {
            throw new UnsupportedOperationException("getPath");
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            throw new UnsupportedOperationException("getPathMatcher");
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            throw new UnsupportedOperationException("getUserPrincipalLookupService");
        }

        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException("newWatchService");
        }
    }

    @Test
    void originPathsAreRelativeToTheProjectDirectory() {
        Map<String, String> sources = Map.of(
                "fx.deep.Nested", "package fx.deep; public class Nested {}",
                "Root", "public class Root {}");
        List<Path> files = SourceFiles.write(directory, sources);

        List<SourceUnit> units = SourceDeclarations.parse(directory, files);

        List<String> paths = units.stream().map(unit -> unit.origin().path()).sorted().toList();
        assertEquals(List.of("Root.java", "fx/deep/Nested.java"), paths);
        assertTrue(units.stream().allMatch(unit -> unit.origin().line() == 1L && unit.origin().column() == 1L));
    }
}
