package io.github.graphmaquina.processor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import lombok.RequiredArgsConstructor;

/**
 * Reports {@code SG609} for a declaration still written with the retired matrix vocabulary.
 *
 * <p>The retired annotations no longer exist in core, so they are recognized by the qualified names they had,
 * read off the annotation types javac resolved. A project that still compiles against an older release has
 * them on its classpath, and is told at the very annotation it wrote what to write instead; a project whose
 * imports no longer resolve is told by javac itself, and the migration guide supplies the replacement. Nothing
 * is kept loadable, and no machine is reconstructed out of an old declaration.</p>
 *
 * <p>A machine that mixes the two vocabularies is refused like one that uses only the old: the state a
 * half-migrated declaration describes is not the state either vocabulary alone describes, so accepting the
 * typed half and ignoring the rest would build a graph the author did not declare.</p>
 */
@RequiredArgsConstructor
final class LegacyDeclarations {

    private static final String CODE = "SG609";
    private static final String PACKAGE = "io.github.graphmaquina.annotation.";

    /** The retired vocabulary a facade carried, in the order a diagnostic prefers to name it. */
    private static final List<String> ON_TYPE = List.of(PACKAGE + "StateGraph", PACKAGE + "Row", PACKAGE + "Rows",
            PACKAGE + "Cell", PACKAGE + "Cells", PACKAGE + "Forbid", PACKAGE + "Forbids", PACKAGE + "Watch",
            PACKAGE + "Watches");

    /** The retired vocabulary an operation carried. */
    private static final List<String> ON_MEMBER = List.of(PACKAGE + "Action", PACKAGE + "Move");

    /** Every retired name the processor still claims, so a declaration that resolves one is met by SG609. */
    static Set<String> retiredAnnotationTypes() {
        Set<String> names = new LinkedHashSet<>(ON_TYPE);
        names.addAll(ON_MEMBER);
        names.add(PACKAGE + "When");
        return Set.copyOf(names);
    }

    private final Diagnostics diagnostics;

    /**
     * Reports every declaration of {@code round} that still uses the retired vocabulary and answers with the
     * qualified names of the types that carry one, so nothing tries to build a graph out of them afterwards.
     */
    Set<String> report(RoundEnvironment round) {
        Set<String> reported = new LinkedHashSet<>();
        for (Element root : round.getRootElements()) {
            if (root instanceof TypeElement type) {
                report(type, reported);
            }
        }
        return reported;
    }

    /** One type and the types it encloses; the facade itself and each of its operations is reported once. */
    private void report(TypeElement type, Set<String> reported) {
        find(type, ON_TYPE).ifPresent((AnnotationMirror mirror) -> {
            reported.add(type.getQualifiedName().toString());
            DiagnosticAnchor.of(type, mirror).report(diagnostics, CODE, describe(type));
        });
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD) {
                find(member, ON_MEMBER).ifPresent((AnnotationMirror mirror) -> {
                    reported.add(type.getQualifiedName().toString());
                    DiagnosticAnchor.of(member, mirror).report(diagnostics, CODE, describe(type, member));
                });
            } else if (member instanceof TypeElement nested) {
                report(nested, reported);
            }
        }
    }

    /** The first of {@code candidates} that {@code element} carries, which is the one to point at. */
    private static Optional<AnnotationMirror> find(Element element, List<String> candidates) {
        for (String candidate : candidates) {
            for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                        .contentEquals(candidate)) {
                    return Optional.of(mirror);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What to write instead of the retired header of {@code type}, with its vocabulary spelled as the simple name
     * followed by {@code Graph}: the name the generator gives that vocabulary when normalizing the simple name to
     * PascalCase leaves it unchanged.
     */
    private static String describe(TypeElement type) {
        String graph = type.getSimpleName() + "Graph";
        return type.getQualifiedName() + " declares its graph with the retired matrix annotations, which this"
                + " version no longer reads: mark it @GenerateGraphApi, give it a transition enum as"
                + " Machine<E, S, T>, and declare it with the vocabulary generated for it — @" + graph
                + ".Definition(initial = ..., state = <Entity>Refs.<Property>.class, id ="
                + " <Entity>Refs.<Property>.class), @" + graph + ".StatePolicy, @" + graph + ".Transition, @"
                + graph + ".Reject, and the Watch and When of each <Owner><Peer> pair it declares with"
                + " @GraphPeers; mark the members it binds with @GraphAction, @GraphGuard, @GraphHook,"
                + " @GraphCondition or @GraphProperty and name each of them as <Host>Refs.<Member>.class";
    }

    /** What to write instead of the retired declaration of one operation. */
    private static String describe(TypeElement type, Element member) {
        String graph = type.getSimpleName() + "Graph";
        return type.getQualifiedName() + "." + member.getSimpleName() + " declares its transition with the"
                + " retired matrix annotations, which this version no longer reads: use @" + graph
                + ".Transition(id = ..., from = ..., to = ...), mark the method it runs with @GraphAction and"
                + " the methods it asks with @GraphGuard, and name each of them as <Host>Refs.<Member>.class";
    }
}
