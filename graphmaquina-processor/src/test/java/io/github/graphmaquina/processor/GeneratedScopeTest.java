package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Machine;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A simple name in a companion means whatever the scope of the generated class resolves it to, and that scope
 * holds more than the types the processor names: the other types of its package, the member types it inherits
 * from the interface it implements, the {@code Builder} a set companion declares, and every variable it
 * declares. A type is written by its simple name only where that name resolves to the type itself, and no
 * variable the companion declares takes the name of a package the companion writes a qualified name of. What the
 * author's declarations take from it instead, a type wherever it heads a name and a field only where an expression
 * reads it, is refused as SG603 before the companion is written, and so is a companion of a later round that would
 * take a name of one already written. A type another processor generates into the package after a companion is
 * written is reported, at the next call of the processor, at the facade or set whose companion it takes a name
 * from, unless it comes in the last round, the one whose {@code processingOver()} is true, which no call of the
 * processor follows.
 */
class GeneratedScopeTest {

    /** The first line of the body of the shop domain's facade, after which a test adds members. */
    private static final String FACADE = "public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {";

    /** The set of the shop domain, holding its one machine. */
    private static final String SHOP = """
            package shop;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Shop extends Machines { Orders orders(); }
            """;

    /** The first bytes of an AppleDouble file, which no Java compiler can read as a source. */
    private static final byte[] APPLE_DOUBLE = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ',
        'O', 'S', ' ', 'X', (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};

