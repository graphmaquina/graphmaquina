package io.github.graphmaquina.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runs one operation of the StateGraph generator as a separate process and writes the request it reads.
 *
 * <p>The generator is never loaded into the plugin's own classloader: it runs under the launcher and over the
 * classpath the generation task of the source set holds — by convention the launcher of the JDK the source set's
 * javac compiles with and the {@code graphmaquinaGenerator} configuration — so the build's daemon JVM and the
 * generator's JVM stay independent. Its command line is fixed — {@code <operation> --request <file>} — and so is its
 * contract: status {@code 0} means the operation completed, anything else means the build must fail rather than
 * compile against the vocabulary of a previous run. Whatever the generator wrote on its error stream is never
 * dropped: it ends the message of the exception this class throws when the operation failed, and it is a warning
 * of the build log when the operation completed.</p>
 *
 * <p>The request itself is serialized here, by the small encoder below, rather than through the generator's own
 * codec: the plugin depends on the documented JSON shape, not on the {@code graphmaquina-codegen} classes. Strings
 * are written as UTF-8 text with only the escapes JSON requires — a quote, a backslash and every character below
 * {@code U+0020} — so a path carrying spaces, quotes, backslashes or non-ASCII characters round-trips exactly.</p>
 */
public final class CodegenProcess {

    /** The generator's entry point, as its command-line contract names it. */
    private static final String MAIN_CLASS = "io.github.graphmaquina.codegen.CodegenMain";

    /** The lowest Java feature release the generator and the compilation it prepares are supported on. */
    private static final int MINIMUM_LANGUAGE_VERSION = 21;

    /** Where what a completed operation wrote on its error stream is reported. */
    private static final Logger LOGGER = Logging.getLogger(CodegenProcess.class);

    private final File executable;
    private final List<File> generatorClasspath;
    private final ExecOperations execOperations;

