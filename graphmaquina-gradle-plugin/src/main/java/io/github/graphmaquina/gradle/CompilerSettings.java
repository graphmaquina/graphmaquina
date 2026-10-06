package io.github.graphmaquina.gradle;

import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.ForkOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaCompiler;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The release and the encoding javac takes for one compilation, derived from its options and from a list of its
 * compiler arguments the way Gradle 9.2.1 hands both to javac.
 *
 * <p>Gradle gives javac its own options ahead of the compiler arguments: {@code options.release} as a
 * {@code --release} and {@code options.encoding} as an {@code -encoding}. javac takes the last {@code --release} and
 * the last {@code -encoding} it is given. The generation derives both from {@code options.compilerArgs} alone, since
 * the argument providers of the compilation may need files that other tasks only write before the compilation runs;
 * the preparation, which runs just before javac, derives them from all the compiler arguments as well and refuses a
 * compilation whose providers change either.</p>
 */
final class CompilerSettings {

    /** A {@code --release} value as a plain decimal number, short enough to be an {@code int}. */
    private static final Pattern RELEASE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");

    /** The encoding the generator reads a request that names none in. */
    private static final String GENERATOR_DEFAULT_ENCODING = "UTF-8";

    private CompilerSettings() {
    }

    /**
     * The compiler arguments {@code options.compilerArgs} holds, each turned into a string the way Gradle turns it
     * before javac sees it: {@code toString()}, a {@code null} kept as it is. The arguments of the argument providers
     * are left out, so none of them runs.
     */
    static List<String> declaredArguments(CompileOptions options) {
        List<String> arguments = new ArrayList<>();
        for (Object argument : options.getCompilerArgs()) {
            arguments.add(argument == null ? null : argument.toString());
        }
        return arguments;
    }

    /**
     * The release javac compiles {@code compilation} at when it is given {@code arguments} as its compiler arguments:
     * the value of the last {@code --release} among them, spelled {@code --release N} or {@code --release=N}, else
     * {@code options.release}. A value that is not a plain decimal number, which javac refuses, is passed over.
     * Without either, Gradle passes javac {@code -source} and {@code -target} from the source and target
     * compatibility, which default to the language version of the compilation's toolchain, and no {@code --release},
     * so javac compiles against the whole API of the toolchain's JDK: the release is then that language version.
     */
    static Integer release(JavaCompile compilation, List<String> arguments) {
        Optional<Integer> argument = releaseArgument(arguments);
        if (argument.isPresent()) {
            return argument.get();
        }
        return compilation.getOptions().getRelease()
                .orElse(compilation.getJavaCompiler().map((JavaCompiler compiler) ->
                        compiler.getMetadata().getLanguageVersion().asInt()))
                .getOrNull();
    }

    /**
     * The release the last {@code --release} of {@code arguments} names, spelled {@code --release N} or
     * {@code --release=N}; empty when there is none, or when its value is not a plain decimal number.
     */
    static Optional<Integer> releaseArgument(List<String> arguments) {
        return lastValue(arguments, "--release", true)
                .filter((String value) -> RELEASE_NUMBER.matcher(value).matches())
                .map(Integer::valueOf);
    }

    /**
     * Whether the last {@code --release} of {@code arguments} carries a value that is not a plain decimal number, which
     * javac refuses, so the compilation fails in javac whatever release the rest of its settings give.
     */
    static boolean refusesLastRelease(List<String> arguments) {
        Optional<String> argument = lastValue(arguments, "--release", true);
        return argument.isPresent() && !RELEASE_NUMBER.matcher(argument.get()).matches();
    }

    /**
     * The encoding javac reads the sources of {@code compilation} in when it is given {@code arguments} as its compiler
     * arguments, or {@code null} when the compilation names none.
     *
     * <p>When Gradle starts javac from the command line, as it does when {@code options.fork} is set with
     * {@code forkOptions.javaHome} or {@code forkOptions.executable}, javac takes the last {@code -encoding}: the
     * encoding is the value of the last {@code -encoding} of {@code arguments}, else {@code options.encoding}. Gradle's
     * own Java compiler, in process or in a forked compiler daemon, reads the sources through a file manager it opens
     * in {@code options.encoding}, which then wins over every {@code -encoding}: the encoding is
     * {@code options.encoding}, else the value of the last {@code -encoding} of {@code arguments}.</p>
     */
    static String encoding(JavaCompile compilation, List<String> arguments) {
        CompileOptions options = compilation.getOptions();
        String declared = options.getEncoding();
        if (declared != null && !commandLine(options)) {
            return declared;
        }
        return lastValue(arguments, "-encoding", false).orElse(declared);
    }

    /**
     * Whether Gradle 9.2.1 starts javac from the command line for {@code options}, rather than running its own Java
     * compiler in process or in a forked compiler daemon: when {@code options.fork} is set with
     * {@code forkOptions.javaHome} or {@code forkOptions.executable}.
     */
    static boolean commandLine(CompileOptions options) {
        ForkOptions fork = options.getForkOptions();
        return options.isFork() && (fork.getJavaHome() != null || fork.getExecutable() != null);
    }

    /**
     * Whether the generator, reading a request that carries {@code generation}, and javac, reading
     * {@code compilation}, read in the same charset. A request without an encoding is read in UTF-8; two names of one
     * charset, such as an alias, are the same; a name this JVM supports no charset for is the same only as itself.
     */
    static boolean sameEncoding(String generation, String compilation) {
        if (compilation == null) {
            return generation == null;
        }
        String read = generation == null ? GENERATOR_DEFAULT_ENCODING : generation;
        return read.equals(compilation) || sameCharset(read, compilation);
    }

    /** Whether {@code first} and {@code second} both name charsets this JVM supports, and the same one. */
    static boolean sameCharset(String first, String second) {
        try {
            return Charset.forName(first).equals(Charset.forName(second));
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    /** Whether this JVM supports a charset of the name {@code name}. */
    static boolean supported(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException illegal) {
            return false;
        }
    }

    /**
     * The value javac takes for {@code option} from {@code arguments}, which is that of its last occurrence: the
     * argument that follows it, or, when {@code joined}, what follows an equals sign in the same argument; empty
     * when no occurrence carries a value.
     */
    static Optional<String> lastValue(List<String> arguments, String option, boolean joined) {
        String value = null;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (option.equals(argument) && index + 1 < arguments.size()) {
                index++;
                value = arguments.get(index);
            } else if (joined && argument != null && argument.startsWith(option + '=')) {
                value = argument.substring(option.length() + 1);
            }
        }
        return Optional.ofNullable(value);
    }
}
