package com.devopsaaas.tool.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a tool input field to the LLM; becomes the "description" of the field in the JSON Schema. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.FIELD})
public @interface Description {

    String value();
}
