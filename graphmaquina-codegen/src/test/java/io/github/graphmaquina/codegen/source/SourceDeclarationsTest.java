package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ClassTree;
import io.github.graphmaquina.codegen.CodegenException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        assertEquals(Path.of("fx", "Orders.java").toString(), units.get(0).origin().path());
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

        assertEquals("SG601", failure.code());
        assertEquals("the original sources cannot be read: " + Path.of("fx", "Orders.java")
                + ":4: illegal start of type", failure.getMessage());
        assertEquals(Path.of("fx", "Orders.java").toString(), failure.location().orElseThrow().path());
        assertEquals(4L, failure.location().orElseThrow().line());
        assertEquals(1L, failure.location().orElseThrow().column());
    }

    @Test
    void originPathsAreRelativeToTheProjectDirectory() {
        Map<String, String> sources = Map.of(
                "fx.deep.Nested", "package fx.deep; public class Nested {}",
                "Root", "public class Root {}");
        List<Path> files = SourceFiles.write(directory, sources);

        List<SourceUnit> units = SourceDeclarations.parse(directory, files);

        List<String> paths = units.stream().map(unit -> unit.origin().path()).sorted().toList();
        assertEquals(List.of(Path.of("Root.java").toString(), Path.of("fx", "deep", "Nested.java").toString()), paths);
        assertTrue(units.stream().allMatch(unit -> unit.origin().line() == 1L && unit.origin().column() == 1L));
    }
}
