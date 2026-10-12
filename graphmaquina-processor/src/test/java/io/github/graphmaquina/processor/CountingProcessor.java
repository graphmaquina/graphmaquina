package io.github.graphmaquina.processor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Runs the real {@link StateGraphProcessor} unchanged behind proxies that count what scales with the size of a
 * compilation: how many times each round is scanned for annotated elements, and how many times the members of each
 * type are listed. It is public because javac instantiates a processor reflectively from the name given to
 * {@code -processor}, and javac loads it through a loader that delegates to the test's, so the counts are the test's
 * own.
 */
public final class CountingProcessor extends AbstractProcessor {

    /** The calls of {@code getElementsAnnotatedWith} and {@code getElementsAnnotatedWithAny}, one entry per round. */
    static final List<Integer> SCANS_PER_ROUND = new ArrayList<>();

    /** The calls of {@code Elements.getAllMembers}, by the qualified name of the type whose members were listed. */
    static final Map<String, Integer> MEMBER_LISTINGS = new LinkedHashMap<>();

    private final StateGraphProcessor delegate = new StateGraphProcessor();

    /** Forgets the counts of a previous compilation. */
    static void reset() {
        SCANS_PER_ROUND.clear();
        MEMBER_LISTINGS.clear();
    }

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        Elements elements = environment.getElementUtils();
        Elements counted = proxy(Elements.class, (Object self, Method method, Object[] arguments) -> {
            if (method.getName().equals("getAllMembers") && arguments[0] instanceof TypeElement type) {
                MEMBER_LISTINGS.merge(type.getQualifiedName().toString(), 1, Integer::sum);
            }
            return forward(elements, method, arguments);
        });
        delegate.init(proxy(ProcessingEnvironment.class, (Object self, Method method, Object[] arguments) ->
                method.getName().equals("getElementUtils") ? counted : forward(environment, method, arguments)));
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
        int[] scans = {0};
        RoundEnvironment counted = proxy(RoundEnvironment.class, (Object self, Method method, Object[] arguments) -> {
            if (method.getName().startsWith("getElementsAnnotatedWith")) {
                scans[0]++;
            }
            return forward(round, method, arguments);
        });
        boolean claimed = delegate.process(annotations, counted);
        SCANS_PER_ROUND.add(scans[0]);
        return claimed;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(CountingProcessor.class.getClassLoader(), new Class<?>[] {type},
                handler));
    }

    private static Object forward(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
