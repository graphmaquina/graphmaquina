package io.github.graphmaquina.maven;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
@Getter(AccessLevel.PACKAGE)
@RequiredArgsConstructor
enum CompilerExecution {

    /** The compilation of {@code src/main/java}, which {@code compiler:compile} runs. */
    MAIN("main", "compile", "compile", "prepare", "process-sources", "complete", "compile", "skipMain",
        "maven.main.skip", "generatedSourcesDirectory", "includes", "excludes"),

    /** The compilation of {@code src/test/java}, which {@code compiler:testCompile} runs. */
    TEST("test", "testCompile", "test-compile", "prepare-test", "process-test-sources", "complete-test",
        "test-compile", "skip", "maven.test.skip", "generatedTestSourcesDirectory", "testIncludes", "testExcludes");

    /**
     * The source set this execution compiles, as the identity of its compilation names it.
     *
     * @return {@code main} or {@code test}
     */
    private final String sourceSet;

    /**
     * The goal of {@code maven-compiler-plugin} that runs this compilation.
     *
     * @return {@code compile} or {@code testCompile}
     */
    private final String goal;

    /**
     * The phase maven-compiler-plugin 3.15.0 binds {@link #goal()} to by default, which is the phase of an execution
     * of that goal that declares none.
     *
     * @return {@code compile} or {@code test-compile}
     */
    private final String defaultPhase;

    /**
     * The goal of this plugin that prepares this compilation, which has to run before javac.
     *
     * @return {@code prepare} or {@code prepare-test}
     */
    private final String preparationGoal;

    /**
     * The phase the descriptor of this plugin binds {@link #preparationGoal()} to by default.
     *
     * @return {@code process-sources} or {@code process-test-sources}
     */
    private final String preparationPhase;

    /**
     * The goal of this plugin that completes this compilation, which has to run after javac.
     *
     * @return {@code complete} or {@code complete-test}
     */
    private final String completionGoal;

    /**
     * The phase the descriptor of this plugin binds {@link #completionGoal()} to by default.
     *
     * @return {@code compile} or {@code test-compile}
     */
    private final String completionPhase;

    /**
     * The configuration parameter of {@code maven-compiler-plugin} that switches this compilation off.
     *
     * @return the name of the skip parameter
     */
    private final String skipParameter;

    /**
     * The user property that sets {@link #skipParameter()} from the command line.
     *
     * @return the name of the skip property
     */
    private final String skipProperty;

    /**
     * The configuration parameter this execution writes the sources of the annotation processor with. The two
     * javac executions name that directory differently, and a check that read one of them for both would both
     * miss a redirected test directory and refuse a legitimate main one.
     *
     * @return the name of the generated-sources parameter
     */
    private final String generatedSourcesParameter;

    /**
     * The configuration parameter that lists the patterns of the sources this execution compiles.
     *
     * @return the name of the includes parameter
     */
    private final String includesParameter;

    /**
     * The configuration parameter that lists the patterns of the sources this execution leaves out. The test
     * execution also leaves out those its {@code testIncrementalExcludes} match, which maven-compiler-plugin joins with
     * these.
     *
     * @return the name of the excludes parameter
     */
    private final String excludesParameter;
}
