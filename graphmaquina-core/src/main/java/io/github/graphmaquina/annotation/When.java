package io.github.graphmaquina.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One rule of a watch: exactly one of {@code condition}, {@code allIn}, {@code anyIn}, {@code noneIn}, and the target.
 *
 * @deprecated since 0.3: declare the graph with the vocabulary the StateGraph generator emits for the
 *     machine. This declaration is kept only so a project that has not been migrated is met with SG609
 *     and the typed declaration to write instead.
 */
@Target({})
@Retention(RetentionPolicy.CLASS)
@Deprecated(since = "0.3")
public @interface When {

    String condition() default "";

    String allIn() default "";

    String anyIn() default "";

    String noneIn() default "";

    String goTo();
}
