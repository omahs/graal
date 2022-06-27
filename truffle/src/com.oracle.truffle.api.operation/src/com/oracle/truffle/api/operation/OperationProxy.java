package com.oracle.truffle.api.operation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(OperationProxies.class)
public @interface OperationProxy {
    Class<?> value();

    String operationName() default "";
}