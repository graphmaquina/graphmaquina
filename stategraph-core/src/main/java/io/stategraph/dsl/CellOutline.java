package io.stategraph.dsl;

import io.stategraph.api.EdgeKind;
import java.util.Optional;
import lombok.NonNull;

/** One cell of a {@link GraphOutline}, by names: enough for the structural checks, nothing about code. */
public record CellOutline(@NonNull String from, @NonNull String to, @NonNull EdgeKind kind, @NonNull Optional<String> onFailure) {

    public CellOutline {
        if (onFailure.isPresent() && kind != EdgeKind.ACTION) {
            throw new IllegalArgumentException(from + " → " + to + ": onFailure is only allowed on ACTION");
        }
    }
}
