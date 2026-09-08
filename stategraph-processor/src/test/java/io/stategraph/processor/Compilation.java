package io.stategraph.processor;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Compiles sources in memory with the processor under -Xlint:all -Werror, exactly as a consumer would. */
final class Compilation {

    /** What a compilation produced: diagnostics, and the directory holding classes and generated sources. */
    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path output) {

        List<String> errors() {
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(diagnostic.getMessage(null));
                }
            }
            return errors;
        }

        boolean hasError(String code) {
            return countError(code) > 0;
        }

        long countError(String code) {
            long count = 0;
            for (String error : errors()) {
                if (error.contains("[" + code + "]")) {
                    count++;
                }
            }
            return count;
        }

        /** Line of the first error carrying the code, or -1. */
        long lineOf(String code) {
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null).contains("[" + code + "]")) {
                    return diagnostic.getLineNumber();
                }
            }
            return -1;
        }

        String describe() {
            StringBuilder text = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                text.append(diagnostic.getKind()).append(' ').append(diagnostic.getLineNumber()).append(": ")
                        .append(diagnostic.getMessage(null)).append('\n');
            }
            return text.toString();
        }

        boolean hasGenerated(String qualifiedName) {
            return Files.exists(output.resolve(qualifiedName.replace('.', '/') + ".java"));
        }

        String generated(String qualifiedName) {
            try {
                return Files.readString(output.resolve(qualifiedName.replace('.', '/') + ".java"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("no generated source for " + qualifiedName + "; diagnostics:\n" + describe(), e);
            }
        }

        Class<?> load(String qualifiedName) {
            try {
                URL[] urls = {output.toUri().toURL()};
                return new URLClassLoader(urls, Compilation.class.getClassLoader()).loadClass(qualifiedName);
            } catch (MalformedURLException | ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String qualifiedName, String code) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private Compilation() { }

    /** {@code sources} maps qualified names to source text. */
    static Result compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try {
            Path output = Files.createTempDirectory("stategraph-processor-");
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                units.add(new Source(entry.getKey(), entry.getValue()));
            }
            List<String> options = List.of(
                    "-processor", StateGraphProcessor.class.getName(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString(),
                    "-s", output.toString(),
                    "-Xlint:all", "-Werror");
            try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(output));
                boolean success = compiler.getTask(null, files, collector, options, null, units).call();
                return new Result(success, collector.getDiagnostics(), output);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
