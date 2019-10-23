package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

interface Compoundable<T: Compoundable<T>> {
    interface Builder<T: Compoundable<T>> {
        operator fun T.unaryPlus()
    }

    operator fun plus(other: T): T
}

fun <T: Compoundable<T>> composite(init: Compoundable.Builder<T>.() -> Unit): T {
    return CompoundableBuilder<T>().apply(init).build()
}

private class CompoundableBuilder<T: Compoundable<T>> : Compoundable.Builder<T> {
    private var value: T? = null

    override fun T.unaryPlus() {
        value = value?.let { it + this } ?: this
    }

    fun build(): T {
        return value ?: throw IllegalArgumentException("Cannot build empty composites yet")
    }
}
