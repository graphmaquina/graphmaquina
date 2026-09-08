package io.stategraph.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One rule of a watch: exactly one of {@code condition}, {@code allIn}, {@code anyIn}, {@code noneIn}, and the target. */
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface When {

    String condition() default "";

    String allIn() default "";

    String anyIn() default "";

    String noneIn() default "";

    String goTo();
}
