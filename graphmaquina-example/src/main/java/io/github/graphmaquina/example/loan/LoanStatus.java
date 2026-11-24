package io.github.graphmaquina.example.loan;

/**
 * States of a loan: borrowed, overdue once past its due date, and the two closed states, returned and lost, which are
 * terminal.
 */
public enum LoanStatus { BORROWED, OVERDUE, RETURNED, LOST }
