package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Closes a row: every cell of {@code state} nobody declared is FORBID with {@code otherwise} as the reason. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Rows.class)
public @interface Row {

    String state();

    String otherwise();
}
