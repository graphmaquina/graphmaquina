package io.github.graphmaquina.processor;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * Binds explicitly written annotation values to their source expressions, including implicit repeatable containers.
 * Indexes only the annotated declaration's modifiers and keeps semantic identities within one processing environment.
 * Defaults, classfile declarations and malformed shapes without a matching expression have no source location.
 */
final class AnnotationSources {

    /** The exact source expression, its compilation unit and its start offset for declaration-order comparisons. */
    record Location(CompilationUnitTree unit, Tree tree, long start) { }

    private final Trees trees;
    private final Map<Element, Index> declarations = new IdentityHashMap<>();

    AnnotationSources(Trees trees) {
        this.trees = trees;
    }

    /** Finds only a verified explicit value binding, never javac's enclosing-declaration fallback. */
    Optional<Location> find(Element element, AnnotationMirror annotation, AnnotationValue value) {
        Index index = declarations.computeIfAbsent(element, (Element declaration) -> new Index(declaration));
        Map<AnnotationValue, Location> values = index.annotations.get(annotation);
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(value));
    }

    /** A declaration-local identity index built from resolved annotation types and named annotation arguments. */
    private final class Index {

        private final Map<AnnotationMirror, Map<AnnotationValue, Location>> annotations = new IdentityHashMap<>();
        private final Map<Element, ArrayDeque<AnnotationTree>> roots = new HashMap<>();
        private final CompilationUnitTree unit;

        private Index(Element element) {
            TreePath path = trees.getPath(element);
            unit = path == null ? null : path.getCompilationUnit();
            if (path == null) {
                return;
            }
            List<? extends AnnotationTree> written = switch (path.getLeaf()) {
                case ClassTree declaration -> declaration.getModifiers().getAnnotations();
                case MethodTree declaration -> declaration.getModifiers().getAnnotations();
                case VariableTree declaration -> declaration.getModifiers().getAnnotations();
                default -> List.of();
            };
            for (AnnotationTree tree : written) {
                TreePath typePath = TreePath.getPath(path, tree.getAnnotationType());
                Element type = typePath == null ? null : trees.getElement(typePath);
                if (type instanceof TypeElement) {
                    roots.computeIfAbsent(type, (Element key) -> new ArrayDeque<>()).add(tree);
                }
            }
            for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                AnnotationTree direct = take(mirror.getAnnotationType().asElement());
                if (direct != null) {
                    bindAnnotation(mirror, direct);
                } else {
                    bindRepeated(mirror);
                }
            }
        }

        private AnnotationTree take(Element type) {
            ArrayDeque<AnnotationTree> matching = roots.get(type);
            return matching == null ? null : matching.poll();
        }

        /** Only @Repeatable metadata authorizes pairing a synthetic container with direct repeated annotations. */
        private void bindRepeated(AnnotationMirror container) {
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : container.getElementValues().entrySet()) {
                if (!entry.getKey().getSimpleName().contentEquals("value")
                        || !(entry.getValue().getValue() instanceof List<?> values)) {
                    continue;
                }
                for (Object item : values) {
                    if (item instanceof AnnotationValue value && value.getValue() instanceof AnnotationMirror nested
                            && repeatsInto(nested, container)) {
                        AnnotationTree source = take(nested.getAnnotationType().asElement());
                        if (source != null) {
                            bindAnnotation(nested, source);
                        }
                    }
                }
            }
        }

        private boolean repeatsInto(AnnotationMirror nested, AnnotationMirror container) {
            for (AnnotationMirror meta : nested.getAnnotationType().asElement().getAnnotationMirrors()) {
                if (meta.getAnnotationType().asElement() instanceof TypeElement type
                        && type.getQualifiedName().contentEquals("java.lang.annotation.Repeatable")) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                            : meta.getElementValues().entrySet()) {
                        if (entry.getKey().getSimpleName().contentEquals("value")
                                && entry.getValue().getValue() instanceof DeclaredType declared
                                && declared.asElement().equals(container.getAnnotationType().asElement())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void bindAnnotation(AnnotationMirror mirror, AnnotationTree tree) {
            Map<String, ExpressionTree> arguments = new HashMap<>();
            for (ExpressionTree argument : tree.getArguments()) {
                if (argument instanceof AssignmentTree assignment) {
                    if (assignment.getVariable() instanceof IdentifierTree identifier) {
                        arguments.put(identifier.getName().toString(), assignment.getExpression());
                    }
                } else {
                    arguments.put("value", argument);
                }
            }
            Map<AnnotationValue, Location> values = new IdentityHashMap<>();
            annotations.put(mirror, values);
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : mirror.getElementValues().entrySet()) {
                ExpressionTree expression = arguments.get(entry.getKey().getSimpleName().toString());
                if (expression != null) {
                    bindValue(values, entry.getValue(), expression);
                }
            }
        }

        /** Recursion follows only matching semantic/source shapes; omitted defaults cannot consume source arguments. */
        private void bindValue(Map<AnnotationValue, Location> values, AnnotationValue value, ExpressionTree expression) {
            if (expression.getKind() == Tree.Kind.ERRONEOUS) {
                return;
            }
            if (value.getValue() instanceof List<?> array) {
                List<? extends ExpressionTree> items = expression instanceof NewArrayTree initializer
                        ? initializer.getInitializers() : List.of(expression);
                if (items == null || items.size() != array.size()) {
                    return;
                }
                for (int at = 0; at < items.size(); at++) {
                    if (array.get(at) instanceof AnnotationValue item) {
                        bindValue(values, item, items.get(at));
                    }
                }
            } else if (value.getValue() instanceof AnnotationMirror nested) {
                if (!(expression instanceof AnnotationTree annotation)) {
                    return;
                }
                bindAnnotation(nested, annotation);
            }
            long start = trees.getSourcePositions().getStartPosition(unit, expression);
            if (start >= 0) {
                values.put(value, new Location(unit, expression, start));
            }
        }
    }
}
