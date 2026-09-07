package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/** build() of the DSL found problems. Tests assert on codes(), never on the text. */
public final class InvalidGraphException extends StateMachineException {

    /** One problem found while validating a graph: a stable code paired with a human-readable message. */
    public record Problem(@NonNull String code, @NonNull String message) {
        public Problem {
            if (code.isBlank() || message.isBlank()) {
                throw new IllegalArgumentException("code and message must not be blank");
            }
        }
    }

    @Getter
    private final List<Problem> problems;

    public InvalidGraphException(MachineKey<?, ?> key, List<Problem> problems) {
        super(describe(key, problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> codes() {
        List<String> codes = new ArrayList<>();
        for (Problem problem : problems) {
            codes.add(problem.code());
        }
        return List.copyOf(codes);
    }

    private static String describe(MachineKey<?, ?> key, List<Problem> problems) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(problems, "problems");
        StringBuilder text = new StringBuilder("the graph " + key + " has " + problems.size() + " problem(s):");
        for (Problem problem : problems) {
            text.append("\n  [").append(problem.code()).append("] ").append(problem.message());
        }
        return text.toString();
    }
}
