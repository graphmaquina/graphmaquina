package io.github.graphmaquina.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * The members of types, listed once per type and kept by simple name, so that looking one member up by its name walks
 * none of the others: a host that exports thousands of members, and the references container that holds a symbol type
 * for each, are each listed once however many of their members are looked up.
 *
 * <p>javac enters the members of a source type afresh in every round, so an index answers only within the round it
 * was filled in: {@link #forget} empties it, and the processor forgets at the start of every round.</p>
 */
final class MemberIndex {

    private final Elements elements;
    private final Map<TypeElement, Map<String, List<Element>>> declared = new HashMap<>();
    private final Map<TypeElement, Map<String, List<Element>>> all = new HashMap<>();

    MemberIndex(Elements elements) {
        this.elements = elements;
    }

    /** The element utilities the members are listed with. */
    Elements elements() {
        return elements;
    }

    /** The members {@code type} declares itself named {@code name}, in the order it declares them. */
    List<Element> declared(TypeElement type, CharSequence name) {
        return declared.computeIfAbsent(type, (TypeElement key) -> byName(key.getEnclosedElements()))
                .getOrDefault(name.toString(), List.of());
    }

    /**
     * The members {@code type} declares or inherits named {@code name}, as {@link Elements#getAllMembers} lists them,
     * in the order it lists them.
     */
    List<Element> all(TypeElement type, CharSequence name) {
        return all.computeIfAbsent(type, (TypeElement key) -> byName(elements.getAllMembers(key)))
                .getOrDefault(name.toString(), List.of());
    }

    /** Forgets every listing, which the members a later round enters replace. */
    void forget() {
        declared.clear();
        all.clear();
    }

    private static Map<String, List<Element>> byName(List<? extends Element> members) {
        Map<String, List<Element>> byName = new LinkedHashMap<>();
        for (Element member : members) {
            byName.computeIfAbsent(member.getSimpleName().toString(), (String key) -> new ArrayList<>()).add(member);
        }
        return byName;
    }
}
