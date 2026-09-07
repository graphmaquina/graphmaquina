package io.stategraph.export;

import io.stategraph.api.EdgeKind;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** One cell seen only through names, so the renderer never depends on the entity or the state enum constants. */
public record EdgeView(@NonNull String from, @NonNull String to, @NonNull EdgeKind kind, @NonNull String reason,
                       @NonNull Optional<String> action, @NonNull List<String> guards, boolean async,
                       @NonNull Optional<String> failureOf, @NonNull Optional<String> watchRule) {

    public EdgeView {
        guards = List.copyOf(guards);
    }
}
