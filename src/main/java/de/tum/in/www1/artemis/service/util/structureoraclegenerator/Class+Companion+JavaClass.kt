package de.tum.`in`.www1.artemis.service.util.structureoraclegenerator

import com.thoughtworks.qdox.model.*

operator fun Class.Companion.invoke(javaClass: JavaClass): Class {
    return object : Class {
        override val modifiers: Set<String> by lazy { javaClass.modifiers.toSet() }

        override val declaringClass: Class get() = Class(javaClass.declaringClass)

        override val name: String get() = javaClass.name

        override val simpleName: String get() = javaClass.simpleName

        override val `package`: Package get() = Package(javaClass.`package`)

        override val canonicalName: String get() = javaClass.canonicalName

        override val isInterface: Boolean get() = javaClass.isInterface

        override val isEnum: Boolean get() = javaClass.isEnum

        override val isAbstract: Boolean get() = javaClass.isAbstract

        override val superClass: Class? get() = javaClass.superJavaClass?.let { Class(it) }

        override val interfaces: List<Class> get() = javaClass.interfaces.map { Class(it) }

        override val constructors: List<Executable> get() = javaClass.constructors.map { Executable(it) }

        override val fields: List<Field> get() = javaClass.fields.map { Field(it) }

        override val methods: List<Method> get() = javaClass.methods.map { Method(it) }

        override val annotations: List<Annotation> get() = javaClass.annotations.map { Annotation(it) }
    }
}

operator fun Type.Companion.invoke(javaType: JavaType): Type {
    return object : Type {
        override val value: String get() = javaType.value
    }
}

operator fun Member.Companion.invoke(javaMember: JavaMember): Member {
    return object : Member {
        override val modifiers: Set<String> by lazy { javaMember.modifiers.toSet() }
        override val declaringClass: Class get() = Class(javaMember.declaringClass)
    }
}

operator fun Package.Companion.invoke(javaPackage: JavaPackage): Package {
    return object : Package {
        override val name: String get() = javaPackage.name
    }
}

operator fun Executable.Companion.invoke(javaExecutable: JavaExecutable): Executable {
    return object : Executable, Member by Member(javaExecutable) {
        override val annotations: List<Annotation>
            get() = javaExecutable.annotations.map { Annotation(it) }
        override val parameters: List<Parameter>
            get() = javaExecutable.parameters.map { Parameter(it) }
    }
}

operator fun Method.Companion.invoke(javaMethod: JavaMethod): Method {
    return object : Method, Executable by Executable(javaMethod) {
        override val name: String get() = javaMethod.name
        override val returnType: Type get() = Type(javaMethod.returnType)
        override val returns: Class get() = Class(javaMethod.returns)
    }
}

operator fun Parameter.Companion.invoke(javaParameter: JavaParameter): Parameter {
    return object : Parameter {
        override val type: Type get() = Type(javaParameter.type)
        override val name: String get() = javaParameter.name
    }
}

operator fun Field.Companion.invoke(javaField: JavaField): Field {
    return object : Field, Member by Member(javaField) {
        override val annotations: List<Annotation>
            get() = javaField.annotations.map { Annotation(it) }

        override val name: String get() = javaField.name
        override val type: Class get() = Class(javaField.type)

        override val isEnumConstant: Boolean get() = javaField.isEnumConstant
    }
}

operator fun Annotation.Companion.invoke(javaAnnotation: JavaAnnotation): Annotation {
    return object : Annotation {
        override val type: Class get() = Class(javaAnnotation.type)
    }
}