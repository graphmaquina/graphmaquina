package io.github.graphmaquina.codegen.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Reads the consumer's original sources with {@link JavacTask#parse()} only. The declarations, modifiers,
 * types and annotations of a unit are read syntactically; a body is visited only for the local and anonymous classes
 * it declares, the annotations written in them and the member types and supertypes that decide what those annotations
 * name, and no expression is evaluated, so a source may name a type this generator has not produced yet. javac still
 * parses every body,
 * so the sources are parsed at the release and in the encoding the compilation that follows reads them with:
 * a source may use the syntax of that release anywhere, and characters of that encoding.
 */
public final class SourceDeclarations {

    private static final String UNRESOLVED = "SG601";

    private SourceDeclarations() {
    }

    /**
     * Parses {@code files} at release {@value GenerationRequest#DEFAULT_RELEASE}, read in UTF-8, and anchors each
     * unit at its path relative to {@code project}, or at its absolute path for a file on another root. The file
     * manager is closed once the units are built; the parsed trees keep the syntax the declaration index still needs.
     */
    public static List<SourceUnit> parse(Path project, List<Path> files) {
        return List.copyOf(new LinkedHashSet<>(parseEach(project, files, GenerationRequest.DEFAULT_RELEASE,
                GenerationRequest.DEFAULT_ENCODING).values()));
    }

    /**
     * Parses {@code files} as the compilation reads them, at {@code release} and in {@code encoding}, and returns
     * the unit each of them was read into, in the order given. javac compares files by their real paths, so it reads
     * a file once however many paths lead to it through symbolic links, and those paths map to the unit of the
     * first; the hard links of one file have different real paths, so each is read into a unit of its own.
     */
    static Map<Path, SourceUnit> parseEach(Path project, List<Path> files, int release, Charset encoding) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(encoding, "encoding");
        if (files.isEmpty()) {
            return Map.of();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<Path, SourceUnit> units = new LinkedHashMap<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, encoding)) {
            List<JavaFileObject> sources = new ArrayList<>();
            for (JavaFileObject source : fileManager.getJavaFileObjectsFromPaths(files)) {
                sources.add(source);
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", Integer.toString(release)), null, sources);
            try {
                // javac parsed each distinct file object once, and two are equal exactly when their real paths are:
                // the paths that lead to one file through symbolic links share its tree; each hard link has its own.
                Map<JavaFileObject, SourceUnit> parsed = new HashMap<>();
                for (CompilationUnitTree tree : task.parse()) {
                    parsed.put(tree.getSourceFile(), new SourceUnit(tree, fileOrigin(project, tree)));
                }
                for (int index = 0; index < files.size(); index++) {
                    units.putIfAbsent(files.get(index), parsed.get(sources.get(index)));
                }
            } finally {
                // javac's own error is the one the user can act on, so it replaces whatever parsing threw.
                requireNoErrors("the original sources cannot be read", diagnostics, Optional.of(project));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the original sources under " + project, failure);
        }
        return Collections.unmodifiableMap(units);
    }

    /** The package {@code unit} declares, as its declaration spells it; empty for the unnamed package. */
    static String packageName(SourceUnit unit) {
        ExpressionTree packageName = unit.tree().getPackageName();
        return packageName == null ? "" : TreeTypeUses.spell(packageName);
    }

    /** The qualified names of the top-level types {@code unit} declares, in declaration order. */
    static List<String> topLevelTypes(SourceUnit unit) {
        String packageName = packageName(unit);
        List<String> types = new ArrayList<>();
        for (Tree declaration : unit.tree().getTypeDecls()) {
            if (declaration instanceof ClassTree type) {
                types.add(packageName.isEmpty()
                        ? type.getSimpleName().toString()
                        : packageName + '.' + type.getSimpleName());
            }
        }
        return List.copyOf(types);
    }

    /**
     * Fails when javac reported an error for one run, naming the javac message and, when the error is in a file and
     * {@code project} is given, that file and the position. A tooling failure stops the task: a truncated tree is
     * never read as if it were a valid declaration. Only the first error is described, followed by the number of
     * further errors of the same run.
     *
     * @param project the directory diagnostics are anchored against, empty when the run has no original
     *     sources of its own
     */
    static void requireNoErrors(String what, DiagnosticCollector<JavaFileObject> diagnostics,
            Optional<Path> project) {
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
        if (errors.isEmpty()) {
            return;
        }
        Diagnostic<? extends JavaFileObject> first = errors.get(0);
        Optional<SourceLocation> location = location(first, project);
        StringBuilder message = new StringBuilder(what).append(": ")
                .append(location.map(anchor -> anchor.path() + ':' + anchor.line() + ": ").orElse(""))
                .append(first.getMessage(Locale.ROOT));
        if (errors.size() > 1) {
            message.append(" (and ").append(errors.size() - 1).append(" further javac error")
                    .append(errors.size() == 2 ? "" : "s").append(')');
        }
        String reported = message.toString();
        throw location.map(anchor -> new CodegenException(UNRESOLVED, reported, anchor))
                .orElseGet(() -> new CodegenException(UNRESOLVED, reported));
    }

    private static Optional<SourceLocation> location(Diagnostic<? extends JavaFileObject> diagnostic,
            Optional<Path> project) {
        JavaFileObject source = diagnostic.getSource();
        if (source == null || project.isEmpty() || !"file".equals(source.toUri().getScheme())) {
            return Optional.empty();
        }
        return Optional.of(new SourceLocation(relative(project.get(), Path.of(source.toUri())),
                Math.max(diagnostic.getLineNumber(), 1L), Math.max(diagnostic.getColumnNumber(), 1L)));
    }

    /** Reads every type declaration of {@code unit}, as {@link SourceHeaders#declarations} reads them. */
    static List<TypeHeader> declarations(SourceUnit unit, SourcePositions positions) {
        return SourceHeaders.declarations(unit, positions);
    }

    private static SourceLocation fileOrigin(Path project, CompilationUnitTree tree) {
        return new SourceLocation(relative(project, Path.of(tree.getSourceFile().toUri())), 1L, 1L);
    }

    /**
     * The path of {@code file} relative to {@code project}, written with forward slashes on every platform so the
     * export index that publishes it does not depend on the machine that built it. A file the project directory
     * cannot be relativized against, such as one on another drive, keeps its absolute path.
     */
    static String relative(Path project, Path file) {
        Path target = file.toAbsolutePath().normalize();
        Path shown;
        try {
            shown = project.toAbsolutePath().normalize().relativize(target);
        } catch (IllegalArgumentException differentRoot) {
            shown = target;
        }
        return portable(shown.toString(), shown.getFileSystem().getSeparator());
    }

    /** {@code path} with every {@code separator} of the file system it was read from replaced by a forward slash. */
    static String portable(String path, String separator) {
        return path.replace(separator, "/");
    }
}