    /**
     * @param executable the {@code java} executable to run the generator with
     * @param generatorClasspath the resolved runtime classpath of {@code graphmaquina-codegen}
     * @param execOperations the service that starts the process
     */
    public CodegenProcess(File executable, Iterable<File> generatorClasspath, ExecOperations execOperations) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.generatorClasspath = List.copyOf(toList(Objects.requireNonNull(generatorClasspath,
            "generatorClasspath")));
        this.execOperations = Objects.requireNonNull(execOperations, "execOperations");
    }

    /**
     * The process for {@code launcher}, refusing a toolchain that cannot run this generator at all.
     *
     * @param launcher the launcher to run the generator under
     * @param generatorClasspath the resolved runtime classpath of {@code graphmaquina-codegen}
     * @param execOperations the service that starts the process
     * @return a process bound to that launcher's executable
     * @throws GradleException if the launcher is below Java 21, or if its installation has no {@code javac} in
     *         {@code bin}, which marks a runtime without {@code jdk.compiler}
     */
    public static CodegenProcess of(JavaLauncher launcher, Iterable<File> generatorClasspath,
            ExecOperations execOperations) {
        Objects.requireNonNull(launcher, "launcher");
        JavaInstallationMetadata metadata = launcher.getMetadata();
        int languageVersion = metadata.getLanguageVersion().asInt();
        if (languageVersion < MINIMUM_LANGUAGE_VERSION) {
            throw new GradleException("the StateGraph generator needs a Java " + MINIMUM_LANGUAGE_VERSION
                + " toolchain or later, but the one it runs under, the JDK javac compiles with unless the"
                + " generation task's javaLauncher names another, is Java " + languageVersion
                + " at " + metadata.getInstallationPath().getAsFile() + ". Raise java.toolchain.languageVersion,"
                + " the toolchain of the compile task or that javaLauncher, whichever selects it, to "
                + MINIMUM_LANGUAGE_VERSION + " or later.");
        }
        if (!compilerOf(metadata).isFile()) {
            throw new GradleException("the StateGraph generator needs the jdk.compiler module, but the"
                + " installation it runs under, at " + metadata.getInstallationPath().getAsFile()
                + ", carries no " + compilerOf(metadata).getName() + ", so it is a runtime, not a JDK."
                + " Point the generation task's javaLauncher, or whatever selects that installation, at a full JDK "
                + MINIMUM_LANGUAGE_VERSION + " or later.");
        }
        return new CodegenProcess(launcher.getExecutablePath().getAsFile(), generatorClasspath, execOperations);
    }

    /**
     * Runs {@code operation} over {@code requestFile}. What a completed operation wrote on its error stream is
     * logged as a warning.
     *
     * @param operation one of {@code generate}, {@code prepare-compile} and {@code complete-compile}
     * @param requestFile the request the generator reads
     * @throws GradleException if the generator left a nonzero status, carrying what it reported
     */
    public void run(String operation, Path requestFile) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestFile, "requestFile");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        ExecResult result = execOperations.javaexec(spec -> {
            spec.setExecutable(executable);
            spec.getMainClass().set(MAIN_CLASS);
            spec.classpath(generatorClasspath);
            spec.args(operation, "--request", requestFile.toString());
            spec.setErrorOutput(diagnostics);
            spec.setIgnoreExitValue(true);
        });
        String reported = diagnostics.toString(StandardCharsets.UTF_8).strip();
        if (result.getExitValue() != 0) {
            throw new GradleException("the StateGraph generator failed with status " + result.getExitValue()
                + " running '" + operation + "' for " + requestFile
                + (reported.isEmpty() ? "" : System.lineSeparator() + reported));
        }
        if (!reported.isEmpty()) {
            // The operation completed, so this is not a failure; it is still the only place the generator has to
            // report something a build should look at, and a silent error stream loses it.
            LOGGER.warn("the StateGraph generator reported while running '" + operation + "': " + reported);
        }
    }

    /**
     * Writes {@code request} to {@code file} as the flat JSON object the generator reads, creating the
     * directory it belongs to.
     *
     * @param file the request file to write
     * @param request the values of the request
     * @throws UncheckedIOException if the file cannot be written
     */
    public static void write(Path file, Request request) {
        Objects.requireNonNull(file, "file");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, encode(request), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot write the StateGraph request " + file, failure);
        }
    }

    /**
     * Encodes {@code request} as the flat JSON object documented by the generator's manifest codec: the eight
     * members every request carries, then the release and the encoding of the compilation when they are known, in
     * the order the codec lists them.
     *
     * @param request the values of the request
     * @return the JSON document, ending with a newline
     */
    public static String encode(Request request) {
        Objects.requireNonNull(request, "request");
        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        member(json, "projectDirectory", request.projectDirectory()).append(",\n");
        array(json, "sources", request.sources()).append(",\n");
        array(json, "classpath", request.classpath()).append(",\n");
        member(json, "sourcesOutput", request.sourcesOutput()).append(",\n");
        member(json, "processorSourcesOutput", request.processorSourcesOutput()).append(",\n");
        member(json, "classesOutput", request.classesOutput()).append(",\n");
        member(json, "workDirectory", request.workDirectory()).append(",\n");
        member(json, "compilationId", request.compilationId());
        if (request.release() != null) {
            json.append(",\n  ");
            string(json, "release").append(": ").append(request.release().intValue());
        }
        if (request.encoding() != null) {
            json.append(",\n");
            member(json, "encoding", request.encoding());
        }
        json.append("\n}\n");
        return json.toString();
    }

    private static StringBuilder member(StringBuilder json, String name, String value) {
        json.append("  ");
        string(json, name).append(": ");
        return string(json, value);
    }

    private static StringBuilder array(StringBuilder json, String name, List<String> values) {
        json.append("  ");
        string(json, name).append(": [");
        for (int index = 0; index < values.size(); index++) {
            json.append(index == 0 ? "\n    " : ",\n    ");
            string(json, values.get(index));
        }
        return json.append(values.isEmpty() ? "]" : "\n  ]");
    }

    /**
     * Appends {@code value} as a JSON string. Only what JSON forbids unescaped is escaped: the quote, the
     * backslash and every character below {@code U+0020}; everything else, non-ASCII included, is written as
     * itself and carried by the UTF-8 encoding of the file.
     */
    private static StringBuilder string(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"');
    }

    /** The compiler executable of {@code metadata}'s installation, whose absence means a runtime, not a JDK. */
    private static File compilerOf(JavaInstallationMetadata metadata) {
        return new File(new File(metadata.getInstallationPath().getAsFile(), "bin"), executableName("javac"));
    }

    /** The file name of the executable {@code tool} of a JDK on this platform: {@code tool.exe} on Windows. */
    static String executableName(String tool) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            ? tool + ".exe"
            : tool;
    }

    private static List<File> toList(Iterable<File> files) {
        List<File> entries = new ArrayList<>();
        for (File file : files) {
            entries.add(file);
        }
        return entries;
    }

    /**
     * The values of one generation request, as the generator's flat JSON object carries them. Every path is an
     * absolute execution path, written exactly as the build resolved it.
     *
     * @param projectDirectory the directory the compilation belongs to
     * @param sources the original sources, as source roots or, for a root the build filters, as the files
     *         it selects; none of them overlaps an output directory
     * @param classpath the compile classpath the generator resolves headers and published indexes against
     * @param sourcesOutput where the generated vocabulary is written
     * @param processorSourcesOutput where the annotation processor writes its own sources
     * @param classesOutput where javac writes the classes of this compilation
     * @param workDirectory where the manifests and the inventories of this compilation live
     * @param compilationId the identity of the project and source set, by default {@code group:name:sourceSet},
     *         or {@code name:sourceSet} when the project's group is empty
     * @param release the Java release javac compiles the source set at, or {@code null} when it is not known,
     *         which the generator reads as release 21
     * @param encoding the name of the charset javac reads the sources in, or {@code null} when the compilation
     *         names none, which the generator reads as UTF-8
     */
    public record Request(String projectDirectory, List<String> sources, List<String> classpath,
            String sourcesOutput, String processorSourcesOutput, String classesOutput, String workDirectory,
            String compilationId, Integer release, String encoding) {

        /** Copies the two lists, so a request never reflects a later change of the collection it was built from. */
        public Request {
            Objects.requireNonNull(projectDirectory, "projectDirectory");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
            Objects.requireNonNull(sourcesOutput, "sourcesOutput");
            Objects.requireNonNull(processorSourcesOutput, "processorSourcesOutput");
            Objects.requireNonNull(classesOutput, "classesOutput");
            Objects.requireNonNull(workDirectory, "workDirectory");
            Objects.requireNonNull(compilationId, "compilationId");
        }

        /**
         * A request that names neither the release nor the encoding of the compilation.
         *
         * @param projectDirectory the directory the compilation belongs to
         * @param sources the original sources
         * @param classpath the compile classpath
         * @param sourcesOutput where the generated vocabulary is written
         * @param processorSourcesOutput where the annotation processor writes its own sources
         * @param classesOutput where javac writes the classes of this compilation
         * @param workDirectory where the manifests and the inventories of this compilation live
         * @param compilationId the identity of the project and source set
         */
        public Request(String projectDirectory, List<String> sources, List<String> classpath,
                String sourcesOutput, String processorSourcesOutput, String classesOutput, String workDirectory,
                String compilationId) {
            this(projectDirectory, sources, classpath, sourcesOutput, processorSourcesOutput, classesOutput,
                    workDirectory, compilationId, null, null);
        }
    }
}
