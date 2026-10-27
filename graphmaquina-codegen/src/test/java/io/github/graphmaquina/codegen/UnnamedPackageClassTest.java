package io.github.graphmaquina.codegen;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.source.DeclarationIndex;
import io.github.graphmaquina.codegen.source.SourceFiles;
import io.github.graphmaquina.codegen.source.TypeHeader;
import io.github.graphmaquina.codegen.source.TypeUse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * javac admits a class of the unnamed package named like the first segment of a package, such as a class {@code shop}
 * beside the package {@code shop}. Code of a named package never sees that class, while in code of the unnamed package
 * the class obscures the package (JLS 6.4.2), so a canonical name such as {@code shop.Model.Order} names a type of the
 * package in the one and a member type of the class in the other. The generator keeps the declarations of both, and
 * resolves a name as javac does from the package of the code that spells it.
 *
 * <p>Whether the class comes first or last among the sources, and whether the class or the package is on the class
 * path, a machine of the package gets the constants of the package's enums and a host of the package its references,
 * while a machine of the unnamed package gets the member types of the class, through each supertype of one canonical
 * name. Code of a named package never gets a type of the unnamed package: no member type of the class is in scope
 * there, whatever an import names, and a name only such a type has is unresolved. A type of the package is never a
 * member type of the class, an annotation type the class nests marks nothing, and a member type of a class
 * {@code java} is no type of {@code java.lang}. An inner type is owned as the types its code names parameterize it, an
 * imported one and the supertypes of a homonym included, and a member type that a homonym inherits from the type of
 * the package of its own canonical name is found there. A name, or an inner type, that the headers of both types of
 * one canonical name spell is resolved, or owned, for each of them. A binary of a named package means the types of
 * the package, and so does a class file of the unnamed package that names them through an import. The access to a
 * type of the class path is checked in the package that declares it, a header no index read is still a context, a
 * peer is the type its class literal names, and a host exports the members of both types of one canonical name, a
 * member both declare alike once.</p>
 *
 * <p>Refused are a machine of the unnamed package whose vocabulary could not name the enums of the package it imports,
 * two machines that share a canonical name, and a class file that names both types of one canonical name, since a
 * manifest knows a machine by that name, and a class file read back by its canonical names cannot tell them
 * apart.</p>
 */
class UnnamedPackageClassTest {

    private static final String COMPILATION_ID = "io.github.graphmaquina:homonyms:main";

    @TempDir
    Path directory;

