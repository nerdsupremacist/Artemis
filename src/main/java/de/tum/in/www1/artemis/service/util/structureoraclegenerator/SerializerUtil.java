package de.tum.in.www1.artemis.service.util.structureoraclegenerator;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * This class contains helper methods for serializing information on structural elements that we deal with repeatedly throughout the other serializers in order to avoid code
 * repetition.
 */
class SerializerUtil {

    /**
     * This method is used to serialize the string representations of each modifier into a JSON array.
     *
     * @param modifiers A collection of modifiers that needs to get serialized.
     * @param javaMember The model of the {@link java.lang.reflect.Member} for which all modifiers should get serialized
     * @return The JSON array containing the string representations of the modifiers.
     */
    static JsonArray serializeModifiers(Set<String> modifiers, Member javaMember) {
        JsonArray modifiersArray = new JsonArray();
        if (javaMember.getDeclaringClass().isInterface()) {
            // constructors are not possible here
            if (javaMember instanceof Method) {
                // interface methods are always public and abstract, however the qdox framework does not report this when parsing the Java source file
                modifiers.add("public");
                modifiers.add("abstract");
            }
            else if (javaMember instanceof Method) {
                // interface attributes are always public, static and final, however the qdox framework does not report this when parsing the Java source file
                modifiers.add("public");
                modifiers.add("static");
                modifiers.add("final");
            }
        }
        for (String modifier : modifiers) {
            modifiersArray.add(modifier);
        }
        return modifiersArray;
    }

    /**
     * This method is used to serialize the string representations of each modifier into a JSON array.
     *
     * @param annotations The annotations of the java member (e.g. Override, Inject, etc.)
     * @return The JSON array containing the string representations of the modifiers.
     */
    static JsonArray serializeAnnotations(List<Annotation> annotations) {
        JsonArray annotationsArray = new JsonArray();
        for (Annotation annotation : annotations) {
            annotationsArray.add(annotation.getType().getSimpleName());
        }
        return annotationsArray;
    }

    /**
     * This method is used to serialize the string representations of each parameter into a JSON array.
     * 
     * @param parameters A collection of modifiers that needs to get serialized.
     * @return The JSON array containing the string representations of the parameter types.
     */
    static JsonArray serializeParameters(List<Parameter> parameters) {
        JsonArray parametersArray = new JsonArray();
        for (Parameter parameter : parameters) {
            parametersArray.add(parameter.getType().getValue());
        }

        return parametersArray;
    }

    /**
     * creates the json object for the serialization and inserts the name and the modifiers
     *
     * @param name The name property of the new JSON object
     * @param javaMember The model for the {@link java.lang.reflect.Member} for which all modifiers should get serialized
     * @param modifiers A collection of modifiers that need to get serialized
     * @param annotations A collection of annotations that need to get serialized
     * @return A new JSON object containing all serialized modifiers under the {@code "modifiers"} key and the name of
     *  the object under the {@code "name"} key
     */
    static JsonObject createJsonObject(String name, Set<String> modifiers, Member javaMember, List<Annotation> annotations) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", name);
        if (!modifiers.isEmpty()) {
            jsonObject.add("modifiers", serializeModifiers(modifiers, javaMember));
        }
        if (!annotations.isEmpty()) {
            jsonObject.add("annotations", serializeAnnotations(annotations));
        }

        return jsonObject;
    }
}
