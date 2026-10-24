package io.github.graphmaquina.codegen.source;

import com.sun.source.tree.CompilationUnitTree;
import io.github.graphmaquina.codegen.model.SourceLocation;
import lombok.NonNull;

/**
 * One original compilation unit that was parsed but never attributed, together with the origin of its file.
 * The tree exposes declarations, modifiers, types and annotations; nothing here evaluates a body.
 */
public record SourceUnit(@NonNull CompilationUnitTree tree, @NonNull SourceLocation origin) {

    public SourceUnit {
    }
}
