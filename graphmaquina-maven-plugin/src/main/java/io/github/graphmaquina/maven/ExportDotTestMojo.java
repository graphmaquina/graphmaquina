package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code export-dot-test} goal: the counterpart of {@code export-dot} for the test compilation, which forks the
 * default lifecycle up to {@code test-compile} and writes into {@code target/graphmaquina/test/dot}.
 */
public final class ExportDotTestMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.TEST)) {
            return;
        }
        export(CompilerExecution.TEST);
    }
}
