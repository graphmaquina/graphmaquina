package io.github.graphmaquina.example.onboarding;

/**
 * States of an account in onboarding, from a new account through its password, its personal info and its address to
 * a terminal activation, plain or premium, or to a review that closes it.
 */
public enum AccountStatus { NEW, PASSWORD_SET, INFO_SET, ADDRESS_SET, ACTIVE, PREMIUM, FLAGGED, REVIEW, CLOSED }
