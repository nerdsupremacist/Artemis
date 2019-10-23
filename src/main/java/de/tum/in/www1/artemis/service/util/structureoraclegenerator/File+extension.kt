package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

import java.io.File

val File.extension: String?
    get() = name.removePrefix(nameWithoutExtension).nonEmptyOrNull()