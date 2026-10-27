package io.github.graphmaquina.processor;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The two reference scenarios of composing machines across modules, kept once as real sources: the producer publishes
 * the payments and the items, and knows neither bookings nor orders; the consumer binds a payment as the sub-machine
 * of a booking's {@code DEPOSIT}, watches the items of a paid order, and builds a set of the four machines. The build
 * names their directory in the system property {@value #PROPERTY}, whose {@code producer} and {@code consumer}
 * subdirectories are package roots, and the guide on composing machines across modules quotes them.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CrossModuleSources {

    /** The system property that names the directory of both sides. */
    static final String PROPERTY = "graphmaquina.crossModule";

    /** The producer's sources: the packages {@code pay} and {@code stock}. */
    static Map<String, String> producer() {
        return FixtureSources.read(root().resolve("producer"));
    }

    /** The consumer's sources: the packages {@code book}, {@code shop} and {@code app}. */
    static Map<String, String> consumer() {
        return FixtureSources.read(root().resolve("consumer"));
    }

    /** The directory that holds both sides. */
    static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty(PROPERTY), PROPERTY + " is not set"));
    }
}
