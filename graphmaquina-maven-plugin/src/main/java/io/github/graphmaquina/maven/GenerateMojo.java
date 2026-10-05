package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code generate} goal: emits the typed StateGraph vocabulary of the main compilation and the manifest the
 * annotation processor reads, before javac runs.
 *
 * <p>The directory it generated into becomes a compile source root of the project, so the compiler of this
 * build compiles what was generated without the POM naming that directory itself, and every goal that reads the
 * compile source roots of the project later in this build reads that directory too.</p>
 *
 * <p>Before it writes anything, the goal checks that {@code default-compile} is configured to read the very
 * manifest this generation writes, under the same JDK and a release that vocabulary compiles with. A build that
 * disagrees is stopped here rather than compiled against the vocabulary of a previous run.</p>
 *
 * <p>When the build compiles no main sources — in a project packaged as {@code pom}, or when
 * {@code maven.main.skip} or {@code <skipMain>} switches the main compilation off — the goal generates nothing
 * and says so.</p>
 */
public final class GenerateMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.MAIN)) {
            return;
        }
        Request request = mainRequest();
        verifyCompiler(CompilerExecution.MAIN, request);
        run("generate", request);
        project().addCompileSourceRoot(request.sourcesOutput());
    }
}
