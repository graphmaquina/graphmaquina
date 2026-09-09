package io.stategraph.codegen.source;

import com.sun.source.tree.CompilationUnitTree;
import io.stategraph.codegen.model.SourceLocation;
import java.util.Objects;

/**
 * One original compilation unit that was parsed but never attributed, together with the origin of its file.
 * The tree exposes declarations, modifiers, types and annotations; nothing here evaluates a body.
 */
public record SourceUnit(CompilationUnitTree tree, SourceLocation origin) {

    public SourceUnit {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(origin, "origin");
    }
}
