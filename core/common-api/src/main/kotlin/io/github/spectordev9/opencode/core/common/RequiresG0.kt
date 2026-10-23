package io.github.spectordev9.opencode.core.common

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresG0(val reason: String)
