package io.github.graphmaquina.fixtures;

/** States of the account machine: an onboarding that ends active, premium or closed after a review. */
public enum AccountStatus { NEW, PASSWORD_SET, INFO_SET, ADDRESS_SET, ACTIVE, PREMIUM, FLAGGED, REVIEW, CLOSED }
