package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import java.util.Optional;

/**
 * What a refusal reports, whichever way it was asked: the state it read, the identifier, the kind, the reason and the
 * guard. {@link #ofCheck} reads it from the answer of an eligibility check and {@link #ofExecute} from the outcome of
 * an execute, so a test compares the two with {@code assertEquals} although each reports its own entity, which a test
 * that asks one entity and executes on an equal one cannot expect to be the same object. Empty stands for no refusal:
 * an eligible answer, or an outcome that is no {@code Rejected}.
 */
public record Refusal(Enum<?> from, Enum<?> transition, RejectionKind kind, String reason, Optional<String> guard) {

    /** A refusal of {@code transition} from {@code from} that names no guard. */
    public static Optional<Refusal> of(Enum<?> from, Enum<?> transition, RejectionKind kind, String reason) {
        return Optional.of(new Refusal(from, transition, kind, reason, Optional.empty()));
    }

    /** The refusal of {@code transition} from {@code from} by the guard {@code guard}. */
    public static Optional<Refusal> byGuard(Enum<?> from, Enum<?> transition, String guard, String reason) {
        return Optional.of(new Refusal(from, transition, RejectionKind.GUARD, reason, Optional.of(guard)));
    }

    /** The refusal {@code answer} reports, empty for an eligible answer. */
    public static <E, S extends Enum<S>, T extends Enum<T>> Optional<Refusal> ofCheck(Eligibility<E, S, T> answer) {
        return answer.asIneligible().map((Eligibility.Ineligible<E, S, T> refused) -> new Refusal(refused.from(),
                refused.transition(), refused.kind(), refused.reason(), refused.guard()));
    }

    /** The refusal {@code outcome} reports, empty for an outcome that is no {@code Rejected}. */
    public static <E, S extends Enum<S>, T extends Enum<T>> Optional<Refusal> ofExecute(Outcome<E, S, T> outcome) {
        return outcome.asRejected().map((Outcome.Rejected<E, S, T> refused) -> new Refusal(refused.from(),
                refused.transition(), refused.kind(), refused.reason(), refused.guard()));
    }
}
