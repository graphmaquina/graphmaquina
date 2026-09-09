package io.stategraph.maven;

/**
 * The two javac executions Maven's default lifecycle runs, and the names each of them is configured and
 * skipped by.
 *
 * <p>A StateGraph goal always belongs to exactly one of them: the {@code main} goals surround
 * {@code default-compile} and the test goals surround {@code default-testCompile}. Reading the configuration of
 * the right execution is what lets a goal say that the compiler is going to read the manifest the generator is
 * about to write, rather than assume it.</p>
 */
enum CompilerExecution {

    /** The compilation of {@code src/main/java}, which {@code compiler:compile} runs. */
    MAIN("default-compile", "skipMain", "maven.main.skip"),

    /** The compilation of {@code src/test/java}, which {@code compiler:testCompile} runs. */
    TEST("default-testCompile", "skip", "maven.test.skip");

    private final String id;
    private final String skipParameter;
    private final String skipProperty;

    CompilerExecution(String id, String skipParameter, String skipProperty) {
        this.id = id;
        this.skipParameter = skipParameter;
        this.skipProperty = skipProperty;
    }

    /**
     * The identity Maven gives this execution in the project model.
     *
     * @return the execution id
     */
    String id() {
        return id;
    }

    /**
     * The configuration parameter of {@code maven-compiler-plugin} that switches this compilation off.
     *
     * @return the name of the skip parameter
     */
    String skipParameter() {
        return skipParameter;
    }

    /**
     * The user property that sets {@link #skipParameter()} from the command line.
     *
     * @return the name of the skip property
     */
    String skipProperty() {
        return skipProperty;
    }
}
