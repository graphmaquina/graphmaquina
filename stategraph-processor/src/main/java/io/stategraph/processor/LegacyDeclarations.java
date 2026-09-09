package io.stategraph.processor;

import io.stategraph.annotation.Action;
import io.stategraph.annotation.Cell;
import io.stategraph.annotation.Cells;
import io.stategraph.annotation.Forbid;
import io.stategraph.annotation.Forbids;
import io.stategraph.annotation.Move;
import io.stategraph.annotation.Row;
import io.stategraph.annotation.Rows;
import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.Watch;
import io.stategraph.annotation.Watches;
import java.lang.annotation.Annotation;
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
 * Reports {@code SG609} for a declaration still written with the v0.2 string vocabulary.
 *
 * <p>The old annotations are kept in core, deprecated, for this diagnostic alone: a project that has not been
 * migrated would otherwise meet an annotation nobody claims and a machine nobody generated a companion for,
 * with nothing to say why. Here it is told, at the very annotation it wrote, what to write instead.</p>
 *
 * <p>A machine that mixes the two vocabularies is refused like one that uses only the old: the state a
 * half-migrated declaration describes is not the state either vocabulary alone describes, so accepting the
 * typed half and ignoring the rest would build a graph the author did not declare.</p>
 */
@RequiredArgsConstructor
final class LegacyDeclarations {

    private static final String CODE = "SG609";

    /** The v0.2 vocabulary a facade carries, in the order a diagnostic prefers to name it. */
    @SuppressWarnings("deprecation")
    private static final List<Class<? extends Annotation>> ON_TYPE = List.of(StateGraph.class,
            Row.class, Rows.class, Cell.class, Cells.class, Forbid.class, Forbids.class,
            Watch.class, Watches.class);

    /** The v0.2 vocabulary an operation carries. */
    @SuppressWarnings("deprecation")
    private static final List<Class<? extends Annotation>> ON_MEMBER = List.of(Action.class, Move.class);

    private final Annotations annotations;
    private final Diagnostics diagnostics;

    /**
     * Reports every declaration of {@code round} that still uses the v0.2 vocabulary and answers with the
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
    private Optional<AnnotationMirror> find(Element element, List<Class<? extends Annotation>> candidates) {
        for (Class<? extends Annotation> candidate : candidates) {
            Optional<AnnotationMirror> found = annotations.find(element, candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** What to write instead of the v0.2 header of {@code type}, spelled with the names generated for it. */
    private static String describe(TypeElement type) {
        String graph = type.getSimpleName() + "Graph";
        return type.getQualifiedName() + " declares its graph with the v0.2 string annotations, which this"
                + " version no longer reads: mark it @GenerateGraphApi and declare it with the vocabulary"
                + " generated for it — @" + graph + ".Definition(initial = ..., state = <Entity>Refs.<Property>"
                + ".class, id = <Entity>Refs.<Property>.class), @" + graph + ".Row, @" + graph + ".Cell, @"
                + graph + ".Forbid, and the Watch and When of each <Owner><Peer> pair it declares with"
                + " @GraphPeers; mark the members it binds with @GraphAction, @GraphGuard, @GraphHook,"
                + " @GraphCondition or @GraphProperty and name each of them as <Host>Refs.<Member>.class";
    }

    /** What to write instead of the v0.2 cell of one operation. */
    private static String describe(TypeElement type, Element member) {
        String graph = type.getSimpleName() + "Graph";
        return type.getQualifiedName() + "." + member.getSimpleName() + " declares its cell with the v0.2"
                + " string annotations, which this version no longer reads: use @" + graph + ".Action or @"
                + graph + ".Move, mark the method it runs with @GraphAction and the methods it asks with"
                + " @GraphGuard, and name each of them as <Host>Refs.<Member>.class";
    }
}
