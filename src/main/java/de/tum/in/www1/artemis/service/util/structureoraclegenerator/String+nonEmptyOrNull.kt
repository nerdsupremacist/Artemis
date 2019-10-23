package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

fun String.nonEmptyOrNull(): String? = if (isNotEmpty()) this else null