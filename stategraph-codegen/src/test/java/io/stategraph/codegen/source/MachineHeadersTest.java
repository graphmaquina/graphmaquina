package io.stategraph.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.source.util.JavacTask;
import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that a machine facade is specialized to its entity, state enum, states and peers. */
class MachineHeadersTest {

    @TempDir
    Path directory;

    private static final String BASE = """
        package fx;
        import io.stategraph.api.Machine;
        public interface Base<T, U extends Enum<U>> extends Machine<T, U> {}
        """;

    @Test
    void resolvesEntityStateAndConstantsThroughAGenericBase() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status> {
                        default void probe() { MissingGeneratedType.run(); }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", graph.entityType());
            assertEquals("fx.Status", graph.stateType());
            assertEquals(List.of("NEW", "DONE"), graph.states());
            assertEquals(List.of(), graph.peers());
            assertEquals(Path.of("fx", "Orders.java").toString(), graph.origin().path());
            assertEquals(3L, graph.origin().line());
        }
    }

    @Test
    void resolvesPeersDeclaredAsClassLiterals() {
        Map<String, String> sources = new LinkedHashMap<>(Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Bills", "package fx; public interface Bills extends Base<Order, Status> {}",
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    import io.stategraph.annotation.GraphPeers;
                    @GenerateGraphApi
                    @GraphPeers({Bills.class})
                    public interface Orders extends Base<Order, Status> {}
                    """));
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            assertEquals(List.of("fx.Bills"), MachineHeaders.resolve(index, "fx.Orders").peers());
        }
    }

    @Test
    void resolvesAMarkedFacadeWhoseGeneratedImportsAndAnnotationsDoNotExistYet() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Bills", "package fx; public interface Bills extends Base<Order, Status> {}",
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    import io.stategraph.annotation.GraphPeers;
                    import fx.OrdersGraph.Definition;
                    import fx.OrdersGraph.*;
                    import static fx.PaymentActionsRefs.*;
                    @GenerateGraphApi
                    @GraphPeers({Bills.class})
                    @Definition(state = PaymentOrderRefs.Status.class, id = PaymentOrderRefs.Id.class)
                    public interface Orders extends Base<Order, Status> {
                        default void probe() { MissingGeneratedType.run(Authorize.class); }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", graph.entityType());
            assertEquals("fx.Status", graph.stateType());
            assertEquals(List.of("fx.Bills"), graph.peers());
        }
    }

    @Test
    void resolvesNestedTypesInheritedThroughTheOwnerHierarchy() {
        Map<String, String> sources = Map.of(
                "fx.Holder", """
                    package fx;
                    public class Holder {
                        public static class Order {}
                        public enum Status { NEW, DONE }
                    }
                    """,
                "fx.Sub", "package fx; public class Sub extends Holder {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Sub.Order, Sub.Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            assertEquals(List.of("fx.Base", "fx.Holder", "fx.Holder.Order", "fx.Holder.Status", "fx.Orders",
                            "fx.Sub"),
                    index.sourceHeaders().stream().map(TypeHeader::canonicalName).sorted().toList());
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Holder.Order", graph.entityType());
            assertEquals("fx.Holder.Status", graph.stateType());
            assertEquals(List.of("NEW", "DONE"), graph.states());
        }
    }

    @Test
    void reportsAmbiguousOnDemandImportsInsteadOfChoosingByOrder() {
        Map<String, String> sources = Map.of(
                "a.Status", "package a; public enum Status { NEW }",
                "b.Status", "package b; public enum Status { DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import a.*;
                    import b.*;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals("SG601", failure.code());
            assertEquals("the type Status is ambiguous between a.Status and b.Status", failure.getMessage());
            assertEquals(Path.of("fx", "Orders.java").toString(), failure.location().orElseThrow().path());
        }
    }

    @Test
    void keepsMethodTypeVariablesOutOfTheClassSubstitutionEnvironment() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", """
                    package fx;
                    import io.stategraph.api.Machine;
                    public interface Base<T, U extends Enum<U>> extends Machine<T, U> {
                        <T extends Number> T map(T value);
                        T get();
                    }
                    """,
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            TypeHeader base = index.find("fx.Base").orElseThrow();
            TypeHeader.Member map = member(base, "map");
            TypeHeader.Member get = member(base, "get");
            assertEquals(new TypeUse.Variable("T", new TypeParameterKey("fx.Base#map(T)", 0)), map.type());
            assertEquals(List.of("T"), map.typeParameters());
            assertEquals(new TypeUse.Variable("T", new TypeParameterKey("fx.Base", 0)), get.type());

            Map<TypeParameterKey, TypeUse> environment =
                    Map.of(new TypeParameterKey("fx.Base", 0), TypeUse.Declared.of("java.lang.String"));
            assertEquals("java.lang.String", index.substitute(get.type(), environment).render());
            assertEquals("T", index.substitute(map.type(), environment).render());

            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", graph.entityType());
            assertEquals("fx.Status", graph.stateType());
        }
    }

    @Test
    void reportsAStateTypeThatIsNotAnEnum() {
        Map<String, String> sources = Map.of(
                "fx.Order", "package fx; public class Order {}",
                "fx.Status", "package fx; public class Status {}",
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    import io.stategraph.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals("SG601", failure.code());
            assertEquals("the state type fx.Status of fx.Orders is not an enum", failure.getMessage());
            assertEquals(Path.of("fx", "Orders.java").toString(), failure.location().orElseThrow().path());
        }
    }

    @Test
    void reportsAFacadeThatDoesNotSpecializeMachine() {
        Map<String, String> sources = Map.of(
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals("SG601", failure.code());
            assertEquals("fx.Orders does not extend io.stategraph.api.Machine", failure.getMessage());
        }
    }

    @Test
    void agreesWithJavacDirectSupertypesOnTheSameCompilableFixture() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.stategraph.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals(machineArgumentsAccordingToJavac(files), List.of(graph.entityType(), graph.stateType()));
        }
    }

    private static TypeHeader.Member member(TypeHeader header, String name) {
        return header.members().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no member " + name + " on " + header.canonicalName()));
    }

    /** Compiles the same fixture for real and reads the {@code Machine} specialization javac itself computes. */
    private List<String> machineArgumentsAccordingToJavac(List<Path> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, SourceFiles.classpath());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,
                    List.of(Files.createDirectories(directory.resolve("oracle-classes"))));
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            task.analyze();
            assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .toList());
            Elements elements = task.getElements();
            Types types = task.getTypes();
            Deque<TypeMirror> pending = new ArrayDeque<>();
            pending.add(elements.getTypeElement("fx.Orders").asType());
            while (!pending.isEmpty()) {
                TypeMirror current = pending.removeFirst();
                if (current instanceof DeclaredType declared
                        && ((TypeElement) declared.asElement()).getQualifiedName()
                                .contentEquals("io.stategraph.api.Machine")) {
                    List<String> arguments = new ArrayList<>();
                    for (TypeMirror argument : declared.getTypeArguments()) {
                        arguments.add(argument.toString());
                    }
                    return List.copyOf(arguments);
                }
                pending.addAll(types.directSupertypes(current));
            }
            throw new IllegalStateException("javac found no Machine supertype");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
