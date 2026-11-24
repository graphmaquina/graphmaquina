package io.github.graphmaquina.example.loan;

import io.github.graphmaquina.annotation.GraphProperty;
import java.time.Instant;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A book on loan. The machine reads and writes its state, which starts at BORROWED; the inventory system writes LOST
 * itself when it reports the book lost.
 */
@RequiredArgsConstructor
public final class Loan {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @Getter
    @NonNull
    private final Instant dueAt;
    @GraphProperty
    @Getter
    private LoanStatus status = LoanStatus.BORROWED;

    public void setStatus(@NonNull LoanStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Loan#" + id;
    }
}
