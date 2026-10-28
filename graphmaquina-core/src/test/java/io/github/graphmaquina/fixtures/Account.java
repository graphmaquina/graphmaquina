package io.github.graphmaquina.fixtures;

import lombok.Getter;

/**
 * Test entity of the account machine: an id, a mutable status, and the three facts its automatic edges guard on,
 * which a test sets: whether the personal info and the address are complete, and whether the account is premium.
 */
@Getter
public final class Account {

    private final String id;
    private AccountStatus status;
    private boolean infoComplete;
    private boolean addressComplete;
    private boolean premium;

    public Account(String id, AccountStatus status) {
        this.id = id;
        this.status = status;
    }

    public void setStatus(AccountStatus status) { this.status = status; }

    public void setInfoComplete(boolean infoComplete) { this.infoComplete = infoComplete; }

    public void setAddressComplete(boolean addressComplete) { this.addressComplete = addressComplete; }

    public void setPremium(boolean premium) { this.premium = premium; }

    @Override
    public String toString() { return "Account#" + id; }
}
