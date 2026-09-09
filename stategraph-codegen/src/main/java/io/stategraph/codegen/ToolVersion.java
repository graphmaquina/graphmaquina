package io.stategraph.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The version of this generator, as the build stamped it into {@code io/stategraph/codegen/version.properties}.
 *
 * <p>Every manifest this tool writes carries it, and every index it reads back is refused when it was written by
 * another version, so a stale artifact is diagnosed instead of being interpreted with the wrong rules.</p>
 */
public final class ToolVersion {

    private static final String RESOURCE = "version.properties";

    private ToolVersion() {
    }

    /**
     * The version of the running generator.
     *
     * @throws IllegalStateException if the resource is missing or carries no {@code version}, which means the
     *     tool was packaged without its own build stamp
     */
    public static String current() {
        Properties properties = new Properties();
        try (InputStream resource = ToolVersion.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("the StateGraph generator resource io/stategraph/codegen/"
                    + RESOURCE + " is missing; this tool jar is incomplete");
            }
            properties.load(resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read io/stategraph/codegen/" + RESOURCE, failure);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("the StateGraph generator resource io/stategraph/codegen/" + RESOURCE
                + " declares no version; this tool jar is incomplete");
        }
        return version;
    }
}
