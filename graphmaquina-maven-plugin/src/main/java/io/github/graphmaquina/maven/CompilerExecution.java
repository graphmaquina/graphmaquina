package io.github.graphmaquina.maven;

/**
 * The two compilations of a project, the goal of {@code maven-compiler-plugin} that runs each of them, and the names
 * each of them is configured and skipped by.
 *
 * <p>A StateGraph goal always belongs to exactly one of them: the {@code main} goals surround the execution of
 * {@code compiler:compile} that compiles the generated vocabulary, which the lifecycle of a {@code jar} names
 * {@code default-compile}, and the test goals surround that of {@code compiler:testCompile}, by default
 * {@code default-testCompile}. Reading the configuration of the execution that runs is what lets a goal say that the
 * compiler is going to read the manifest the generator is about to write, rather than assume it.</p>
 */
enum CompilerExecution {

    /** The compilation of {@code src/main/java}, which {@code compiler:compile} runs. */
    MAIN("main", "compile", "compile", "prepare", "process-sources", "complete", "compile", "skipMain",
        "maven.main.skip", "generatedSourcesDirectory", "includes", "excludes"),

    /** The compilation of {@code src/test/java}, which {@code compiler:testCompile} runs. */
    TEST("test", "testCompile", "test-compile", "prepare-test", "process-test-sources", "complete-test",
        "test-compile", "skip", "maven.test.skip", "generatedTestSourcesDirectory", "testIncludes", "testExcludes");

    private final String sourceSet;
    private final String goal;
    private final String defaultPhase;
    private final String preparationGoal;
    private final String preparationPhase;
    private final String completionGoal;
    private final String completionPhase;
    private final String skipParameter;
    private final String skipProperty;
    private final String generatedSourcesParameter;
    private final String includesParameter;
    private final String excludesParameter;

    CompilerExecution(String sourceSet, String goal, String defaultPhase, String preparationGoal,
            String preparationPhase, String completionGoal, String completionPhase, String skipParameter,
            String skipProperty, String generatedSourcesParameter, String includesParameter, String excludesParameter) {
        this.sourceSet = sourceSet;
        this.goal = goal;
        this.defaultPhase = defaultPhase;
        this.preparationGoal = preparationGoal;
        this.preparationPhase = preparationPhase;
        this.completionGoal = completionGoal;
        this.completionPhase = completionPhase;
        this.skipParameter = skipParameter;
        this.skipProperty = skipProperty;
        this.generatedSourcesParameter = generatedSourcesParameter;
        this.includesParameter = includesParameter;
        this.excludesParameter = excludesParameter;
    }

    /**
     * The source set this execution compiles, as the identity of its compilation names it.
     *
     * @return {@code main} or {@code test}
     */
    String sourceSet() {
        return sourceSet;
    }

    /**
     * The goal of {@code maven-compiler-plugin} that runs this compilation.
     *
     * @return {@code compile} or {@code testCompile}
     */
    String goal() {
        return goal;
    }

    /**
     * The phase maven-compiler-plugin 3.15.0 binds {@link #goal()} to by default, which is the phase of an execution
     * of that goal that declares none.
     *
     * @return {@code compile} or {@code test-compile}
     */
    String defaultPhase() {
        return defaultPhase;
    }

    /**
     * The goal of this plugin that prepares this compilation, which has to run before javac.
     *
     * @return {@code prepare} or {@code prepare-test}
     */
    String preparationGoal() {
        return preparationGoal;
    }

    /**
     * The phase the descriptor of this plugin binds {@link #preparationGoal()} to by default.
     *
     * @return {@code process-sources} or {@code process-test-sources}
     */
    String preparationPhase() {
        return preparationPhase;
    }

    /**
     * The goal of this plugin that completes this compilation, which has to run after javac.
     *
     * @return {@code complete} or {@code complete-test}
     */
    String completionGoal() {
        return completionGoal;
    }

    /**
     * The phase the descriptor of this plugin binds {@link #completionGoal()} to by default.
     *
     * @return {@code compile} or {@code test-compile}
     */
    String completionPhase() {
        return completionPhase;
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

    /**
     * The configuration parameter this execution writes the sources of the annotation processor with. The two
     * javac executions name that directory differently, and a check that read one of them for both would both
     * miss a redirected test directory and refuse a legitimate main one.
     *
     * @return the name of the generated-sources parameter
     */
    String generatedSourcesParameter() {
        return generatedSourcesParameter;
    }

    /**
     * The configuration parameter that lists the patterns of the sources this execution compiles.
     *
     * @return the name of the includes parameter
     */
    String includesParameter() {
        return includesParameter;
    }

    /**
     * The configuration parameter that lists the patterns of the sources this execution leaves out. The test
     * execution also leaves out those its {@code testIncrementalExcludes} match, which maven-compiler-plugin joins with
     * these.
     *
     * @return the name of the excludes parameter
     */
    String excludesParameter() {
        return excludesParameter;
    }
}
