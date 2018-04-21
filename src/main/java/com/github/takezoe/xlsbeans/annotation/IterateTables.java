package com.github.takezoe.xlsbeans.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *
 * @author Mitsuyoshi Hasegawa
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Documented
public @interface IterateTables {

    String tableLabel();
    Class<?> tableClass();
    int bottom() default -1;
    boolean optional() default false;
}
