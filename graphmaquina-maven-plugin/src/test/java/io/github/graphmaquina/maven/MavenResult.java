package io.github.graphmaquina.maven;

/**
 * What one fixture Maven build left behind: the status the process exited with and everything it printed on
 * both of its streams, merged in the order Maven produced it. This is test-support code, not a production API.
 *
 * @param exitCode the status of the {@code mvn} process; {@code 0} when the build succeeded
 * @param output the merged standard output and error of the build
 */
public record MavenResult(int exitCode, String output) {
}
