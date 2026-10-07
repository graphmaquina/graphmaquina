package io.github.graphmaquina.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what the library logs through {@code System.getLogger("io.github.graphmaquina")} while it is open, and
 * keeps it off the console: a test that makes an observer throw asserts the warning instead of printing it. It adds a
 * handler to the {@code java.util.logging} logger of that name and stops that logger from passing records to its
 * parents; {@link #close()} restores both.
 */
public final class LibraryLog implements AutoCloseable {

    private final Logger logger = Logger.getLogger("io.github.graphmaquina");
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            synchronized (records) {
                records.add(record);
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    };
    private final boolean useParentHandlers;

    private LibraryLog() {
        useParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
    }

    /** Starts capturing. Close it, with try-with-resources, to give the logger back. */
    public static LibraryLog capture() {
        return new LibraryLog();
    }

    /** What the library logged since {@link #capture()}, in order. */
    public List<LogRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /** Removes the handler and restores whether the logger passes records to its parents. */
    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(useParentHandlers);
    }
}
