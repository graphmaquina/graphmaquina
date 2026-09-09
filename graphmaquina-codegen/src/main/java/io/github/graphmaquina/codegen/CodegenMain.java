package io.github.graphmaquina.codegen;

import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.output.CompilationOutputs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The command line the Gradle and Maven plugins drive the generator with: one operation and one request file
 * per invocation, nothing else.
 *
 * <pre>{@code
 * io.github.graphmaquina.codegen.CodegenMain generate --request request.json
 * io.github.graphmaquina.codegen.CodegenMain prepare-compile --request request.json
 * io.github.graphmaquina.codegen.CodegenMain complete-compile --request request.json
 * }</pre>
 *
 * <p>{@code generate} emits the typed vocabulary and the manifest before javac; {@code prepare-compile} retires
 * the outputs of the previous cycle and invalidates the published index, also before javac;
 * {@code complete-compile} runs only after javac succeeded and publishes what it produced. A build never runs
 * the last one after a failed compilation.</p>
 *
 * <p>Nothing is caught and continued. An operation this tool cannot run leaves status {@code 1} and a message on
 * the error stream naming the operation, the request file and the cause, with its SG code when it has one;
 * arguments this tool does not accept leave status {@code 2}. A build that ignored either status would compile
 * against the vocabulary of a previous run, so the status is the whole contract.</p>
 */
public final class CodegenMain {

    private static final String GENERATE = "generate";
    private static final String PREPARE = "prepare-compile";
    private static final String COMPLETE = "complete-compile";
    private static final List<String> OPERATIONS = List.of(GENERATE, PREPARE, COMPLETE);
    private static final String REQUEST = "--request";
    private static final String USAGE =
        "usage: io.github.graphmaquina.codegen.CodegenMain <" + GENERATE + '|' + PREPARE + '|' + COMPLETE + "> "
            + REQUEST + " <file>";
    private static final int FAILED = 1;
    private static final int MISUSED = 2;

    private CodegenMain() {
    }

    /** Runs {@link #run(String[])} and leaves its status to the build that started this process. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Runs one operation over one request file and returns the status a build must act on: {@code 0} when the
     * operation completed, {@code 1} when it failed, {@code 2} when the arguments are not the ones this tool
     * accepts.
     */
    public static int run(String[] args) {
        if (args.length != 3) {
            return misused("expected one operation and " + REQUEST + " <file>, got "
                + args.length + " argument(s): " + String.join(" ", args));
        }
        String operation = args[0];
        if (!OPERATIONS.contains(operation)) {
            return misused("unknown operation '" + operation + "'; expected one of "
                + String.join(", ", OPERATIONS));
        }
        if (!REQUEST.equals(args[1])) {
            return misused(operation + ": expected " + REQUEST + " as the second argument, got '"
                + args[1] + "'");
        }
        String requestFile = args[2];
        try {
            GenerationRequest request = ManifestCodec.readRequest(Path.of(requestFile));
            switch (operation) {
                case GENERATE -> StateGraphCodegen.generate(request);
                case PREPARE -> CompilationOutputs.prepare(request);
                case COMPLETE -> CompilationOutputs.complete(request);
                default -> throw new IllegalStateException("unreachable operation: " + operation);
            }
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("graphmaquina: " + operation + " failed for " + requestFile + ": "
                + describe(failure));
            return FAILED;
        }
    }

    /**
     * The failure as a build should read it: the SG code when the generator assigned one, followed by every
     * cause underneath it, since the outermost message alone usually says what the tool was doing and not what
     * went wrong.
     */
    static String describe(Throwable failure) {
        StringBuilder description = new StringBuilder(headline(failure));
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seen.add(failure);
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            description.append(": ").append(headline(cause));
        }
        return description.toString();
    }

    /** One link of the chain: the SG code when the generator assigned one, the type otherwise. */
    private static String headline(Throwable failure) {
        if (failure instanceof CodegenException diagnosed) {
            return "[" + diagnosed.code() + "] " + diagnosed.getMessage();
        }
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    /** Reports arguments this tool does not accept and returns the status that says exactly that. */
    private static int misused(String problem) {
        System.err.println("graphmaquina: " + problem);
        System.err.println(USAGE);
        return MISUSED;
    }
}
