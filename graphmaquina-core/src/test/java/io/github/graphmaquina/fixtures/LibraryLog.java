package io.github.graphmaquina.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what the library logs through {@code System.getLogger} while it is open, and keeps it off the console.
 * {@link #capture()} captures {@code io.github.graphmaquina}, where the warning of an observer that throws goes, so a
 * test that makes an observer throw asserts the warning instead of printing it; it leaves that logger's level as it
 * is. {@link #runtime(Level)} captures {@code io.github.graphmaquina.runtime}, the log of each call, and sets that
 * logger to the {@code java.util.logging} level the test names: {@code FINE} to see DEBUG, {@code FINER} to see TRACE
 * too. Either adds a handler to the {@code java.util.logging} logger of that name and stops that logger from passing
 * records to its parents; {@link #close()} removes the handler and restores whether the logger passes records to its
 * parents, and the level the logger had when the capture opened.
 */
public final class LibraryLog implements AutoCloseable {

    private final Logger logger;
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
    /** The logger's own level when the capture opened; empty when it inherited its parent's. */
    private final Optional<Level> level;

    private LibraryLog(String name, Optional<Level> capturedAt) {
        logger = Logger.getLogger(name);
        useParentHandlers = logger.getUseParentHandlers();
        level = Optional.ofNullable(logger.getLevel());
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        if (capturedAt.isPresent()) {
            logger.setLevel(capturedAt.get());
        }
    }

    /** Starts capturing {@code io.github.graphmaquina}. Close it, with try-with-resources, to give the logger back. */
    public static LibraryLog capture() {
        return new LibraryLog("io.github.graphmaquina", Optional.empty());
    }

    /**
     * Starts capturing {@code io.github.graphmaquina.runtime} at {@code level}. Close it, with try-with-resources, to
     * give the logger back.
     */
    public static LibraryLog runtime(Level level) {
        return new LibraryLog("io.github.graphmaquina.runtime", Optional.of(level));
    }

    /** What the library logged since the capture opened, in order. */
    public List<LogRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /**
     * Removes the handler, and restores whether the logger passes records to its parents and the level it had when
     * the capture opened.
     */
    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(useParentHandlers);
        logger.setLevel(level.orElse(null));
    }
}
