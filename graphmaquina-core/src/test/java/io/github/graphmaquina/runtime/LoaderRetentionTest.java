package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What closing a registry leaves on the thread that closes it: nothing that keeps the library's classes reachable, so
 * that an application server can unload the library with the application while the thread that closed the registry, a
 * thread of the server's own, lives on. The library and the graph are loaded by a class loader of their own, a thread
 * builds a registry with a default executor there and closes it, then waits until the loader has been checked, and
 * that loader must be collectable meanwhile. The registry runs no call: a call compares records, TransitionKey's equals
 * for one, and the JDK keeps the method handle that such a comparison adapts to the record in a soft cache, which
 * System.gc() need not clear. The test assumes a JVM that collects on System.gc().
 */
class LoaderRetentionTest {

    /** Builds a registry of the doc machine, whose executor is the default one, and closes it. */
    public static final class BuildAndClose implements Runnable {

        @Override
        public void run() {
            GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                    .terminal(ARCHIVED).statePolicy(DRAFT, "a draft is submitted or discarded")
                    .statePolicy(REVIEW, "a review is published").statePolicy(PUBLISHED, "a publication is archived")
                    .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                    .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                    .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                    .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                    .build();
            MachineRegistry.builder()
                    .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                    .build().close();
        }
    }

    /** Whether {@code weak} is cleared within twenty collections. */
    private static boolean collected(WeakReference<?> weak) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            if (weak.get() == null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * Loads {@link BuildAndClose} with a class loader of its own, over the library's classes and the tests', hands it
     * to {@code closer} through {@code task} and starts {@code closer}, which runs it and counts {@code closed} down.
     * The caller keeps only the returned weak reference to that loader.
     */
    private static WeakReference<ClassLoader> runInALoaderOfItsOwn(AtomicReference<Runnable> task, Thread closer,
                                                                   CountDownLatch closed) throws Exception {
        URL[] classes = {MachineRegistry.class.getProtectionDomain().getCodeSource().getLocation(),
                Doc.class.getProtectionDomain().getCodeSource().getLocation()};
        try (URLClassLoader loader = new URLClassLoader(classes, ClassLoader.getPlatformClassLoader())) {
            task.set((Runnable) loader.loadClass(BuildAndClose.class.getName()).getConstructor().newInstance());
            closer.start();
            assertTrue(closed.await(10, TimeUnit.SECONDS), "the registry was not built and closed");
            return new WeakReference<>(loader);
        }
    }

    @Test
    void aThreadThatClosedARegistryKeepsNothingThatHoldsTheLibrarysClassLoader() throws Exception {
        WeakReference<Object> control = new WeakReference<>(new byte[8_000_000]);
        assumeTrue(collected(control), "this JVM does not collect an unreachable value on System.gc()");
        AtomicReference<Runnable> task = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            task.getAndSet(null).run();
            closed.countDown();
            try {
                checked.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            WeakReference<ClassLoader> loader = runInALoaderOfItsOwn(task, closer, closed);
            assertTrue(collected(loader), "the thread that closed the registry keeps the library's class loader");
        } finally {
            checked.countDown();
            closer.join();
        }
    }
}
