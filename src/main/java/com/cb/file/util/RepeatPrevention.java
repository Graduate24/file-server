package com.cb.file.util;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatPrevention {

    @AliasFor("key")
    String value() default "";

    @AliasFor("value")
    String key() default "";

}
