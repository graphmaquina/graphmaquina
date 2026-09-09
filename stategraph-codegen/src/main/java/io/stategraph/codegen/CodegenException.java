package io.stategraph.codegen;

import io.stategraph.codegen.model.SourceLocation;
import java.util.Objects;
import java.util.Optional;

/**
 * Reported by the generator and its manifest codec for a specific, diagnosable failure: an SG error code, a
 * message and, when the failure can be anchored to an original source, the location of that anchor.
 */
public final class CodegenException extends RuntimeException {

    private final String code;
    private final Optional<SourceLocation> location;

    public CodegenException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.location = Optional.empty();
    }

    public CodegenException(String code, String message, SourceLocation location) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.location = Optional.of(location);
    }

    /** The SG error code identifying this failure, for example {@code "SG602"}. */
    public String code() {
        return code;
    }

    /** The original source location this failure is anchored to, when one applies. */
    public Optional<SourceLocation> location() {
        return location;
    }
}