    /**
     * A machine of the package {@code shop} whose enums, entity and action hosts are member types, beside a class
     * {@code shop} of the unnamed package that nests homonyms of its enums, of its entity, of its hosts, or of the
     * types generated for it, declared in {@code shop.java}, which the generator reads before the files of the
     * package, or in {@code zzz.java}, which it reads after them. The generator writes the same model and the same
     * sources as for the machine alone, and they compile.
     */
    @Test
    void aClassNamedLikeThePackageOfNestedDeclarationsTakesNoneOfTheirNames() throws IOException {
        Generation alone = generate("alone", nested(), List.of());
        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, String> homonyms : nestedHomonyms().entrySet()) {
            for (String file : List.of("shop", "zzz")) {
                String label = homonyms.getKey() + "-" + file;
                checks.add(() -> {
                    Generation beside = generate(label, beside(nested(), file, homonyms.getValue()), List.of());
                    assertEquals(alone.model(), beside.model(), label);
                    assertEquals(alone.sources(), beside.sources(), label);
                    assertCompiles(beside, List.of());
                });
            }
        }
        assertAll(checks);
    }

    /**
     * The declarations of the machine of {@link #aClassNamedLikeThePackageOfNestedDeclarationsTakesNoneOfTheirNames} as
     * member types of a class {@code shop} of the unnamed package, with a machine of that package over them and a
     * second one watching it, beside a package {@code shop} that declares types of the same canonical names: enums with
     * other constants in a class with package access, an entity with a getter of its own, and hosts with package access
     * and an action of their own. Each side gets what it gets alone, whether the class is read before the package or
     * after it.
     */
    @Test
    void theNestedDeclarationsOfAMachineOfTheUnnamedPackageAreMemberTypesOfTheClass() throws IOException {
        Generation pack = generate("package", namedHomonyms(), List.of());
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            checks.add(() -> {
                Generation unnamed = generate("unnamed-" + file, unnamedNested(file), List.of());
                Map<String, String> sources = new LinkedHashMap<>(unnamedNested(file));
                sources.putAll(namedHomonyms());
                Generation both = generate("both-" + file, sources, List.of());

                List<SymbolDescriptor> symbols = new ArrayList<>(unnamed.model().symbols());
                symbols.addAll(pack.model().symbols());
                symbols.sort(Comparator.comparing(SymbolDescriptor::qualifiedName));
                Map<String, String> generated = new TreeMap<>(unnamed.sources());
                generated.putAll(pack.sources());
                assertEquals(List.of("Docs", "Orders"),
                        both.model().graphs().stream().map(GraphDescriptor::facade).toList(), file);
                assertEquals(List.of("NEW", "PAID", "CANCELLED"), both.model().graphs().get(1).states(), file);
                assertEquals(unnamed.model().graphs(), both.model().graphs(), file);
                assertEquals(symbols, both.model().symbols(), file);
                assertEquals(generated, both.sources(), file);
                assertCompiles(both, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
            });
        }
        assertAll(checks);
    }

    /**
     * A machine of the package {@code fx} over the enums of the package {@code shop}, which only the class path holds,
     * beside a class {@code shop} of the unnamed package: on the class path and empty; on the class path and nesting
     * homonyms of the enums with other constants; or among the sources and nesting them. The machine gets the package's
     * enums, and a machine of the unnamed package spelling the same names gets the class's.
     */
    @Test
    void aClassNamedLikeAPackageOfTheClassPathTakesNoneOfItsTypes() throws IOException {
        Path obscuring = jar("obscuring", Map.of("shop", "public final class shop {\n}\n", "shop.Statuses", STATUSES));
        Path homonyms = jar("homonyms", Map.of("shop", HOMONYM_CLASS, "shop.Statuses", STATUSES));
        Path pack = jar("package", Map.of("shop.Statuses", STATUSES));
        Map<String, String> sourceHomonyms = new LinkedHashMap<>(machine("fx"));
        sourceHomonyms.put("shop", HOMONYM_CLASS);
        Map<String, String> unnamedOverSources = new LinkedHashMap<>(machine(""));
        unnamedOverSources.put("shop", HOMONYM_CLASS);

        assertAll(
            () -> assertStates("obscuring", machine("fx"), obscuring, List.of("OPEN", "CLOSED"), List.of("CLOSE")),
            () -> assertStates("homonyms", machine("fx"), homonyms, List.of("OPEN", "CLOSED"), List.of("CLOSE")),
            () -> assertStates("homonyms-unnamed", machine(""), homonyms, List.of("NEW"), List.of("PAY")),
            () -> assertStates("sources", sourceHomonyms, pack, List.of("OPEN", "CLOSED"), List.of("CLOSE")),
            () -> assertStates("sources-unnamed", unnamedOverSources, pack, List.of("NEW"), List.of("PAY")));
    }

    /**
     * A class {@code shop} of the unnamed package, among the sources, nests a host whose action takes an {@code X},
     * which is no member type of that class, while the class path holds a type {@code X} of the package {@code shop}
     * and the sources a class {@code X} of the unnamed package. A type of the package is never a member type of the
     * class, so the parameter is the class {@code X} of the unnamed package, as javac binds it.
     */
    @Test
    void aTypeOfAPackageOfTheClassPathIsNoMemberTypeOfTheClassNamedLikeThePackage() throws IOException {
        Path classpath = jar("member", Map.of("shop.X", "package shop;\npublic final class X { }\n"));
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("X", "public final class X { }\n");
        sources.put("shop", """
                import io.github.graphmaquina.annotation.GraphAction;
                public final class shop {
                    private shop() { }
                    public static final class Hosts {
                        private Hosts() { }
                        public static final class Payments {
                            @GraphAction
                            public void charge(X x) { }
                        }
                    }
                }
                """);

        Generation generation = generate("member", sources, List.of(classpath));

        assertEquals(List.of("X"), generation.model().symbols().get(0).candidates().get(0).parameterTypes());
        assertCompiles(generation, List.of("-classpath", System.getProperty("java.class.path") + File.pathSeparator
                + classpath));
    }

    /**
     * The enums of the package {@code shop}, which only the class path holds, have package access, while the class
     * {@code shop} of the unnamed package beside them nests public homonyms. A machine of the package {@code shop} may
     * name them, and one of the package {@code fx} is refused for the package that declares them.
     */
    @Test
    void theAccessToATypeOfTheClassPathIsCheckedInThePackageThatDeclaresIt() throws IOException {
        Path classpath = jar("access", Map.of("shop", HOMONYM_CLASS, "shop.Statuses", """
                package shop;
                public final class Statuses {
                    private Statuses() { }
                    enum OrderStatus { OPEN, CLOSED }
                    enum OrderTransition { CLOSE }
                }
                """));

        assertStates("same-package", machine("shop"), classpath, List.of("OPEN", "CLOSED"), List.of("CLOSE"));
        CodegenException refused = assertThrows(CodegenException.class,
                () -> generate("other-package", machine("fx"), List.of(classpath)));
        assertEquals(DiagnosticCode.SG601.name(), refused.code());
        assertEquals("the state type shop.Statuses.OrderStatus of fx.Orders is not accessible to the vocabulary"
                + " generated in package fx: shop.Statuses.OrderStatus is not public and belongs to package shop",
                refused.getMessage());
    }

    /**
     * A generic base of the package {@code lib}, which only the class path holds, binds the enums of its machine by
     * their canonical names {@code shop.Statuses.OrderStatus} and {@code shop.Statuses.OrderTransition}, beside a class
     * {@code shop} of the unnamed package of the same class path that nests homonyms of them. A binary of a named
     * package means the types of the package, so the machine extending that base gets the package's enums.
     */
    @Test
    void aBinaryOfANamedPackageMeansTheTypesOfThePackage() throws IOException {
        Path classpath = jar("base", Map.of("shop", HOMONYM_CLASS, "shop.Statuses", STATUSES, "lib.Base", """
                package lib;
                import io.github.graphmaquina.api.Machine;
                public interface Base<E>
                        extends Machine<E, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> { }
                """));
        Map<String, String> sources = Map.of("fx.Order", "package fx;\npublic final class Order { }\n", "fx.Orders", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import lib.Base;
                @GenerateGraphApi
                public interface Orders extends Base<Order> { }
                """);

        assertStates("base", sources, classpath, List.of("OPEN", "CLOSED"), List.of("CLOSE"));
    }

    /**
     * A machine of the unnamed package imports the enums of the package {@code shop}, beside a class {@code shop} of
     * the unnamed package that nests public homonyms of them. In an import no class of the unnamed package is in scope
     * (JLS 6.3), so the machine means the package's enums; but its vocabulary, generated in the unnamed package, can
     * only write their canonical names, which there name the class's homonyms. The machine is refused. So is one whose
     * state enum is the imported enum of the package and whose transition enum is the class's homonym of that very
     * canonical name, spelled through the class: two distinct enums, though one name.
     */
    @Test
    void aMachineOfTheUnnamedPackageCannotNameTheEnumsOfThePackageItImports() {
        Map<String, String> sources = new LinkedHashMap<>(machine(""));
        sources.put("shop.Statuses", STATUSES);
        String imported = """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import shop.Statuses;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, Statuses.OrderStatus, %s> { }
                """;
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            for (String transition : List.of("Statuses.OrderTransition", "shop.Statuses.OrderStatus")) {
                checks.add(() -> {
                    Map<String, String> layout = new LinkedHashMap<>(sources);
                    layout.put("Orders", imported.formatted(transition));
                    String label = file + " " + transition;
                    CodegenException refused = assertThrows(CodegenException.class,
                            () -> generate("import-" + file + "-" + transition.length(),
                                    beside(layout, file, STATUS_HOMONYMS), List.of()), label);
                    assertEquals(DiagnosticCode.SG603.name(), refused.code(), label);
                    assertEquals("the vocabulary generated for the machine Orders in the unnamed package cannot name"
                            + " shop.Statuses.OrderStatus: the type shop obscures the package shop there",
                            refused.getMessage(), label);
                });
            }
        }
        assertAll(checks);
    }

    /**
     * A class {@code shop} of the unnamed package nests a generic {@code Outer<T>} with an inner class {@code Inner},
     * a {@code Mid<Y>} extending {@code Outer<Y>}, and a {@code Sub<X>} extending {@code Mid<X>} whose action takes the
     * {@code Inner} it inherits, while the package {@code shop} declares an {@code Outer<T>} with an {@code Inner} too,
     * a {@code Mid} extending {@code Outer<Long>} and a {@code Sub} extending {@code Outer<Integer>}. An action of the
     * unnamed package takes {@code shop.Sub<String>.Inner}. Each parameter is the class's {@code Inner}, owned by the
     * parameterization the class's {@code Sub} and {@code Mid} give its {@code Outer}.
     */
    @Test
    void anInheritedInnerTypeIsOwnedThroughTheTypesOfThePackageOfItsCode() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Outer", "package shop;\npublic class Outer<T> {\n    public class Inner { }\n}\n");
        sources.put("shop.Mid", "package shop;\npublic class Mid extends Outer<Long> { }\n");
        sources.put("shop.Sub", "package shop;\npublic class Sub extends Outer<Integer> { }\n");
        sources.put("Payments", """
                import io.github.graphmaquina.annotation.GraphAction;
                public final class Payments {
                    @GraphAction
                    public void charge(shop.Sub<String>.Inner inner) { }
                }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            checks.add(() -> {
                Generation generation = generate("inner-" + file, beside(sources, file, """
                            public static class Outer<T> {
                                public class Inner { }
                            }
                            public static class Mid<Y> extends Outer<Y> { }
                            public static class Sub<X> extends Mid<X> {
                                @io.github.graphmaquina.annotation.GraphAction
                                public void settle(Inner inner) { }
                            }
                        """), List.of());
                Map<String, List<String>> parameters = new TreeMap<>();
                for (SymbolDescriptor symbol : generation.model().symbols()) {
                    parameters.put(symbol.qualifiedName(), symbol.candidates().get(0).parameterTypes());
                }
                assertEquals(Map.of("PaymentsRefs.Charge", List.of("shop.Outer<java.lang.String>.Inner"),
                        "ShopSubRefs.Settle", List.of("shop.Outer<X>.Inner")), parameters, file);
                assertCompiles(generation, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
            });
        }
        assertAll(checks);
    }

    /**
     * A machine of the package {@code shop} and a machine that a class {@code shop} of the unnamed package nests share
     * the canonical name {@code shop.Orders}, by which alone the manifest and every peer declaration know a machine.
     * The generator refuses them with one diagnostic that names both with their packages and positions.
     */
    @Test
    void twoMachinesThatShareACanonicalNameAreRefused() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                public final class shop {
                    private shop() { }
                    public enum S { A }
                    public enum T { GO }
                    public static final class E { }
                    @GenerateGraphApi
                    public interface Orders extends Machine<E, S, T> { }
                }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                public interface Orders extends Machine<Orders.E, Orders.S, Orders.T> {
                    enum S { X, Y }
                    enum T { MOVE }
                    final class E { }
                }
                """);

        CodegenException refused = assertThrows(CodegenException.class, () -> generate("machines", sources, List.of()));

        assertEquals(DiagnosticCode.SG603.name(), refused.code());
        assertEquals("the machines shop.Orders of the unnamed package at sources/shop.java:8 and shop.Orders of package"
                + " shop at sources/shop/Orders.java:4 share one canonical name, which is all a manifest names a"
                + " machine by; rename one of them", refused.getMessage());
        assertEquals("sources/shop.java", refused.location().orElseThrow().path());
    }

    /**
     * A host of the unnamed package that carries {@code @GraphBindings} implements the interface {@code shop.B} of the
     * package {@code shop}, which it imports, and {@code shop.C}, a member type of a class {@code shop} of the unnamed
     * package that extends that class's own {@code shop.B}. The one {@code shop.B} marks the action {@code charge}, the
     * other the action {@code settle}. The host exports both, each declared by its own {@code shop.B}, which the
     * annotation processor tells apart by the member.
     */
    @Test
    void aHostExportsTheMembersOfTwoTypesOfOneCanonicalName() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("zzz", """
                import io.github.graphmaquina.annotation.GraphAction;
                final class shop {
                    private shop() { }
                    public interface B {
                        @GraphAction
                        default void settle() { }
                    }
                    public interface C extends B { }
                }
                """);
        sources.put("shop.B", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                public interface B {
                    @GraphAction
                    default void charge() { }
                }
                """);
        sources.put("Host", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import shop.B;
                @GraphBindings
                public final class Host implements B, shop.C { }
                """);

        Generation generation = generate("hierarchy", sources, List.of());

        List<String> symbols = new ArrayList<>();
        for (SymbolDescriptor symbol : generation.model().symbols()) {
            MemberDescriptor candidate = symbol.candidates().get(0);
            symbols.add(symbol.qualifiedName() + " " + candidate.declaringType() + " " + candidate.origin().path());
        }
        assertEquals(List.of("HostRefs.Charge shop.B sources/shop/B.java", "HostRefs.Settle shop.B sources/zzz.java",
                "ShopBRefs.Settle shop.B sources/zzz.java", "shop.BRefs.Charge shop.B sources/shop/B.java"), symbols);
        assertCompiles(generation, List.of("-Xlint:-auxiliaryclass"));
    }

    /**
     * The host of {@link #aHostExportsTheMembersOfTwoTypesOfOneCanonicalName}, whose two {@code shop.B} both mark the
     * same action {@code charge()}, which the host implements without a mark. The host exports that member once, as the
     * type it meets first declares it: the {@code shop.B} of the package, which it implements directly. Each
     * {@code shop.B} exports it as a host of its own as well, and what was generated compiles.
     */
    @Test
    void aHostExportsOnceAMemberThatTwoTypesOfOneCanonicalNameDeclareAlike() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("zzz", """
                import io.github.graphmaquina.annotation.GraphAction;
                final class shop {
                    private shop() { }
                    public interface B {
                        @GraphAction
                        void charge();
                    }
                    public interface C extends B { }
                }
                """);
        sources.put("shop.B", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                public interface B {
                    @GraphAction
                    void charge();
                }
                """);
        sources.put("Host", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import shop.B;
                @GraphBindings
                public final class Host implements B, shop.C {
                    @Override
                    public void charge() { }
                }
                """);

        Generation generation = generate("one-member", sources, List.of());

        List<String> candidates = new ArrayList<>();
        for (SymbolDescriptor symbol : generation.model().symbols()) {
            for (MemberDescriptor candidate : symbol.candidates()) {
                candidates.add(symbol.qualifiedName() + " " + candidate.declaringType() + " "
                        + candidate.origin().path());
            }
        }
        assertEquals(List.of("HostRefs.Charge shop.B sources/shop/B.java", "ShopBRefs.Charge shop.B sources/zzz.java",
                "shop.BRefs.Charge shop.B sources/shop/B.java"), candidates);
        assertCompiles(generation, List.of("-Xlint:-auxiliaryclass"));
    }

    /**
     * A machine of the unnamed package extends the interface {@code shop.B} of the package {@code shop}, which it
     * imports, and {@code shop.C}, a member type of a class {@code shop} of the unnamed package that extends that
     * class's own {@code shop.B}, which extends {@code Machine}. The machine gets the enums it binds through that path.
     */
    @Test
    void aMachineReachesMachineThroughEachTypeOfACanonicalName() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("zzz", """
                import io.github.graphmaquina.api.Machine;
                final class shop {
                    private shop() { }
                    public enum S { A }
                    public enum T { GO }
                    public static final class E { }
                    public interface B extends Machine<E, S, T> { }
                    public interface C extends B { }
                }
                """);
        sources.put("shop.B", "package shop;\npublic interface B { }\n");
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import shop.B;
                @GenerateGraphApi
                public interface Orders extends B, shop.C { }
                """);

        Generation generation = generate("specialization", sources, List.of());

        GraphDescriptor graph = generation.model().graphs().get(0);
        assertEquals(List.of("Orders", "shop.E", "shop.S", "shop.T"),
                List.of(graph.facade(), graph.entityType(), graph.stateType(), graph.transitionType()));
        assertEquals(List.of(List.of("A"), List.of("GO")), List.of(graph.states(), graph.transitions()));
        assertCompiles(generation, List.of("-Xlint:-auxiliaryclass"));
    }

    /**
     * A class {@code io} of the unnamed package nests an annotation type whose canonical name is that of
     * {@code @GraphAction}, and a method of that package spells it, which there names that member type. It marks
     * nothing, so the method exports no symbol.
     */
    @Test
    void anAnnotationTypeOfTheClassIsNoMarker() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("io", """
                final class io {
                    private io() { }
                    static final class github {
                        private github() { }
                        static final class graphmaquina {
                            private graphmaquina() { }
                            static final class annotation {
                                private annotation() { }
                                @interface GraphAction { }
                            }
                        }
                    }
                }
                """);
        sources.put("Payments", """
                public final class Payments {
                    @io.github.graphmaquina.annotation.GraphAction
                    public void charge() { }
                }
                """);

        Generation generation = generate("marker", sources, List.of());

        assertEquals(new GenerationModel(List.of(), List.of()), generation.model());
        assertCompiles(generation, List.of());
    }

    /**
     * A host of the package {@code shop} imports {@code other.Detail} and names it {@code Detail}, while a class
     * {@code shop} of the unnamed package nests a {@code Detail} of its own. That class encloses no declaration of the
     * package, so its member types are not in scope there, and the action's parameter is the imported type.
     */
    @Test
    void aMemberTypeOfTheClassIsNotInScopeInCodeOfThePackage() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("other.Detail", "package other;\npublic final class Detail { }\n");
        sources.put("shop.Hosts", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import other.Detail;
                public final class Hosts {
                    private Hosts() { }
                    public static final class Payments {
                        @GraphAction
                        public void charge(Detail detail) { }
                    }
                }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            checks.add(() -> {
                Generation generation = generate("scope-" + file, beside(sources, file, """
                            static final class Detail { }
                        """), List.of());
                MemberDescriptor candidate = generation.model().symbols().get(0).candidates().get(0);
                assertEquals(List.of("other.Detail"), candidate.parameterTypes(), file);
                assertCompiles(generation, List.of());
            });
        }
        assertAll(checks);
    }

    /**
     * Code of a named package never sees a class {@code shop} of the unnamed package, so no member type of that class
     * answers a simple name there, though the class nests a type of that very name: an action of the package
     * {@code shop} takes a {@code Detail} that an on-demand import names, or a {@code String} of {@code java.lang}; an
     * action of the package {@code fx}, which imports both {@code shop.*} and {@code other.*} on demand, takes the one
     * {@code Detail} of those packages; and a machine of the package {@code shop} binds the enums an on-demand import
     * names. Each holds whether the class is read first or last.
     */
    @Test
    void codeOfANamedPackageNeverMeansAMemberTypeOfTheClass() {
        Map<String, String> onDemand = new LinkedHashMap<>();
        onDemand.put("other.Detail", "package other;\npublic final class Detail { }\n");
        onDemand.put("shop.Hosts", hosts("shop", "import other.*;\n", "Detail"));
        Map<String, String> javaLang = Map.of("shop.Hosts", hosts("shop", "", "String"));
        Map<String, String> twoOnDemand = new LinkedHashMap<>();
        twoOnDemand.put("other.Detail", "package other;\npublic final class Detail { }\n");
        twoOnDemand.put("shop.Marker", "package shop;\npublic final class Marker { }\n");
        twoOnDemand.put("fx.Hosts", hosts("fx", "import shop.*;\nimport other.*;\n", "Detail"));
        Map<String, String> enums = new LinkedHashMap<>();
        enums.put("shop.model.OrderStatus", "package shop.model;\npublic enum OrderStatus { NEW, PAID }\n");
        enums.put("shop.model.OrderTransition", "package shop.model;\npublic enum OrderTransition { PAY }\n");
        enums.put("shop.model.Order", "package shop.model;\npublic final class Order { }\n");
        enums.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import shop.model.*;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> { }
                """);
        String detail = "    public static final class Detail { }\n";
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            checks.add(() -> assertParameters("on-demand-" + file, beside(onDemand, file, detail), "other.Detail"));
            checks.add(() -> assertParameters("java-lang-" + file,
                    beside(javaLang, file, "    public static final class String { }\n"), "java.lang.String"));
            checks.add(() -> assertParameters("two-on-demand-" + file, beside(twoOnDemand, file, detail),
                    "other.Detail"));
            checks.add(() -> {
                Generation generation = generate("enums-" + file, beside(enums, file,
                        "    public enum OrderStatus { WRONG }\n    public enum OrderTransition { BAD }\n"), List.of());
                GraphDescriptor graph = generation.model().graphs().get(0);
                assertEquals(List.of("shop.model.OrderStatus", "shop.model.OrderTransition"),
                        List.of(graph.stateType(), graph.transitionType()), file);
                assertEquals(List.of(List.of("NEW", "PAID"), List.of("PAY")),
                        List.of(graph.states(), graph.transitions()), file);
                assertCompiles(generation, List.of());
            });
        }
        assertAll(checks);
    }

    /**
     * The package {@code shop} declares a type {@code io}, which obscures the package {@code io} inside the vocabulary
     * generated for a machine of that package, while a class {@code shop} of the unnamed package nests a homonym
     * {@code io}. The vocabulary is refused as it is without that class, whether the class is read first or last.
     */
    @Test
    void aTypeOfThePackageObscuresAPackageBesideItsHomonym() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Order", "package shop;\npublic final class Order { }\n");
        sources.put("shop.Status", "package shop;\npublic enum Status { NEW }\n");
        sources.put("shop.Move", "package shop;\npublic enum Move { GO }\n");
        sources.put("shop.io", "package shop;\npublic final class io { }\n");
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, Status, Move> { }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String layout : List.of("alone", "shop", "zzz")) {
            checks.add(() -> {
                Map<String, String> layoutSources = layout.equals("alone") ? sources
                        : beside(sources, layout, "    public static final class io { }\n");
                CodegenException refused = assertThrows(CodegenException.class,
                        () -> generate("obscured-" + layout, layoutSources, List.of()), layout);
                assertEquals(DiagnosticCode.SG603.name(), refused.code(), layout);
                assertEquals("the vocabulary generated for the machine shop.Orders in package shop cannot name"
                        + " io.github.graphmaquina: the type shop.io obscures the package io there",
                        refused.getMessage(), layout);
            });
        }
        assertAll(checks);
    }

    /**
     * A machine of the package {@code shop} beside a class {@code java} of the unnamed package that nests a public
     * {@code lang.io}. The implicit import of {@code java.lang} imports the types of that package only, never a member
     * type of such a class, so no type {@code io} obscures the package {@code io} that the machine's vocabulary names,
     * and the vocabulary is generated as it is without the class.
     */
    @Test
    void aMemberTypeOfAClassNamedJavaIsNoTypeOfJavaLang() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Order", "package shop;\npublic final class Order { }\n");
        sources.put("shop.Status", "package shop;\npublic enum Status { NEW }\n");
        sources.put("shop.Move", "package shop;\npublic enum Move { GO }\n");
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, Status, Move> { }
                """);
        sources.put("java", """
                public final class java {
                    private java() { }
                    public static final class lang {
                        private lang() { }
                        public static final class io { }
                    }
                }
                """);

        Generation generation = generate("class-java", sources, List.of());

        assertEquals(List.of("shop.Orders"),
                generation.model().graphs().stream().map(GraphDescriptor::facade).toList());
        assertCompiles(generation, List.of());
    }

    /**
     * A machine of the package {@code fx} spells its entity, or its state enum, by a simple name that only a type of
     * the unnamed package has. Code of a named package never sees such a type, so the name is unresolved, as javac
     * finds it.
     */
    @Test
    void aMachineOfANamedPackageNeverBindsATypeOfTheUnnamedPackage() {
        Map<String, String> entity = new LinkedHashMap<>();
        entity.put("Order", "public final class Order { }\n");
        entity.put("fx.Status", "package fx;\npublic enum Status { NEW }\n");
        entity.put("fx.Move", "package fx;\npublic enum Move { GO }\n");
        entity.put("fx.Orders", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, Status, Move> { }
                """);
        Map<String, String> state = new LinkedHashMap<>(entity);
        state.remove("Order");
        state.remove("fx.Status");
        state.put("fx.Order", "package fx;\npublic final class Order { }\n");
        state.put("Status", "public enum Status { NEW }\n");

        assertAll(() -> {
            CodegenException refused = assertThrows(CodegenException.class,
                    () -> generate("unnamed-entity", entity, List.of()));
            assertEquals(DiagnosticCode.SG601.name(), refused.code());
            assertEquals("the entity type Order of fx.Orders is unresolved", refused.getMessage());
        }, () -> {
            CodegenException refused = assertThrows(CodegenException.class,
                    () -> generate("unnamed-state", state, List.of()));
            assertEquals(DiagnosticCode.SG601.name(), refused.code());
            assertEquals("the state type Status of fx.Orders is unresolved", refused.getMessage());
        });
    }

    /**
     * A host of the unnamed package extends {@code Mid<String>}, the generic {@code shop.Mid} of the package
     * {@code shop} that it imports, which extends {@code Outer<Y>}, and its action takes the inner type {@code Inner}
     * that {@code Outer} declares, while a class {@code shop} of the unnamed package nests an {@code Outer} with an
     * {@code Inner} too and a {@code Mid} extending {@code Outer<Integer>}. In an import no class of the unnamed
     * package is in scope (JLS 6.3), so the parameter is owned by {@code shop.Outer<java.lang.String>}, whether the
     * class is read first or last.
     */
    @Test
    void anInnerTypeInheritedThroughAnImportedTypeIsOwnedThroughThatType() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Outer", "package shop;\npublic class Outer<T> {\n    public class Inner { }\n}\n");
        sources.put("shop.Mid", "package shop;\npublic class Mid<Y> extends Outer<Y> { }\n");
        sources.put("S", "public enum S { A, B }\n");
        sources.put("T", "public enum T { GO }\n");
        sources.put("Host", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Mid;
                public final class Host extends Mid<String> {
                    @GraphAction
                    public ActionOutcome charge(TransitionContext<Inner, S, T> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            checks.add(() -> {
                Generation generation = generate("imported-inner-" + file, beside(sources, file, """
                            public static class Outer<T> {
                                public class Inner { }
                            }
                            public static class Mid<Y> extends Outer<Integer> { }
                        """), List.of());
                assertEquals(List.of("io.github.graphmaquina.api.TransitionContext<shop.Outer<java.lang.String>.Inner,"
                        + " S, T>"), generation.model().symbols().get(0).candidates().get(0).parameterTypes(), file);
                assertCompiles(generation, List.of());
            });
        }
        assertAll(checks);
    }

    /**
     * A class {@code shop} of the unnamed package nests {@code Outer}, and in it an inner class {@code Inner} extending
     * {@code Base<String>}, a class of the unnamed package that imports and extends the generic {@code shop.Outer<T>}
     * of the package, whose own inner class is named {@code Inner} too. In the body of the class's {@code Inner}, the
     * simple name {@code Inner} means the inner class it inherits from the package's {@code Outer}, owned by
     * {@code shop.Outer<java.lang.String>}, as javac binds it, whether the class is read first or last; and so it does
     * in the body of an inner class of another name, {@code Leaf}.
     */
    @Test
    void anInnerTypeItsHomonymInheritsIsOwnedThroughTheSupertypesOfTheHomonym() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Outer", "package shop;\npublic class Outer<T> {\n    public class Inner { }\n}\n");
        sources.put("Base", "import shop.Outer;\npublic class Base<T> extends Outer<T> { }\n");
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            for (String name : List.of("Inner", "Leaf")) {
                checks.add(() -> {
                    String label = "homonym-inner-" + name + "-" + file;
                    Generation generation = generate(label, beside(sources, file, """
                                static class Outer {
                                    class %s extends Base<String> {
                                        @io.github.graphmaquina.annotation.GraphHook
                                        public void done(Inner entity) { }
                                    }
                                }
                            """.formatted(name)), List.of());
                    SymbolDescriptor symbol = generation.model().symbols().get(0);
                    assertEquals(List.of("shop.Outer." + name, "shop.Outer<java.lang.String>.Inner"),
                            List.of(symbol.host(), symbol.candidates().get(0).parameterTypes().get(0)), label);
                    assertCompiles(generation, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
                });
            }
        }
        assertAll(checks);
    }

    /**
     * A class {@code shop} of the unnamed package nests {@code X}, which extends {@code Base}, a class of the unnamed
     * package that imports and extends the type {@code shop.X} of the package, whose member type {@code Y} the class's
     * {@code X} inherits and names. Walking from the class's {@code X} to the package's type of that very canonical
     * name, the name {@code Y} means {@code shop.X.Y} of the package, as javac binds it, whether the class is read
     * first or last; and so it does for a type of the class of another name, {@code Z}.
     */
    @Test
    void aMemberTypeItsHomonymInheritsIsFoundInTheTypeOfThePackage() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.X", "package shop;\npublic class X {\n    public static class Y { }\n}\n");
        sources.put("Base", "import shop.X;\npublic class Base extends X { }\n");
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            for (String name : List.of("X", "Z")) {
                checks.add(() -> {
                    String label = "homonym-member-" + name + "-" + file;
                    Generation generation = generate(label, beside(sources, file, """
                                static class %s extends Base {
                                    @io.github.graphmaquina.annotation.GraphHook
                                    public void done(Y entity) { }
                                }
                            """.formatted(name)), List.of());
                    SymbolDescriptor symbol = generation.model().symbols().get(0);
                    assertEquals(List.of("shop." + name, "shop.X.Y"),
                            List.of(symbol.host(), symbol.candidates().get(0).parameterTypes().get(0)), label);
                    assertCompiles(generation, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
                });
            }
        }
        assertAll(checks);
    }

    /**
     * A class {@code shop} of the unnamed package imports and extends the type {@code shop.X} of the package, which
     * extends {@code shop.N}, which inherits the member type {@code N} of {@code shop.Hooks}. The class nests an
     * {@code X} of its own that carries {@code @GraphBindings} and extends {@code N}. Resolving that name walks from
     * the header of the class's {@code X} to the package's {@code X}, whose own header spells {@code N} too: it means
     * {@code shop.Hooks.N}, as javac binds it, so the class's {@code X} exports the hook that type marks, whether the
     * class is read first or last; and so does a member type of the class of another name, {@code Y}.
     */
    @Test
    void aNameTheHeadersOfTwoTypesOfOneCanonicalNameSpellIsResolvedForEach() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.X", "package shop;\npublic class X extends N { }\n");
        sources.put("shop.N", "package shop;\npublic class N extends Hooks { }\n");
        sources.put("shop.Hooks", """
                package shop;
                import io.github.graphmaquina.annotation.GraphHook;
                public class Hooks {
                    public static class N {
                        @GraphHook
                        public void done(java.time.Month month) { }
                    }
                }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            for (String name : List.of("X", "Y")) {
                checks.add(() -> assertTheHookOfTheHeaderNameIsExported(sources, file, name));
            }
        }
        assertAll(checks);
    }

    /**
     * The package {@code shop} declares the generic {@code A<T>} with the inner classes {@code Inner}, which extends
     * {@code A<T>}, {@code C}, which extends {@code Inner}, and {@code Leaf}. {@code Base}, a class of the unnamed
     * package, imports {@code shop.A} and extends {@code A<String>.C}. A class {@code shop} of the unnamed package
     * nests an {@code A} extending {@code Base}, and in it an inner {@code C} that extends the inherited {@code Inner}
     * and whose hook takes the inherited {@code Leaf}. Walking from the class's {@code C} meets the package's
     * {@code C}, whose {@code Inner} is owned by its own {@code A<T>}: the hook takes
     * {@code shop.A<java.lang.String>.Leaf}, as javac binds it, whether the class is read first or last; and so does
     * the hook of an inner class of another name, {@code D}.
     */
    @Test
    void anInnerTypeTheHeadersOfTwoTypesOfOneCanonicalNameSpellIsOwnedForEach() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.A", """
                package shop;
                public class A<T> {
                    public class Inner extends A<T> { }
                    public class C extends Inner { }
                    public class Leaf { }
                }
                """);
        sources.put("Base", """
                import shop.A;
                public class Base extends A<String>.C {
                    public Base() {
                        new A<String>().super();
                    }
                }
                """);
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("shop", "zzz")) {
            for (String name : List.of("C", "D")) {
                checks.add(() -> {
                    String label = "homonym-owner-" + name + "-" + file;
                    Generation generation = generate(label, beside(sources, file, """
                                public static class A extends Base {
                                    public class %s extends Inner {
                                        @io.github.graphmaquina.annotation.GraphHook
                                        public void done(Leaf entity) { }
                                    }
                                }
                            """.formatted(name)), List.of());
                    SymbolDescriptor symbol = generation.model().symbols().get(0);
                    assertEquals(List.of("shop.A." + name, "shop.A<java.lang.String>.Leaf"),
                            List.of(symbol.host(), symbol.candidates().get(0).parameterTypes().get(0)), label);
                    assertCompiles(generation, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
                });
            }
        }
        assertAll(checks);
    }

    /**
     * Classes of the unnamed package that only the class path holds name types of the package {@code shop} through an
     * import, beside a class {@code shop} of the same class path that nests a {@code Charging} without an action and
     * homonyms of the enums. Their class files bind the types of the package: a host of the unnamed package extending
     * {@code BaseHost}, which extends {@code shop.Charging}, exports the action that type marks; and a machine of the
     * unnamed package extending {@code Base}, which binds the package's enums, is refused, since its vocabulary,
     * generated in the unnamed package, can only write their canonical names, which there name the class's homonyms.
     */
    @Test
    void aClassFileOfTheUnnamedPackageMeansTheTypesItsImportsName() throws IOException {
        Map<String, String> library = new LinkedHashMap<>();
        library.put("shop.Charging", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Charging {
                    @GraphAction
                    public void charge() { }
                }
                """);
        library.put("BaseHost", "import shop.Charging;\npublic class BaseHost extends Charging { }\n");
        library.put("shop.Statuses", STATUSES);
        library.put("Order", "public final class Order { }\n");
        library.put("Base", """
                import io.github.graphmaquina.api.Machine;
                import shop.Statuses;
                public interface Base extends Machine<Order, Statuses.OrderStatus, Statuses.OrderTransition> { }
                """);
        library.put("shop", "public final class shop {\n    private shop() { }\n    public static class Charging { }\n"
                + STATUS_HOMONYMS + "}\n");
        Path classpath = jar("imports", library);

        assertAll(() -> {
            Generation host = generate("binary-host", Map.of("Payments", """
                    import io.github.graphmaquina.annotation.GraphBindings;
                    @GraphBindings
                    public final class Payments extends BaseHost { }
                    """), List.of(classpath));
            assertEquals(List.of("PaymentsRefs.Charge shop.Charging"), host.model().symbols().stream()
                    .map((SymbolDescriptor symbol) -> symbol.qualifiedName() + " "
                            + symbol.candidates().get(0).declaringType())
                    .toList());
            assertCompiles(host, List.of("-classpath", System.getProperty("java.class.path") + File.pathSeparator
                    + classpath));
        }, () -> {
            CodegenException machine = assertThrows(CodegenException.class, () -> generate("binary-machine",
                    Map.of("Orders", """
                            import io.github.graphmaquina.annotation.GenerateGraphApi;
                            @GenerateGraphApi
                            public interface Orders extends Base { }
                            """), List.of(classpath)));
            assertEquals(DiagnosticCode.SG603.name(), machine.code());
            assertEquals("the vocabulary generated for the machine Orders in the unnamed package cannot name"
                    + " shop.Statuses.OrderStatus: the type shop obscures the package shop there",
                    machine.getMessage());
        });
    }

    /**
     * A class of the unnamed package that only the class path holds, {@code Pair}, extends two interfaces of the
     * canonical name {@code shop.Statuses.Marker}: the one of the package {@code shop}, through an import, and the one
     * that a class {@code shop} of the unnamed package nests, through that class. Its class file tells them apart, a
     * canonical name does not, so a host whose hierarchy holds {@code Pair} is refused, with both types named.
     */
    @Test
    void aClassFileOfTheUnnamedPackageThatNamesTwoTypesOfOneCanonicalNameIsRefused() {
        Map<String, String> library = new LinkedHashMap<>();
        library.put("shop.Statuses", "package shop;\npublic final class Statuses {\n    private Statuses() { }\n"
                + "    public interface Marker { }\n}\n");
        library.put("shop", "public final class shop {\n    private shop() { }\n"
                + "    public static final class Statuses {\n        private Statuses() { }\n"
                + "        public interface Marker { }\n    }\n}\n");
        library.put("Pair", "import shop.Statuses;\n"
                + "public interface Pair extends Statuses.Marker, shop.Statuses.Marker { }\n");
        Path classpath = jar("pair", library);

        CodegenException refused = assertThrows(CodegenException.class, () -> generate("pair", Map.of("Payments", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings
                public final class Payments implements Pair {
                    @GraphAction
                    public void pay() { }
                }
                """), List.of(classpath)));

        assertEquals(DiagnosticCode.SG603.name(), refused.code());
        assertEquals("the class file of Pair of the unnamed package names both shop.Statuses.Marker of package shop on"
                + " the classpath and shop.Statuses.Marker of the unnamed package on the classpath, which the"
                + " generator cannot tell apart by their canonical name; rename one of them", refused.getMessage());
    }

    /**
     * A machine of the package {@code shop} declares the peer {@code Docs}, which there names {@code shop.Docs} of the
     * package, a {@code Machine} that is no {@code @GenerateGraphApi} machine, while a class {@code shop} of the
     * unnamed package nests a {@code @GenerateGraphApi} machine {@code Docs} of the same canonical name. The peer is
     * the type its class literal names, so it is refused as it is without the class, whether the class is read first
     * or last.
     */
    @Test
    void aPeerIsTheTypeItsClassLiteralNames() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Order", "package shop;\npublic final class Order { }\n");
        sources.put("shop.Statuses", STATUSES);
        sources.put("shop.Docs", """
                package shop;
                import io.github.graphmaquina.api.Machine;
                public interface Docs extends Machine<Order, Statuses.OrderStatus, Statuses.OrderTransition> { }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                @GraphPeers(Docs.class)
                public interface Orders extends Machine<Order, Statuses.OrderStatus, Statuses.OrderTransition> { }
                """);
        String machine = """
                    public enum S { A }
                    public enum T { GO }
                    public static final class E { }
                    @io.github.graphmaquina.annotation.GenerateGraphApi
                    public interface Docs extends io.github.graphmaquina.api.Machine<E, S, T> { }
                """;
        List<Executable> checks = new ArrayList<>();
        for (String layout : List.of("alone", "shop", "zzz")) {
            checks.add(() -> {
                Map<String, String> layoutSources = layout.equals("alone") ? sources : beside(sources, layout, machine);
                CodegenException refused = assertThrows(CodegenException.class,
                        () -> generate("peer-" + layout, layoutSources, List.of()), layout);
                assertEquals(DiagnosticCode.SG608.name(), refused.code(), layout);
                assertEquals("the peer machine shop.Docs declared by shop.Orders is not a @GenerateGraphApi machine of"
                        + " this compilation or of a dependency export index", refused.getMessage(), layout);
                assertEquals("sources/shop/Orders.java", refused.location().orElseThrow().path(), layout);
            });
        }
        assertAll(checks);
    }

    /**
     * A header that no index read, built as a header of the class path, is still a context the index resolves and
     * canonicalizes names in: its package is read by its canonical name, as javac answers it.
     */
    @Test
    void aHeaderTheIndexDidNotReadIsStillAContext() {
        TypeHeader foreign = new TypeHeader("lib.Foreign", ElementKind.CLASS, Set.of(Modifier.PUBLIC), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), TypeHeader.Scope.BINARY, Optional.empty());
        try (DeclarationIndex index = DeclarationIndex.open(List.of(), SourceFiles.classpath())) {
            assertEquals(Optional.of("java.util.Map.Entry"),
                    index.resolve(foreign, "java.util.Map.Entry").map(TypeHeader::canonicalName));
            assertEquals("java.lang.Comparable",
                    index.canonicalize(foreign, TypeUse.Declared.of("java.lang.Comparable")).render());
        }
    }

    /** The enums {@code shop.Statuses.OrderStatus} and {@code shop.Statuses.OrderTransition} of the package. */
    private static final String STATUSES = """
            package shop;
            public final class Statuses {
                private Statuses() { }
                public enum OrderStatus { OPEN, CLOSED }
                public enum OrderTransition { CLOSE }
            }
            """;

    /** The body of a class {@code shop} of the unnamed package nesting homonyms of {@link #STATUSES}. */
    private static final String STATUS_HOMONYMS = """
                public static final class Statuses {
                    private Statuses() { }
                    public enum OrderStatus { NEW }
                    public enum OrderTransition { PAY }
                }
            """;

    /** A class {@code shop} of the unnamed package nesting homonyms of {@link #STATUSES} with other constants. */
    private static final String HOMONYM_CLASS =
            "public final class shop {\n    private shop() { }\n" + STATUS_HOMONYMS + "}\n";

    /**
     * A machine {@code Orders} of the package {@code packageName}, empty for the unnamed package, over the enums
     * {@code shop.Statuses.OrderStatus} and {@code shop.Statuses.OrderTransition}, spelled by those canonical names or,
     * by a machine of the package {@code shop}, as {@code Statuses.OrderStatus} and {@code Statuses.OrderTransition}.
     */
    private static Map<String, String> machine(String packageName) {
        String header = packageName.isEmpty() ? "" : "package " + packageName + ";\n";
        String statuses = packageName.equals("shop") ? "Statuses" : "shop.Statuses";
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(qualified(packageName, "Order"), header + "public final class Order { }\n");
        sources.put(qualified(packageName, "Orders"), header + """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                public interface Orders extends Machine<Order, %1$s.OrderStatus, %1$s.OrderTransition> { }
                """.formatted(statuses));
        return sources;
    }

    /** The class {@code shop.Hosts} of {@link #nested()}: a host overrides, unmarked, the action its base marks. */
    private static final String NESTED_HOSTS = """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Model.Order;
                import shop.Statuses.OrderStatus;
                import shop.Statuses.OrderTransition;
                public final class Hosts {
                    private Hosts() { }
                    public static class Charging {
                        @GraphAction
                        public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                    @GraphBindings
                    public static final class Payments extends Charging {
                        @Override
                        public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                }
                """;

    /** The machine {@code shop.Orders} of {@link #nested()}, over its enums and entity and bound to its host. */
    private static final String NESTED_ORDERS = """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import shop.Model.Order;
                import shop.Statuses.OrderStatus;
                import shop.Statuses.OrderTransition;
                import static shop.Statuses.OrderStatus.*;
                import static shop.Statuses.OrderTransition.*;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = {PAID, CANCELLED},
                    state = ModelOrderRefs.Status.class, id = ModelOrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is paid or cancelled")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = HostsPaymentsRefs.Charge.class)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);

                    @OrdersGraph.Transition(id = CANCEL, from = NEW, to = CANCELLED)
                    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order);
                }
                """;

    /**
     * A machine of the package {@code shop} whose enums, entity and action hosts are member types: the concrete host
     * {@code Hosts.Payments} overrides, without marking it, the action its base {@code Hosts.Charging} marks.
     */
    private static Map<String, String> nested() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Statuses", """
                package shop;
                public final class Statuses {
                    private Statuses() { }
                    public enum OrderStatus { NEW, PAID, CANCELLED }
                    public enum OrderTransition { PAY, CANCEL }
                }
                """);
        sources.put("shop.Model", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Model {
                    private Model() { }
                    public static final class Order {
                        @GraphProperty private Statuses.OrderStatus status = Statuses.OrderStatus.NEW;
                        @GraphProperty public String id() { return "o1"; }
                        public Statuses.OrderStatus status() { return status; }
                        public void status(Statuses.OrderStatus value) { status = value; }
                    }
                }
                """);
        sources.put("shop.Hosts", NESTED_HOSTS);
        sources.put("shop.Orders", NESTED_ORDERS);
        return sources;
    }

    /**
     * Bodies of a class {@code shop} of the unnamed package, by what they hold: public homonyms of the enums of
     * {@link #nested()} with other constants, a public homonym of its entity without members, homonyms of its hosts
     * without members inside a private class, or homonyms of a type and of symbols generated for it.
     */
    private static Map<String, String> nestedHomonyms() {
        Map<String, String> homonyms = new LinkedHashMap<>();
        homonyms.put("enums", """
                    public static final class Statuses {
                        private Statuses() { }
                        public enum OrderStatus { OPEN }
                        public enum OrderTransition { CLOSE }
                    }
                """);
        homonyms.put("entity", """
                    public static final class Model {
                        private Model() { }
                        public static final class Order { }
                    }
                """);
        homonyms.put("hosts", """
                    private static final class Hosts {
                        private Hosts() { }
                        static class Charging { }
                        static final class Payments extends Charging { }
                    }
                """);
        homonyms.put("generated", """
                    static final class OrdersGraph { @interface Definition { } }
                    static final class ModelOrderRefs { static final class Id { } }
                    static final class HostsPaymentsRefs { static final class Charge { } }
                """);
        return homonyms;
    }

    /** The class {@code shop} of the unnamed package of {@link #unnamedNested}, nesting the declarations it names. */
    private static final String UNNAMED_NESTED_SHOP = """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphProperty;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                final class shop {
                    private shop() { }
                    public static final class Statuses {
                        private Statuses() { }
                        public enum OrderStatus { NEW, PAID, CANCELLED }
                        public enum OrderTransition { PAY, CANCEL }
                    }
                    public static final class Model {
                        private Model() { }
                        public static final class Order {
                            @GraphProperty private Statuses.OrderStatus status = Statuses.OrderStatus.NEW;
                            @GraphProperty public String id() { return "o1"; }
                            public Statuses.OrderStatus status() { return status; }
                            public void status(Statuses.OrderStatus value) { status = value; }
                        }
                    }
                    public static final class Hosts {
                        private Hosts() { }
                        public static class Charging {
                            @GraphAction
                            public ActionOutcome charge(TransitionContext<Model.Order, Statuses.OrderStatus,
                                    Statuses.OrderTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                        @GraphBindings
                        public static final class Payments extends Charging {
                            @Override
                            public ActionOutcome charge(TransitionContext<Model.Order, Statuses.OrderStatus,
                                    Statuses.OrderTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                    }
                }
                """;

    /**
     * The declarations of {@link #nested()} as member types of a class {@code shop} of the unnamed package, declared in
     * {@code file}, with a machine {@code Orders} of that package over them and a machine {@code Docs} that watches it.
     */
    private static Map<String, String> unnamedNested(String file) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(file, UNNAMED_NESTED_SHOP);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = shop.Statuses.OrderStatus.NEW,
                    terminals = {shop.Statuses.OrderStatus.PAID, shop.Statuses.OrderStatus.CANCELLED},
                    state = ShopModelOrderRefs.Status.class, id = ShopModelOrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = shop.Statuses.OrderStatus.NEW,
                    otherwise = "a new order is paid or cancelled")
                public interface Orders
                        extends Machine<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> {
                    @OrdersGraph.Transition(id = shop.Statuses.OrderTransition.PAY,
                        from = shop.Statuses.OrderStatus.NEW, to = shop.Statuses.OrderStatus.PAID,
                        action = ShopHostsPaymentsRefs.Charge.class)
                    Outcome<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> pay(
                            shop.Model.Order order);
                }
                """);
        sources.put("Docs", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                @GraphPeers(Orders.class)
                public interface Docs
                        extends Machine<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> { }
                """);
        return sources;
    }

    /**
     * Types of the package {@code shop} with the canonical names of the member types of {@link #unnamedNested}: enums
     * with other constants and package access, an entity whose marked getter is {@code ref}, and a host with package
     * access whose action is {@code refund}.
     */
    private static Map<String, String> namedHomonyms() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Statuses", """
                package shop;
                final class Statuses {
                    private Statuses() { }
                    enum OrderStatus { OPEN, CLOSED }
                    enum OrderTransition { CLOSE }
                }
                """);
        sources.put("shop.Model", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Model {
                    private Model() { }
                    public static final class Order {
                        @GraphProperty public String ref() { return "r1"; }
                    }
                }
                """);
        sources.put("shop.Hosts", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                final class Hosts {
                    private Hosts() { }
                    static class Charging { }
                    static final class Payments extends Charging {
                        @GraphAction public void refund() { }
                    }
                }
                """);
        return sources;
    }

    /**
     * {@code sources} and a class {@code shop} of the unnamed package whose body is {@code members}, declared in the
     * file {@code file}: public in its own file {@code shop.java}, and with package access in any other. In any other
     * file the class is an auxiliary class, and the references container generated for a host it nests names it from
     * outside that file, which javac's lint {@code auxiliaryclass}, when enabled, warns about. The strict compilation
     * here enables it with {@code -Xlint:all} and passes {@code -Werror}, so it disables that lint for a layout whose
     * class nests a host, as a build with {@code -Xlint:all -Werror} must for any host declared as an auxiliary class.
     */
    private static Map<String, String> beside(Map<String, String> sources, String file, String members) {
        Map<String, String> with = new LinkedHashMap<>(sources);
        with.put(file, (file.equals("shop") ? "public " : "") + "final class shop {\n    private shop() { }\n"
                + members + "}\n");
        return with;
    }

    private static String qualified(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + '.' + simpleName;
    }

    /**
     * A class {@code Hosts} of the package {@code packageName}, with {@code imports}, nesting a host whose action
     * {@code charge} takes a {@code parameter}.
     */
    private static String hosts(String packageName, String imports, String parameter) {
        return "package " + packageName + ";\nimport io.github.graphmaquina.annotation.GraphAction;\n" + imports + """
                public final class Hosts {
                    private Hosts() { }
                    public static final class Payments {
                        @GraphAction
                        public void charge(%s detail) { }
                    }
                }
                """.formatted(parameter);
    }

    /**
     * Asserts that a class {@code shop} of the unnamed package, declared in {@code file} beside {@code sources},
     * extends {@code shop.X} and nests a {@code @GraphBindings} class {@code name} extending {@code N}, exports the
     * hook that {@code shop.Hooks.N} marks, and that the sources and what was generated compile.
     */
    private void assertTheHookOfTheHeaderNameIsExported(Map<String, String> sources, String file, String name)
            throws IOException {
        String label = "homonym-header-" + name + "-" + file;
        Map<String, String> layout = new LinkedHashMap<>(sources);
        layout.put(file, "import shop.X;\n" + (file.equals("shop") ? "public " : "") + """
                final class shop extends X {
                    private shop() { }
                    @io.github.graphmaquina.annotation.GraphBindings
                    public static class %s extends N { }
                }
                """.formatted(name));
        Generation generation = generate(label, layout, List.of());
        List<String> symbols = new ArrayList<>();
        for (SymbolDescriptor symbol : generation.model().symbols()) {
            symbols.add(symbol.qualifiedName() + " " + symbol.candidates().get(0).declaringType());
        }
        assertEquals(List.of("Shop" + name + "Refs.Done shop.Hooks.N", "shop.HooksNRefs.Done shop.Hooks.N"),
                symbols, label);
        assertCompiles(generation, file.equals("zzz") ? List.of("-Xlint:-auxiliaryclass") : List.of());
    }

    /**
     * Asserts that generating {@code sources} exports one action whose one parameter is {@code parameter}, and that the
     * sources and what was generated compile.
     */
    private void assertParameters(String label, Map<String, String> sources, String parameter) throws IOException {
        Generation generation = generate(label, sources, List.of());
        assertEquals(List.of(parameter), generation.model().symbols().get(0).candidates().get(0).parameterTypes(),
                label);
        assertCompiles(generation, List.of());
    }

    /**
     * Asserts that generating {@code sources} over {@code jar} gives the machine {@code Orders} the states
     * {@code states} and the transitions {@code transitions}, and that the sources and what was generated compile.
     */
    private void assertStates(String label, Map<String, String> sources, Path jar, List<String> states,
            List<String> transitions) throws IOException {
        Generation generation = generate(label, sources, List.of(jar));
        GraphDescriptor graph = generation.model().graphs().get(0);
        assertEquals(List.of(states, transitions), List.of(graph.states(), graph.transitions()), label);
        assertCompiles(generation, List.of("-classpath", System.getProperty("java.class.path") + File.pathSeparator
                + jar));
    }

    /** The jar {@code name}.jar of {@code sources}, compiled by plain javac. */
    private Path jar(String name, Map<String, String> sources) {
        return SourceFiles.compileToJar(directory.resolve("jars").resolve(name), sources,
                directory.resolve("jars").resolve(name + ".jar"));
    }

    /**
     * Generates {@code sources}, written under {@code sources} of the project {@code label}, over the test classpath
     * and {@code extra}, and reads back the model of its manifest and every source it wrote.
     */
    private Generation generate(String label, Map<String, String> sources, List<Path> extra) throws IOException {
        Path project = directory.resolve(label);
        Path root = project.resolve("sources");
        List<Path> files = new ArrayList<>(SourceFiles.write(root, sources));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.addAll(extra);
        GenerationRequest request = new GenerationRequest(project, List.of(root), classpath,
                project.resolve("generated"), project.resolve("processor-generated"), project.resolve("classes"),
                project.resolve("work"), COMPILATION_ID);
        GenerationResult result = StateGraphCodegen.generate(request);
        Map<String, String> generated = new TreeMap<>();
        for (Path file : result.generatedSources()) {
            generated.put(request.sourcesOutput().toAbsolutePath().relativize(file).toString().replace('\\', '/'),
                    Files.readString(file, StandardCharsets.UTF_8));
        }
        files.addAll(result.generatedSources());
        return new Generation(project, ManifestCodec.readCompile(result.annotationManifest()).local().model(),
                generated, files);
    }

    /** Asserts that the sources and what was generated for them compile, with {@code extra} javac options. */
    private static void assertCompiles(Generation generation, List<String> extra) {
        SourceFiles.Compilation compilation =
                SourceFiles.compileStrict(generation.files(), generation.project().resolve("javac"), extra);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /**
     * One generation: its project, the model its manifest describes, every source it wrote by its path below the
     * generated source root, and the original and generated files javac compiles.
     */
    private record Generation(Path project, GenerationModel model, Map<String, String> sources, List<Path> files) {
    }
}