    /**
     * A record of the facade's package named {@code Override} would capture a bare {@code @Override}, and a
     * member type of the facade named like the action host would capture the host inside the companion.
     */
    @Test
    void neitherAPackageTypeNorAMemberTypeOfTheFacadeCapturesTheNamesOfTheCompanion() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("shop.Override", """
                package shop;
                public record Override(String reason, long cents) { }
                """);
        sources.put("shop.Orders", sources.get("shop.Orders").replace("public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {",
                "public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {\n"
                        + "    interface Payments { void settled(Order order); }"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String companion = result.generated("shop.OrdersRuntime");
        assertTrue(companion.contains("@java.lang.Override"), companion);
        assertTrue(companion.contains("graph(shop.Payments payments)"), companion);
    }

    /**
     * The set's package declares a {@code Class}, an {@code IllegalArgumentException} and the action host
     * {@code Builder}, which the set companion's own nested {@code Builder} hides. The companion still compiles,
     * and it still throws the JDK's exception for a machine that is not one of its asynchronous ones.
     */
    @Test
    void theTypesOfTheSetsPackageAndItsOwnBuilderCaptureNothing() throws Exception {
        Map<String, String> sources = shop("app.BuilderRefs.Charge.class", "");
        sources.put("app.Builder", """
                package app;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import shop.Order;
                import shop.OrderStatus;
                import shop.OrderTransition;
                public final class Builder {
                    @GraphAction public CompletionStage<ActionOutcome> charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return CompletableFuture.completedFuture(ActionOutcome.ok());
                    }
                }
                """);
        sources.put("app.Class", """
                package app;
                public enum Class { ECONOMY, BUSINESS }
                """);
        sources.put("app.IllegalArgumentException", """
                package app;
                public final class IllegalArgumentException extends RuntimeException {
                    public IllegalArgumentException(String message) { super(message); }
                }
                """);
        sources.put("app.Shop", """
                package app;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                import shop.Orders;
                @StateGraphs
                public interface Shop extends Machines {
                    Orders orders();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        Class<?> host = result.load("app.Builder");
        Object builder = result.load("app.ShopRuntime").getMethod("builder", Duration.class, host)
                .invoke(null, Duration.ofSeconds(1), host.getConstructor().newInstance());
        InvocationTargetException refused = assertThrows(InvocationTargetException.class, () -> builder.getClass()
                .getMethod("asyncTimeout", Class.class, Duration.class).invoke(builder, Machine.class, Duration.ofSeconds(1)));
        assertEquals(java.lang.IllegalArgumentException.class, refused.getCause().getClass());
    }

    /**
     * Two packages {@code a} and {@code b} share every simple name, so the set writes the types of {@code b}
     * qualified, and a hook host named {@code B} would give a variable {@code b} that obscures the package.
     */
    @Test
    void noVariableObscuresAPackageTheCompanionNamesQualified() {
        Map<String, String> sources = new LinkedHashMap<>();
        twin(sources, "a", "");
        twin(sources, "b", ", afterCommit = BRefs.Touched.class");
        sources.put("b.B", """
                package b;
                import io.github.graphmaquina.annotation.GraphHook;
                public final class B {
                    @GraphHook public void touched(Thing thing) { }
                }
                """);
        sources.put("fx.Both", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    b.Things second();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String set = result.generated("fx.BothRuntime");
        assertTrue(set.contains("public static Builder builder(B b2)"), set);
    }

    /**
     * A qualified name starts with a package, which a type of the same name hides wherever the name heads one, and
     * a variable only where an expression reads it; no spelling of the name gets past either. A companion that
     * would write such a name is therefore refused where its facade is declared, with SG603 naming what takes the
     * name, rather than left to fail javac.
     */
    @Test
    void sg603ATypeOfTheFacadesPackageNamedLikeAPackageTheCompanionWrites() {
        Map<String, String> packageType = shop("PaymentsRefs.Charge.class", "");
        packageType.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        packageType.put("shop.billing", "package shop;\npublic final class billing { }\n");
        packageType.put("shop.Orders", packageType.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));
        Compilation.Result typed = Compilation.compileTyped(packageType);
        assertEquals(1, typed.countError("SG603"), typed.describe());
        assertTrue(typed.errors().get(0).contains("billing.Payment") && typed.errors().get(0).contains("shop.billing")
                && typed.errors().get(0).contains("Orders"), typed.describe());
        assertFalse(typed.hasGenerated("shop.OrdersRuntime"), typed.describe());
    }

    /**
     * A build may give javac only some files of the facade's package while its source path holds them all, as
     * Maven does with an AppleDouble file it never compiles or with a file the build's excludes name. The processor
     * looks up only the names the companions write, so javac reads neither file: the build compiles without a word
     * about them and writes no class for them.
     */
    @Test
    void theProcessorReadsNoFileOfThePackageTheBuildLeavesOut() throws IOException {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("shop.Shop", SHOP);
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Path shop = prepared.request().sources().get(0).resolve("shop");
        Files.write(shop.resolve("._Orders.java"), APPLE_DOUBLE);
        Files.writeString(shop.resolve("Excluded.java"), "package shop;\npublic final class Excluded {\n",
                StandardCharsets.UTF_8);

        Compilation.Result result = Compilation.compileWithSourcePath(prepared, List.copyOf(sources.keySet()));

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("shop.OrdersRuntime") && result.hasGenerated("shop.ShopRuntime"),
                result.describe());
        List<String> files = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.diagnostics()) {
            if (diagnostic.getSource() != null) {
                files.add(Path.of(diagnostic.getSource().getName()).getFileName().toString());
            }
        }
        assertFalse(files.contains("._Orders.java") || files.contains("Excluded.java"), result.describe());
        Path classes = prepared.request().classesOutput().resolve("shop");
        assertFalse(Files.exists(classes.resolve("_Orders.class")), result.describe());
        assertFalse(Files.exists(classes.resolve("Excluded.class")), result.describe());
    }

    /**
     * A type of the facade's package that only the source path holds takes a name the companion writes as surely
     * as one the build compiles, since javac resolves that name against the source path too. The facade is
     * refused with the same SG603 either way.
     */
    @Test
    void sg603ATypeOfTheFacadesPackageThatOnlyTheSourcePathHolds() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("shop.billing", "package shop;\npublic final class billing { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));
        List<String> withoutTaker = new ArrayList<>(sources.keySet());
        withoutTaker.remove("shop.billing");

        Compilation.Result listed = Compilation.compileWithSourcePath(Compilation.prepare(sources),
                List.copyOf(sources.keySet()));
        Compilation.Result unlisted = Compilation.compileWithSourcePath(Compilation.prepare(sources), withoutTaker);

        assertEquals(List.of("[SG603] the companion OrdersRuntime of shop.Orders writes billing.Payment, but in its"
                + " scope billing names the type shop.billing, not a package: rename that type or the package"
                + " billing"), listed.errors(), listed.describe());
        assertEquals(listed.errors(), unlisted.errors(), unlisted.describe());
        assertEquals("Orders.java", source(unlisted, "SG603"), unlisted.describe());
        assertFalse(unlisted.hasGenerated("shop.OrdersRuntime"), unlisted.describe());
    }

    /** A constant of the facade, which the companion inherits, hides a package in every expression of its body. */
    @Test
    void sg603AConstantOfTheFacadeNamedLikeAPackageTheCompanionWrites() {
        Compilation.Result constant = Compilation.compileTyped(guardedBy("billing", "    String billing = \"audit\";\n"));
        assertEquals(1, constant.countError("SG603"), constant.describe());
        assertTrue(constant.errors().get(0).contains("billing.Checks") && constant.errors().get(0).contains("Orders.billing"),
                constant.describe());
        assertFalse(constant.hasGenerated("shop.OrdersRuntime"), constant.describe());
    }

    /**
     * A field takes a name only where an expression could read it as a variable. The companion writes the package
     * {@code billing}, the entity, {@code Set} and {@code Supplier} in type positions alone, so constants of the
     * facade named like them take nothing from it.
     */
    @Test
    void constantsNamedLikeNamesTheCompanionWritesOnlyAsTypesTakeNothing() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace(FACADE, FACADE + "\n    String billing = \"audit\";\n    String Order = \"order\";"
                        + "\n    String Set = \"set\";\n    String Supplier = \"supplier\";")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String companion = result.generated("shop.OrdersRuntime");
        assertTrue(companion.contains("pay(Order order, billing.Payment input)"), companion);
    }

    /** The companion reads the constants of the state enum, where a constant of the facade of that name is read instead. */
    @Test
    void sg603AConstantOfTheFacadeNamedLikeATypeWhoseMembersTheCompanionReads() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("shop.Orders", sources.get("shop.Orders").replace(FACADE,
                FACADE + "\n    String OrderStatus = \"status\";"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("the companion OrdersRuntime of shop.Orders writes the type"
                + " shop.OrderStatus as OrderStatus, but in its expressions OrderStatus names the constant"
                + " Orders.OrderStatus: rename that constant"), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /**
     * An annotation of an input type names an enum constant, which an expression reads: a constant of the facade
     * named like the package of that enum takes that name, though the package of the input type is no
     * expression's.
     */
    @Test
    void sg603AConstantOfTheFacadeNamedLikeAPackageAnAnnotationOfAnInputReads() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("billing.Level", "package billing;\npublic enum Level { LOW, HIGH }\n");
        sources.put("billing.Audited", """
                package billing;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                public @interface Audited { Level level(); }
                """);
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;",
                        "import billing.Audited;\nimport billing.Level;\nimport billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace(FACADE, FACADE + "\n    String billing = \"audit\";")
                .replace("pay(Order order);", "pay(Order order, @Audited(level = Level.HIGH) Payment payment);"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("the companion OrdersRuntime of shop.Orders writes billing.Level, but"
                + " in its expressions billing names the constant Orders.billing, not a package: rename that constant"
                + " or the package billing"), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /** The companion's own public field {@code KEY}, which no rename can move, hides a package named {@code KEY}. */
    @Test
    void sg603APackageNamedLikeTheKeyFieldOfTheCompanion() {
        Compilation.Result key = Compilation.compileTyped(guardedBy("KEY", ""));
        assertEquals(1, key.countError("SG603"), key.describe());
        assertTrue(key.errors().get(0).contains("KEY.Checks") && key.errors().get(0).contains("KEY"), key.describe());
        assertFalse(key.hasGenerated("shop.OrdersRuntime"), key.describe());
    }

    /** The same rule in a set's package: a type there named like a package the set companion writes qualified. */
    @Test
    void sg603ATypeOfTheSetsPackageNamedLikeAPackageItsCompanionWrites() {
        Map<String, String> sources = new LinkedHashMap<>();
        twin(sources, "a", "");
        twin(sources, "b", "");
        sources.put("fx.b", "package fx;\npublic final class b { }\n");
        sources.put("fx.Both", """
                package fx;
                import b.Things;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    Things second();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("fx.b") && result.errors().get(0).contains("Both"), result.describe());
        assertFalse(result.hasGenerated("fx.BothRuntime"), result.describe());
        assertTrue(result.hasGenerated("b.ThingsRuntime"), result.describe());
    }

    /**
     * A class {@code fx} of the unnamed package declares member types {@code b} and {@code Override}, whose
     * canonical names {@code fx.b} and {@code fx.Override} are the very names top-level types of the package
     * {@code fx} would have. They are no types of that package, so they take nothing from the set's companion,
     * which writes {@code b.Things} qualified and a bare {@code @Override}, and compiles. A top-level type
     * {@code fx.b} beside them still takes the name.
     */
    @Test
    void memberTypesOfAClassNamedLikeTheSetsPackageTakeNothing() {
        Map<String, String> sources = both();
        sources.put("fx", """
                public final class fx {
                    public static final class b { }
                    public static final class Override { }
                }
                """);

        Compilation.Result memberTypes = Compilation.compileTyped(sources);
        sources.put("fx.b", "package fx;\npublic final class b { }\n");
        Compilation.Result topLevelType = Compilation.compileTyped(sources);

        assertTrue(memberTypes.success(), memberTypes.describe());
        String set = memberTypes.generated("fx.BothRuntime");
        assertTrue(set.contains("@Override") && !set.contains("@java.lang.Override"), set);
        assertEquals(List.of("[SG603] the companion BothRuntime of fx.Both writes b.Things, but in its scope b names"
                + " the type fx.b, not a package: rename that type or the package b"), topLevelType.errors(),
                topLevelType.describe());
        assertFalse(topLevelType.hasGenerated("fx.BothRuntime"), topLevelType.describe());
    }

    /**
     * javac's Filer looks up the name of a type it is asked to create as {@code Elements.getTypeElement} does, which
     * answers a member type whose canonical name it is: here a member type {@code BothRuntime} of a class {@code fx}
     * of the unnamed package. Given that class's source, the Filer refuses to create the set's companion
     * {@code fx.BothRuntime}, as it refuses over a top-level type of that name, so both are refused as SG603 where
     * the set is declared, before the companion is written.
     */
    @Test
    void sg603ATypeWhoseCanonicalNameIsTheSetsCompanions() {
        Map<String, String> memberType = both();
        memberType.put("fx", "public final class fx {\n    public static final class BothRuntime { }\n}\n");
        Map<String, String> topLevelType = both();
        topLevelType.put("fx.BothRuntime", "package fx;\npublic final class BothRuntime { }\n");

        Compilation.Result member = Compilation.compileTyped(memberType);
        Compilation.Result topLevel = Compilation.compileTyped(topLevelType);

        List<String> refused = List.of("[SG603] the generated runtime fx.BothRuntime for fx.Both conflicts with"
                + " existing type fx.BothRuntime");
        assertEquals(refused, member.errors(), member.describe());
        assertFalse(member.hasGenerated("fx.BothRuntime"), member.describe());
        assertEquals(refused, topLevel.errors(), topLevel.describe());
    }

    /** The packages {@code a} and {@code b} of twin machines, and the set {@code fx.Both} holding one of each. */
    private static Map<String, String> both() {
        Map<String, String> sources = new LinkedHashMap<>();
        twin(sources, "a", "");
        twin(sources, "b", "");
        sources.put("fx.Both", """
                package fx;
                import b.Things;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    Things second();
                }
                """);
        return sources;
    }

    /**
     * {@code java.lang}, imported on demand into the companion, takes the name of a package the companion writes
     * qualified when it has a type of that name: here the package {@code Thread} of an input type. A class
     * {@code java} of the unnamed package nesting {@code lang.billing}, whose canonical name
     * {@code java.lang.billing} is the very name a type {@code billing} of {@code java.lang} would have, is no type
     * of {@code java.lang}, so it takes nothing from a companion that writes {@code billing.Payment}.
     */
    @Test
    void sg603AJavaLangTypeNamedLikeAPackageTheCompanionWrites() {
        Map<String, String> nested = paidWith("billing");
        nested.put("java", """
                public final class java {
                    public static final class lang {
                        public static final class billing { }
                    }
                }
                """);

        Compilation.Result javaLang = Compilation.compileTyped(paidWith("Thread"));
        Compilation.Result nestedInJava = Compilation.compileTyped(nested);

        assertEquals(List.of("[SG603] the companion OrdersRuntime of shop.Orders writes Thread.Payment, but in its"
                + " scope Thread names the type java.lang.Thread, not a package: rename the package Thread"),
                javaLang.errors(), javaLang.describe());
        assertFalse(javaLang.hasGenerated("shop.OrdersRuntime"), javaLang.describe());
        assertTrue(nestedInJava.success(), nestedInJava.describe());
        assertTrue(nestedInJava.generated("shop.OrdersRuntime").contains("billing.Payment"),
                nestedInJava.generated("shop.OrdersRuntime"));
    }

    /** The shop domain whose operation {@code pay} takes a {@code <root>.Payment}, which the companion writes. */
    private static Map<String, String> paidWith(String root) {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put(root + ".Payment", "package " + root + ";\npublic record Payment(long cents) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;",
                        "import " + root + ".Payment;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));
        return sources;
    }

    /**
     * A package named like a companion this processor generates into the facade's package is hidden by that
     * companion wherever the companion of the facade writes a qualified name of it, whether it is that companion
     * itself or the one of a set of the package, which exists only once the set is generated. The facade imports
     * the types, which an import names by their package all the same.
     */
    @Test
    void sg603APackageNamedLikeACompanionOfTheFacadesPackage() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("OrdersRuntime.Payment", "package OrdersRuntime;\npublic record Payment(long cents) { }\n");
        sources.put("ShopRuntime.Reason", "package ShopRuntime;\npublic record Reason(String text) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;",
                        "import OrdersRuntime.Payment;\nimport ShopRuntime.Reason;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);")
                .replace("cancel(Order order);", "cancel(Order order, Reason reason);"));
        sources.put("shop.Shop", SHOP);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(2, result.countError("SG603"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("the companion OrdersRuntime of"
                + " shop.Orders writes OrdersRuntime.Payment, but in its scope OrdersRuntime names the companion"
                + " OrdersRuntime itself, not a package: rename the package OrdersRuntime")), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("the companion OrdersRuntime of"
                + " shop.Orders writes ShopRuntime.Reason, but in its scope ShopRuntime names the companion"
                + " shop.ShopRuntime this processor reserves for shop.Shop, not a package: rename the package"
                + " ShopRuntime")), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /**
     * The set companion writes qualified an action host whose simple name the JDK's {@code Executor} takes there.
     * Its package is named like the companion of the set's machine, which this processor generates beside the
     * set, so the set is refused; the machine, which imports the host, is generated.
     */
    @Test
    void sg603APackageNamedLikeTheCompanionOfAMachineOfTheSet() {
        Map<String, String> sources = shop("ExecutorRefs.Charge.class", "");
        sources.put("OrdersRuntime.Executor", """
                package OrdersRuntime;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Order;
                import shop.OrderStatus;
                import shop.OrderTransition;
                public final class Executor {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("shop.Orders", sources.get("shop.Orders").replace("import static shop.OrderStatus.*;",
                "import OrdersRuntime.ExecutorRefs;\nimport static shop.OrderStatus.*;"));
        sources.put("shop.Shop", SHOP);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("the companion ShopRuntime of shop.Shop writes OrdersRuntime.Executor,"
                + " but in its scope OrdersRuntime names the companion shop.OrdersRuntime this processor reserves for"
                + " shop.Orders, not a package: rename the package OrdersRuntime"), result.describe());
        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.hasGenerated("shop.OrdersRuntime"), result.describe());
        assertFalse(result.hasGenerated("shop.ShopRuntime"), result.describe());
    }

    /**
     * Another processor generates the set of the facade's package in the first round, so the processor sees that
     * set only in the second, once the companion of the facade, which writes a name of the package
     * {@code ShopRuntime}, is written. The set's companion would take that name, so the set is refused where it is
     * declared, before its companion is written.
     */
    @Test
    void sg603ASetOfALaterRoundWhoseCompanionWouldTakeANameAWrittenCompanionWrites() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("ShopRuntime.Reason", "package ShopRuntime;\npublic record Reason(String text) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import ShopRuntime.Reason;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Reason reason);"));

        Compilation.Result result = withAnotherProcessor(sources, Map.of("shop.Shop", SHOP));

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("the companion OrdersRuntime of shop.Orders, written in an earlier"
                + " round, writes ShopRuntime.Reason, but in its scope ShopRuntime would name the companion"
                + " shop.ShopRuntime of shop.Shop, which this round reserves, not a package: rename shop.Shop or the"
                + " package ShopRuntime"), result.describe());
        assertEquals("Shop.java", source(result, "SG603"), result.describe());
        assertTrue(result.hasGenerated("shop.OrdersRuntime"), result.describe());
        assertFalse(result.hasGenerated("shop.ShopRuntime"), result.describe());
    }

    /**
     * Another processor generates, in the first round, types of the facade's package named like the package of
     * the input type and like {@code java.lang.Override}, which the companion written in that round writes by its
     * simple name. Both take those names from the companion once they exist, so the processor says so where the
     * facade is declared.
     */
    @Test
    void sg603ATypeOfALaterRoundThatTakesANameAWrittenCompanionWrites() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));

        Compilation.Result result = withAnotherProcessor(sources, Map.of(
                "shop.billing", "package shop;\npublic final class billing { }\n",
                "shop.Override", "package shop;\npublic final class Override { }\n"));

        assertEquals(2, result.countError("SG603"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("the companion OrdersRuntime of"
                + " shop.Orders, written in an earlier round, writes billing.Payment, but in its scope billing now names"
                + " the type shop.billing, new in a later round, not a package: rename that type or the package"
                + " billing")), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("the companion OrdersRuntime of"
                + " shop.Orders, written in an earlier round, writes the type java.lang.Override as Override, but in its"
                + " scope Override now names the type shop.Override, new in a later round: rename that type")),
                result.describe());
        assertEquals("Orders.java", source(result, "SG603"), result.describe());
    }

    /**
     * Another processor, run before this one, writes a type named like the facade's companion in the first round,
     * so the Filer refuses that companion, which is reported as SG599 and never written. The type named like the
     * package of its input, which the same processor writes, therefore takes nothing from any companion.
     */
    @Test
    void aCompanionTheFilerRefusedIsNotCheckedInALaterRound() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class", "");
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("shop.Orders", sources.get("shop.Orders")
                .replace("import static shop.OrderStatus.*;", "import billing.Payment;\nimport static shop.OrderStatus.*;")
                .replace("pay(Order order);", "pay(Order order, Payment payment);"));
        AnotherProcessor.generates(Map.of(
                "shop.OrdersRuntime", "package shop;\npublic final class OrdersRuntime { }\n",
                "shop.billing", "package shop;\npublic final class billing { }\n"));

        Compilation.Result result = Compilation.compileWithoutLifecycle(Compilation.prepare(sources), List.of(
                "-processor", AnotherProcessor.class.getName() + "," + StateGraphProcessor.class.getName()));

        assertEquals(1, result.countError("SG599"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("could not write shop.OrdersRuntime")),
                result.describe());
        assertEquals(0, result.countError("SG603"), result.describe());
    }

    /** Compiles {@code sources} with the processor and {@link AnotherProcessor}, which generates {@code generated}. */
    private static Compilation.Result withAnotherProcessor(Map<String, String> sources, Map<String, String> generated) {
        AnotherProcessor.generates(generated);
        return Compilation.compileWithoutLifecycle(Compilation.prepare(sources), List.of("-processor",
                StateGraphProcessor.class.getName() + "," + AnotherProcessor.class.getName()));
    }

    /** The file name of the source the first error carrying {@code code} is reported in. */
    private static String source(Compilation.Result result, String code) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.diagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null).contains("[" + code + "]")) {
                return Path.of(diagnostic.getSource().getName()).getFileName().toString();
            }
        }
        return "";
    }

    /**
     * The shop domain whose payment is guarded by {@code <root>.Checks.ok}, a static guard of a class that the
     * facade's member type {@code Checks} makes the companion write qualified; {@code members} are added to the
     * facade.
     */
    private static Map<String, String> guardedBy(String root, String members) {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class, guards = " + root + ".ChecksRefs.Ok.class", "");
        sources.put(root + ".Checks", """
                package %s;
                import io.github.graphmaquina.annotation.GraphGuard;
                import io.github.graphmaquina.api.GuardContext;
                import io.github.graphmaquina.api.GuardResult;
                import shop.Order;
                import shop.OrderStatus;
                import shop.OrderTransition;
                public final class Checks {
                    @GraphGuard public static GuardResult ok(GuardContext<Order, OrderStatus, OrderTransition> context) {
                        return GuardResult.pass();
                    }
                }
                """.formatted(root));
        sources.put("shop.Orders", sources.get("shop.Orders").replace(
                "public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {",
                "public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {\n"
                        + "    interface Checks { }\n" + members));
        return sources;
    }

    /**
     * The facade declares or inherits a member type named like the state enum it is declared over, and the set
     * one named like the transition enum: inside each companion that member type owns the simple name, so the
     * enum is written qualified. The header spells the enum qualified, as the facade's own body must.
     */
    @Test
    void aMemberTypeNamedLikeAnEnumOfTheMachineCapturesNothing() {
        Map<String, String> declared = members("    enum Status { ARCHIVED }\n", "", "    enum Step { OTHER }\n", "");
        Compilation.Result direct = Compilation.compileTyped(declared);
        assertTrue(direct.success(), direct.describe());
        assertTrue(direct.generated("fx.DocsRuntime").contains("MachineKey<Doc, fx.Status, Step>"),
                direct.generated("fx.DocsRuntime"));
        assertTrue(direct.generated("fx.LibraryRuntime").contains("StateMachines.Builder<Doc, Status, fx.Step>"),
                direct.generated("fx.LibraryRuntime"));

        Map<String, String> inherited = members("", ", Base", "", ", Shelf");
        inherited.put("fx.Base", "package fx;\npublic interface Base { enum Status { ARCHIVED } }\n");
        inherited.put("fx.Shelf", "package fx;\npublic interface Shelf { interface Step { } }\n");
        Compilation.Result through = Compilation.compileTyped(inherited);
        assertTrue(through.success(), through.describe());
        assertTrue(through.generated("fx.DocsRuntime").contains("MachineKey<Doc, fx.Status, Step>"),
                through.generated("fx.DocsRuntime"));
    }

    /**
     * A machine over {@code fx.Status} and {@code fx.Step}, whose facade adds {@code facadeMembers} and extends
     * {@code facadeSupertypes} too, held by a set that adds {@code setMembers} and extends {@code setSupertypes}.
     */
    private static Map<String, String> members(String facadeMembers, String facadeSupertypes, String setMembers,
            String setSupertypes) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Status", "package fx;\npublic enum Status { DRAFT, LIVE }\n");
        sources.put("fx.Step", "package fx;\npublic enum Step { PUBLISH }\n");
        sources.put("fx.Doc", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public class Doc {
                    @GraphProperty private final String id = "x";
                    @GraphProperty private Status status = Status.DRAFT;
                    public String getId() { return id; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """);
        sources.put("fx.Docs", """
                package fx;
                import static fx.Status.DRAFT;
                import static fx.Status.LIVE;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @DocsGraph.Definition(initial = DRAFT, terminals = {LIVE}, state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "only publish")
                public interface Docs extends Machine<Doc, fx.Status, Step>%s {
                %s
                    @DocsGraph.Transition(id = Step.PUBLISH, from = DRAFT, to = LIVE)
                    Outcome<Doc, fx.Status, Step> publish(Doc doc);
                }
                """.formatted(facadeSupertypes, facadeMembers));
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Library extends Machines%s {
                %s
                    Docs docs();
                }
                """.formatted(setSupertypes, setMembers));
        return sources;
    }

    /** The shop domain: orders paid with {@code action} and the facade over them, with {@code header} added to its definition. */
    private static Map<String, String> shop(String action, String header) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.OrderStatus", "package shop;\npublic enum OrderStatus { NEW, PAID, CANCELLED }\n");
        sources.put("shop.OrderTransition", "package shop;\npublic enum OrderTransition { PAY, CANCEL }\n");
        sources.put("shop.Order", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        sources.put("shop.Payments", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Payments {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static shop.OrderStatus.*;
                import static shop.OrderTransition.*;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = {PAID, CANCELLED}, state = OrderRefs.Status.class,
                    id = OrderRefs.Id.class%s)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is paid or cancelled")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = %s)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);

                    @OrdersGraph.Transition(id = CANCEL, from = NEW, to = CANCELLED)
                    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order);
                }
                """.formatted(header, action));
        return sources;
    }

