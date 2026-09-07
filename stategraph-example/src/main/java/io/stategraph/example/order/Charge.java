package io.stategraph.example.order;

import lombok.NonNull;

/** Result of a charge attempt against a gateway: either accepted, or declined with a gateway code. */
public record Charge(boolean declined, @NonNull String code) {

    public static Charge accepted() { return new Charge(false, "OK"); }

    public static Charge declined(String code) { return new Charge(true, code); }
}
