package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * The observers of a registry, notified in the order they were added. Each callback runs with the reentrancy marker
 * set, so an observer cannot start a transition on its own thread. A throwable that is not an Error, a checked
 * exception included, is logged as a warning that names the observer, the callback and the call, never the entity, and
 * the next observer is still notified. An Error propagates unchanged, and the marker is released whatever the callback
 * throws.
 */
final class Observers {

    /** The observers of a standalone machine's registry, and of a registry built without any. */
    static final Observers NONE = new Observers(List.of());

    private final List<MachineObserver> observers;
    private final Logger logger;

    Observers(List<MachineObserver> observers) {
        this(observers, System.getLogger("io.github.graphmaquina"));
    }

    /** With the logger the contained exceptions go to; tests hand in one that records them. */
    Observers(List<MachineObserver> observers, Logger logger) {
        this.observers = List.copyOf(observers);
        this.logger = logger;
    }

    boolean isEmpty() {
        return observers.isEmpty();
    }

    /**
     * Notifies onStarted of a call whose clock was read at {@code startNanos}, the {@code System.nanoTime()} every end
     * event of the call measures from; the caller reads it just before.
     */
    Observed started(MachineCall call, long startNanos) {
        notifyEach(call, "onStarted", (MachineObserver observer) -> observer.onStarted(call));
        return new Observed(this, call, startNanos);
    }

    void completed(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
        notifyEach(call, "onCompleted", (MachineObserver observer) -> observer.onCompleted(call, outcome, elapsed));
    }

    void failed(MachineCall call, CallFailure failure, Duration elapsed) {
        notifyEach(call, "onFailed", (MachineObserver observer) -> observer.onFailed(call, failure, elapsed));
    }

    private void notifyEach(MachineCall call, String callback, Consumer<MachineObserver> event) {
        for (MachineObserver observer : observers) {
            Reentrancy.enter(call.key(), call.entity());
            try {
                event.accept(observer);
            } catch (Error fatal) {
                throw fatal;
            } catch (Throwable e) {
                logger.log(Level.WARNING, () -> "the observer " + observer.getClass().getName() + " threw in "
                        + callback + " for " + call, e);
            } finally {
                Reentrancy.exit();
            }
        }
    }
}
