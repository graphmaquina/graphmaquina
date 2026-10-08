package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * The processor in a processing environment that is not plain javac's, as an IDE or a tool that wraps the
 * environment provides: {@code Trees} refuses such an environment, so the processor starts all the same and reports
 * every diagnostic through Messager with its element, annotation and value anchors.
 */
class DiagnosticsFallbackTest {

    private static final String FORBIDDEN_RULE = """
            @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                action = TicketActionsRefs.Publish.class,
                recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                    transition = SCHEDULE_RETRY, failsOn = RuntimeException.class))
            Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
            """;

    /** The wrapped environment starts the processor, which still reports SG302, now through Messager alone. */
    @Test
    void aWrappedEnvironmentStartsTheProcessorAndReportsThroughMessager() {
        Compilation.Prepared prepared = Compilation.prepare(RecoveryFixtures.sources(FORBIDDEN_RULE));
        Compilation.Result wrapped = Compilation.compilePrepared(prepared, List.of("-processor",
                Wrapping.class.getName()));
        Compilation.Result plain = Compilation.compileTyped(RecoveryFixtures.sources(FORBIDDEN_RULE));

        assertFalse(wrapped.success(), wrapped.describe());
        assertEquals(plain.errors(), wrapped.errors(), wrapped.describe());
        assertEquals(1, wrapped.countError("SG302"), wrapped.describe());
        Diagnostic<? extends JavaFileObject> diagnostic = wrapped.diagnostics().stream()
                .filter((Diagnostic<? extends JavaFileObject> candidate) -> candidate.getKind() == Diagnostic.Kind.ERROR
                        && candidate.getMessage(null).startsWith("[SG302]"))
                .findFirst().orElseThrow();
        assertTrue(diagnostic.getLineNumber() > 0, wrapped.describe());
        Diagnostic<? extends JavaFileObject> exact = plain.diagnostics().stream()
                .filter((Diagnostic<? extends JavaFileObject> candidate) -> candidate.getKind() == Diagnostic.Kind.ERROR
                        && candidate.getMessage(null).startsWith("[SG302]"))
                .findFirst().orElseThrow();
        String source = RecoveryFixtures.sources(FORBIDDEN_RULE).get("fx.Tickets");
        assertEquals("RuntimeException.class", source.substring((int) exact.getStartPosition(),
                (int) exact.getEndPosition()), plain.describe());
    }

    /**
     * Delegates to the real processor with a proxy in place of the processing environment, which is not javac's
     * own class. It is public and static because javac instantiates a processor reflectively from its name.
     */
    public static final class Wrapping extends AbstractProcessor {

        private final StateGraphProcessor delegate = new StateGraphProcessor();

        @Override
        public synchronized void init(ProcessingEnvironment environment) {
            super.init(environment);
            ProcessingEnvironment wrapped = (ProcessingEnvironment) Proxy.newProxyInstance(
                    ProcessingEnvironment.class.getClassLoader(), new Class<?>[] {ProcessingEnvironment.class},
                    (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> {
                        try {
                            return method.invoke(environment, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            delegate.init(wrapped);
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            return delegate.process(annotations, round);
        }
    }
}
