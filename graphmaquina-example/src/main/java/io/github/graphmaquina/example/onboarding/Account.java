package io.github.graphmaquina.example.onboarding;

import io.github.graphmaquina.annotation.GraphProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An account in onboarding. The machine reads and writes its state, which starts at NEW; the application sets the
 * three facts its automatic steps guard on as the data arrives: whether the personal info and the address are
 * complete, and whether the account is premium.
 */
@RequiredArgsConstructor
public final class Account {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @GraphProperty
    @Getter
    private AccountStatus status = AccountStatus.NEW;
    @Getter
    private boolean infoComplete;
    @Getter
    private boolean addressComplete;
    @Getter
    private boolean premium;

    public void setStatus(@NonNull AccountStatus status) {
        this.status = status;
    }

    public void setInfoComplete(boolean infoComplete) {
        this.infoComplete = infoComplete;
    }

    public void setAddressComplete(boolean addressComplete) {
        this.addressComplete = addressComplete;
    }

    public void setPremium(boolean premium) {
        this.premium = premium;
    }

    @Override
    public String toString() {
        return "Account#" + id;
    }
}
