package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an exported graph guard. {@code propagates} lists the throwable types that reach the caller as
 * themselves: when this guard, on the root step of a call the application makes or in a check, throws or completes its
 * stage with an instance of one of them, the call aborts as for any guard that throws, its observers hear of the abort,
 * and the caller then receives that instance instead of the {@code TransitionAbortedException}, or instead of the
 * {@code EligibilityCheckException} of a check. The guard of a cascaded step, of a sub-machine's exit, of a watch
 * reaction or of an automatic attempt after a commit keeps the abort. The types follow the rule of {@code failsOn}
 * ({@code SG302}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface GraphGuard {

    /**
     * The throwable types that reach the caller as themselves.
     *
     * @return the types, none by default
     */
    Class<? extends Throwable>[] propagates() default {};
}
