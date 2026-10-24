package io.github.graphmaquina.codegen;

import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * Reported by the generator and its manifest codec for a specific, diagnosable failure: an SG error code, a
 * message and, when the failure can be anchored to an original source, the location of that anchor.
 */
@Getter
public final class CodegenException extends RuntimeException {

    /** The SG error code identifying this failure, for example {@code "SG602"}. */
    private final String code;

    /** The original source location this failure is anchored to, when one applies. */
    private final Optional<SourceLocation> location;

    public CodegenException(@NonNull String code, String message) {
        super(message);
        this.code = code;
        this.location = Optional.empty();
    }

    public CodegenException(@NonNull String code, String message, SourceLocation location) {
        super(message);
        this.code = code;
        this.location = Optional.of(location);
    }
}
