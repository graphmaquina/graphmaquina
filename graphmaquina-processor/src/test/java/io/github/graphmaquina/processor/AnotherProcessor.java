package io.github.graphmaquina.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Stands for another processor of the compilation that generates sources in its first round, which every
 * processor then sees as new types in the second round. It is public and top-level because javac instantiates a
 * processor reflectively from the name given to {@code -processor}; a test hands it its sources first.
 */
public final class AnotherProcessor extends AbstractProcessor {

    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    private boolean written;

    /** The sources, by qualified name, that the next compilation generates in its first round. */
    static void generates(Map<String, String> sources) {
        SOURCES.clear();
        SOURCES.putAll(sources);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (written) {
            return false;
        }
        written = true;
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            try (Writer writer = processingEnv.getFiler().createSourceFile(source.getKey()).openWriter()) {
                writer.write(source.getValue());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return false;
    }
}
