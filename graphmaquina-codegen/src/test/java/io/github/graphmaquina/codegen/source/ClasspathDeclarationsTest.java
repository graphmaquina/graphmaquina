package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that classpath headers are materialized into the same declaration model as source headers. */
class ClasspathDeclarationsTest {

    @TempDir
    Path directory;

    @Test
    void materializesGenericOwnerIdentityAndSupertypesFromTheClasspath() {
        try (ClasspathDeclarations declarations = ClasspathDeclarations.open(SourceFiles.classpath())) {
            TypeHeader machine = declarations.find("io.github.graphmaquina.api.Machine").orElseThrow();
            assertEquals(ElementKind.INTERFACE, machine.kind());
            assertEquals(List.of("E", "S", "T"), machine.typeParameters());
            assertEquals(Optional.empty(), machine.origin());
            assertTrue(machine.members().stream()
                    .anyMatch(member -> member.name().equals("transitionsFrom")));
        }
    }

    @Test
    void materializesABinaryGenericBaseAndItsSpecializedSupertype() {
        Path jar = SourceFiles.compileToJar(directory,
                Map.of("fx.Base", """
                    package fx;
                    import io.github.graphmaquina.api.Machine;
                    public interface Base<T, U extends Enum<U>, V extends Enum<V>> extends Machine<T, U, V> {}
                    """),
                directory.resolve("base.jar"));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);
        try (ClasspathDeclarations declarations = ClasspathDeclarations.open(classpath)) {
            TypeHeader base = declarations.find("fx.Base").orElseThrow();
            assertEquals(List.of("T", "U", "V"), base.typeParameters());
            assertEquals(List.of(new TypeUse.Declared("io.github.graphmaquina.api.Machine",
                            List.of(new TypeUse.Variable("T", new TypeParameterKey("fx.Base", 0)),
                                    new TypeUse.Variable("U", new TypeParameterKey("fx.Base", 1)),
                                    new TypeUse.Variable("V", new TypeParameterKey("fx.Base", 2))),
                            Optional.empty())),
                    base.supertypes());
        }
    }

    @Test
    void keepsTheGenericOwnerOfAnInnerMemberTypeReadFromABinary() {
        Path jar = SourceFiles.compileToJar(directory,
                Map.of("p.Box", """
                    package p;
                    public class Box<T> {
                        public class Inner<U> {
                            public T outer() { return null; }
                            public U inner() { return null; }
                        }
                    }
                    """),
                directory.resolve("box.jar"));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);
        try (DeclarationIndex index = DeclarationIndex.open(List.of(), classpath)) {
            TypeHeader inner = index.find("p.Box.Inner").orElseThrow();
            TypeUse outer = member(inner, "outer").type();
            TypeUse own = member(inner, "inner").type();
            assertEquals(new TypeUse.Variable("T", new TypeParameterKey("p.Box", 0)), outer);
            assertEquals(new TypeUse.Variable("U", new TypeParameterKey("p.Box.Inner", 0)), own);

            Map<TypeParameterKey, TypeUse> environment =
                    Map.of(new TypeParameterKey("p.Box", 0), TypeUse.Declared.of("java.lang.String"));
            assertEquals("java.lang.String", index.substitute(outer, environment).render());
            assertEquals("U", index.substitute(own, environment).render());
            assertEquals("p.Box<java.lang.String>.Inner<java.lang.Integer>",
                    index.substitute(new TypeUse.Declared("p.Box.Inner",
                                    List.of(TypeUse.Declared.of("java.lang.Integer")),
                                    Optional.of(new TypeUse.Declared("p.Box",
                                            List.of(new TypeUse.Variable("T", new TypeParameterKey("p.Box", 0))),
                                            Optional.empty()))),
                            environment).render());
        }
    }

    @Test
    void failsWithJavacsOwnErrorWhenAClasspathEntryCannotBeRead() {
        Path corrupt = directory.resolve("corrupt.jar");
        try {
            Files.writeString(corrupt, "this is not a jar", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        CodegenException failure = assertThrows(CodegenException.class,
                () -> ClasspathDeclarations.open(List.of(corrupt)).close());

        assertEquals("SG601", failure.code());
        assertTrue(failure.getMessage().startsWith("the classpath headers cannot be read: "),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("corrupt.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("zip END header not found"), failure.getMessage());
        assertEquals(Optional.empty(), failure.location());
    }

    private static TypeHeader.Member member(TypeHeader header, String name) {
        return header.members().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no member " + name));
    }

    @Test
    void resolvesTheGraphWhenTheGenericBaseOnlyExistsAsABinary() {
        Path jar = SourceFiles.compileToJar(directory,
                Map.of("fx.Base", """
                    package fx;
                    import io.github.graphmaquina.api.Machine;
                    public interface Base<T, U extends Enum<U>, V extends Enum<V>> extends Machine<T, U, V> {}
                    """),
                directory.resolve("base.jar"));
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", "package fx; public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }",
                "fx.Order", "package fx; public class Order {}",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """);
        Path project = directory.resolve("project");
        List<Path> files = SourceFiles.write(project, sources);
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);

        List<SourceUnit> units = SourceDeclarations.parse(project, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, classpath)) {
            GraphDescriptor descriptor = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", descriptor.entityType());
            assertEquals("fx.Status", descriptor.stateType());
            assertEquals(List.of("NEW", "DONE"), descriptor.states());
            assertEquals("fx.DocTransition", descriptor.transitionType());
            assertEquals(List.of("SUBMIT", "PUBLISH", "ARCHIVE"), descriptor.transitions());
        }
    }

    @Test
    void resolvesANestedTypeInheritedFromABinaryOwner() {
        Path jar = SourceFiles.compileToJar(directory,
                Map.of("p.Base", """
                    package p;
                    public class Base {
                        public static class Nested {}
                    }
                    """,
                    "p.Child", "package p; public class Child extends Base {}"),
                directory.resolve("hierarchy.jar"));
        Path project = directory.resolve("project");
        List<Path> files = SourceFiles.write(project,
                Map.of("fx.Probe", "package fx; import p.Child; public class Probe { Child held; }"));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);

        List<SourceUnit> units = SourceDeclarations.parse(project, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, classpath)) {
            TypeHeader probe = index.find("fx.Probe").orElseThrow();
            assertEquals(Optional.empty(), index.find("p.Child.Nested"));
            assertEquals("p.Base.Nested", index.resolve(probe, "Child.Nested").orElseThrow().canonicalName());
        }
    }
}
