package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

import com.thoughtworks.qdox.JavaProjectBuilder
import java.io.File

class JavaSourceCodeParser : SourceCodeParser {
    override val supportedFileExtensions: Set<String> = setOf(".java")

    override fun unsafeParse(file: File): List<Class> {
        val builder = JavaProjectBuilder()
        val src = builder.addSource(file)
        return src.classes.map { Class(it) }
    }
}