    /**
     * A package holding Status, Step, Thing and a Things machine over them, every simple name shared with its twin;
     * {@code definition} is added to the machine's Definition.
     */
    private static void twin(Map<String, String> sources, String name, String definition) {
        sources.put(name + ".Status", "package " + name + ";\npublic enum Status { OPEN, DONE }\n");
        sources.put(name + ".Step", "package " + name + ";\npublic enum Step { FINISH }\n");
        sources.put(name + ".Thing", """
                package %s;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Thing {
                    @GraphProperty private Status status = Status.OPEN;
                    @GraphProperty public String getId() { return "thing"; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """.formatted(name));
        sources.put(name + ".Things", """
                package %1$s;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static %1$s.Status.DONE;
                import static %1$s.Status.OPEN;
                import static %1$s.Step.FINISH;
                @GenerateGraphApi
                @ThingsGraph.Definition(initial = OPEN, terminals = DONE, state = ThingRefs.Status.class, id = ThingRefs.Id.class%2$s)
                @ThingsGraph.StatePolicy(state = OPEN, otherwise = "a thing is finished")
                public interface Things extends Machine<Thing, Status, Step> {
                    @ThingsGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                    Outcome<Thing, Status, Step> finish(Thing thing);
                }
                """.formatted(name, definition));
    }
}
