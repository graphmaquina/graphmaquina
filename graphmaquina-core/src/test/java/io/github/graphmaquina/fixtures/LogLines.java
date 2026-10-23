package io.github.graphmaquina.fixtures;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a {@link LibraryLog} captured as lines that a test compares with literals: what changes from one run to
 * the next, the id of a call, the elapsed time and the budget of an awaiting line, is replaced by a stable text.
 */
public final class LogLines {

    private static final Pattern CALL_ID = Pattern.compile("(call|trigger)=(\\d+)");
    private static final Pattern ELAPSED = Pattern.compile(" elapsed=\\d+us$");
    private static final Pattern BUDGET = Pattern.compile(" budget=(\\d+)us$");

    private LogLines() { }

    /**
     * Each record as {@code LEVEL message}, every call id replaced by a letter in order of first appearance, A for the
     * first, the elapsed time that ends a line replaced by {@code <us>}, and so is the budget of an awaiting line,
     * which only TRACE logs. No line carries the id 0.
     */
    public static List<String> lines(LibraryLog log) {
        Map<String, String> letters = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (LogRecord record : log.records()) {
            Matcher ids = CALL_ID.matcher(record.getMessage());
            StringBuilder line = new StringBuilder(record.getLevel().getName()).append(' ');
            while (ids.find()) {
                String id = ids.group(2);
                assertNotEquals("0", id, record.getMessage());
                if (!letters.containsKey(id)) {
                    letters.put(id, String.valueOf((char) ('A' + letters.size())));
                }
                ids.appendReplacement(line, ids.group(1) + "=" + letters.get(id));
            }
            ids.appendTail(line);
            String text = ELAPSED.matcher(line).replaceFirst(" elapsed=<us>");
            lines.add(BUDGET.matcher(text).replaceFirst(" budget=<us>"));
        }
        return lines;
    }

    /** The budgets, in microseconds, that the awaiting lines of {@code log} printed, in order. */
    public static List<Long> budgets(LibraryLog log) {
        List<Long> budgets = new ArrayList<>();
        for (LogRecord record : log.records()) {
            Matcher budget = BUDGET.matcher(record.getMessage());
            if (budget.find()) {
                budgets.add(Long.parseLong(budget.group(1)));
            }
        }
        return budgets;
    }
}
