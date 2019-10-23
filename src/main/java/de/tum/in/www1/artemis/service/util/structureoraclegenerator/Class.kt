package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

interface Member {
    val modifiers: Set<String>
    val declaringClass: Class

    companion object
}

interface Type {
    val value: String

    companion object
}

interface Class : Type, Member {
    val name: String

    val simpleName: String
    val `package`: Package
    val canonicalName: String

    val isInterface: Boolean
    val isEnum: Boolean
    val isAbstract: Boolean

    val superClass: Class?
    val interfaces: List<Class>

    val constructors: List<Executable>

    val fields: List<Field>
    val methods: List<Method>
    val annotations: List<Annotation>

    val packageName: String
        get() = `package`.name

    override val value: String
        get() = simpleName

    companion object
}

interface Field : Member {
    val annotations: List<Annotation>
    val name: String
    val type: Class

    val isEnumConstant: Boolean

    companion object
}

interface Executable : Member {
    val annotations: List<Annotation>
    val parameters: List<Parameter>

    companion object
}

interface Method : Executable {
    val name: String
    val returnType: Type
    val returns: Class

    companion object
}

interface Parameter {
    val type: Type
    val name: String

    companion object
}

interface Package {
    val name: String

    companion object
}

interface Annotation {
    val type: Class

    companion object
}