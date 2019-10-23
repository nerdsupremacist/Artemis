package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

import java.io.File

interface SourceCodeParser : Compoundable<SourceCodeParser> {
    val supportedFileExtensions: Set<String>
    fun unsafeParse(file: File): List<Class>

    fun supports(file: File) = supportedFileExtensions.contains(file.extension)

    fun parse(file: File): List<Class> {
        require(supports(file)) {
            "File ${file.name} is not supported by parser: $this"
        }
        return unsafeParse(file)
    }

    fun parse(files: Iterable<File>): List<Class> {
        return files.flatMap(::parse)
    }

    override fun plus(other: SourceCodeParser): SourceCodeParser {
        return CompositeParser(this, other)
    }

    companion object : SourceCodeParser by composite({
        +JavaSourceCodeParser()
    })
}

private class CompositeParser(
    private val first: SourceCodeParser,
    private val second: SourceCodeParser
) : SourceCodeParser {
    override val supportedFileExtensions: Set<String> =
        first.supportedFileExtensions.union(second.supportedFileExtensions)

    override fun unsafeParse(file: File): List<Class> {
        return when {
            first.supports(file) -> first.unsafeParse(file)
            second.supports(file) -> second.unsafeParse(file)
            else -> throw IllegalArgumentException(
                "File ${file.name} is not supported by either parser: $first or $second"
            )
        }
    }
}

