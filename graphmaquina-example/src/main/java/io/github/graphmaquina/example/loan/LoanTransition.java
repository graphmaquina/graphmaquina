package io.github.graphmaquina.example.loan;

/**
 * Identifiers of the loan machine's transitions: the gate REQUEST_EXTENSION, which only a check asks, the operations
 * MARK_OVERDUE and RETURN, and REPORT_LOST, which the library's inventory system takes.
 */
public enum LoanTransition { REQUEST_EXTENSION, MARK_OVERDUE, RETURN, REPORT_LOST }
