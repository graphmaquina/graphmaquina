package io.github.graphmaquina.codegen.emit;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emits the references container of every host: a top-level {@code <Host>Refs} whose nested symbol types name one
 * exported member each.
 *
 * <p>A symbol implements the category of every role its family exports, so an overload set that declares two
 * roles is usable wherever either category is required, and it records its host, its original member name and
 * those roles in metadata the processor reads back. The types are final, have a private constructor and are
 * never instantiated: they are compile-time names, not runtime objects. The container suppresses the deprecation and
 * removal warnings of the host it names, as {@link JavaSource#SUPPRESS_DEPRECATION} explains.</p>
 *
 * <p>Every name a container writes is qualified, {@code java.lang.SuppressWarnings} included, except that a host of
 * the unnamed package can only be written by its simple name. Each name is read through its first segment, and any
 * type in scope in the container under that simple name takes the name over: a symbol type of the container
 * shadows a type of that name (JLS 6.4.1) and obscures a package of that name, and a type of the host's package, a
 * top-level type generated in that package, the container included, or a public type of {@code java.lang} obscures
 * the package it starts with (JLS 6.4.2).
 * The bootstrap refuses a host whose names a type of its package or of {@code java.lang} would take; a container
 * whose host a symbol type or a generated type would take is refused here. Both refusals are {@code SG603}.</p>
 */
final class SymbolEmitter {

    private static final Map<SymbolKind, String> CATEGORIES = categories();

    private static Map<SymbolKind, String> categories() {
        Map<SymbolKind, String> categories = new LinkedHashMap<>();
        categories.put(SymbolKind.ACTION, "io.github.graphmaquina.symbol.ActionSymbol");
        categories.put(SymbolKind.GUARD, "io.github.graphmaquina.symbol.GuardSymbol");
        categories.put(SymbolKind.HOOK, "io.github.graphmaquina.symbol.HookSymbol");
        categories.put(SymbolKind.CONDITION, "io.github.graphmaquina.symbol.ConditionSymbol");
        categories.put(SymbolKind.PROPERTY, "io.github.graphmaquina.symbol.PropertySymbol");
        categories.put(SymbolKind.LOOKUP, "io.github.graphmaquina.symbol.LookupSymbol");
        return Map.copyOf(categories);
    }

    private SymbolEmitter() {
    }

    /**
     * The containers of {@code symbols}, one compilation unit per host, in the order their first symbols are given,
     * each holding its symbols in the order they are given. Every symbol of one host shares the container its
     * qualified name spells, so symbols sorted by qualified name, as the bootstrap gives them, are never reordered.
     *
     * @param generated the simple names of the top-level types generated in each package
     * @throws io.github.graphmaquina.codegen.CodegenException {@code SG603} if a symbol type of a container, or a
     *     top-level type generated in its package, takes over the first segment of the container's host
     */
    static List<GeneratedSource> emit(List<SymbolDescriptor> symbols, Map<String, Set<String>> generated) {
        Map<String, List<SymbolDescriptor>> containers = new LinkedHashMap<>();
        for (SymbolDescriptor symbol : symbols) {
            String container = symbol.qualifiedName().substring(0, symbol.qualifiedName().lastIndexOf('.'));
            containers.computeIfAbsent(container, name -> new ArrayList<>()).add(symbol);
        }
        List<GeneratedSource> sources = new ArrayList<>();
        for (Map.Entry<String, List<SymbolDescriptor>> container : containers.entrySet()) {
            sources.add(emit(container.getKey(), container.getValue(), generated));
        }
        return List.copyOf(sources);
    }

    /**
     * One container. A container is a top-level type whose simple name joins the host's enclosing names in
     * PascalCase, followed by {@code Refs}, so the last dot of its canonical name separates its package from its
     * simple name exactly; the canonical name of a container of the unnamed package has no dot.
     */
    private static GeneratedSource emit(String container, List<SymbolDescriptor> symbols,
            Map<String, Set<String>> generated) {
        int lastDot = container.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : container.substring(0, lastDot);
        String simpleName = container.substring(lastDot + 1);
        String host = symbols.get(0).host();
        List<String> symbolNames = symbols.stream().map(SymbolEmitter::simpleName).toList();
        // A refusal is anchored at the symbol whose type takes the host's first segment, or else at the first one.
        SourceLocation anchor = symbols.stream()
            .filter((SymbolDescriptor symbol) -> simpleName(symbol).equals(ApiEmitter.firstSegment(host)))
            .map(SymbolDescriptor::origin)
            .findFirst()
            .orElse(symbols.get(0).origin());
        ApiEmitter.requireNameable("the references container " + simpleName,
            ApiEmitter.meanings(simpleName, packageName, symbolNames, generated), "its host " + host, host, anchor);

        JavaSource source = new JavaSource();
        source.header(packageName, List.of());
        source.javadoc("Generated symbol references for the exported members of {@link " + host + "}.",
            "",
            "<p>Generated by the StateGraph code generator. Do not edit.</p>");
        source.line(JavaSource.SUPPRESS_DEPRECATION);
        source.line("public final class " + simpleName + " {");
        source.indent();
        source.line("");
        source.line("private " + simpleName + "() {");
        source.line("}");
        for (SymbolDescriptor symbol : symbols) {
            symbol(source, symbol);
        }
        source.outdent();
        source.line("}");
        return new GeneratedSource(container, source.toString());
    }

    /** The simple name of the type generated for {@code symbol}, nested in its container. */
    private static String simpleName(SymbolDescriptor symbol) {
        return symbol.qualifiedName().substring(symbol.qualifiedName().lastIndexOf('.') + 1);
    }

    private static void symbol(JavaSource source, SymbolDescriptor symbol) {
        String name = simpleName(symbol);
        source.line("");
        source.javadoc(documentation(symbol));
        source.line("@io.github.graphmaquina.annotation.internal.GeneratedSymbol(host = " + symbol.host()
            + ".class, member = \"" + symbol.member() + "\", kinds = " + kinds(symbol) + ")");
        source.line("public static final class " + name + " implements " + implemented(symbol) + " {");
        source.indent();
        source.line("");
        source.line("private " + name + "() {");
        source.line("}");
        source.outdent();
        source.line("}");
    }

    /** Links the original declaration: the member itself for a method, its declaring type for a property. */
    private static String documentation(SymbolDescriptor symbol) {
        Optional<PropertyDescriptor> property = symbol.property();
        if (property.isPresent()) {
            return "References the {@code " + property.get().logicalName() + "} property of {@link "
                + property.get().declaringType() + "}.";
        }
        return "References {@link " + symbol.host() + "#" + symbol.member() + "}.";
    }

    /** The category interfaces of every role the family exports, in the declaration order of the roles. */
    private static String implemented(SymbolDescriptor symbol) {
        List<String> categories = symbol.kinds().stream()
                .map(CATEGORIES::get).toList();
        return String.join(", ", categories);
    }

    /** The roles as an annotation value: one constant on its own, several inside braces. */
    private static String kinds(SymbolDescriptor symbol) {
        List<String> constants = symbol.kinds().stream()
                .map(kind -> "io.github.graphmaquina.annotation.internal.SymbolKind." + kind.name()).toList();
        return constants.size() == 1 ? constants.get(0) : "{" + String.join(", ", constants) + "}";
    }
}
