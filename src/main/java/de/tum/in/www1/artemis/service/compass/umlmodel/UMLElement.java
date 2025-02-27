package de.tum.in.www1.artemis.service.compass.umlmodel;

import java.util.Objects;

import de.tum.in.www1.artemis.service.compass.assessment.Context;

public abstract class UMLElement implements Similarity<UMLElement> {

    private int similarityID; // id of similarity set the element belongs to

    private String jsonElementID; // unique element id //TODO rename into uniqueId?

    private Context context;

    public UMLElement(String jsonElementID) {
        this.jsonElementID = jsonElementID;
    }

    /**
     * Get the name of the UML element. If the type of element has no name (e.g. UMLRelationship), the element type is returned instead.
     *
     * @return the name of the UML element, or the type of the element if no name attribute exists for the element type
     */
    public abstract String getName();

    /**
     * Get the type of the UML element as string. IMPORTANT: It should be the same as the type attribute of the respective UML elements used in the JSON representation of the
     * models created by Apollon.
     *
     * @return the type of the UML element
     */
    public abstract String getType();

    /**
     * Get the similarity ID of this UML element. Similar elements share the same similarity ID.
     *
     * @return the similarity ID of the UML element
     */
    public int getSimilarityID() {
        return similarityID;
    }

    /**
     * Set the similarity ID of this UML element. Similar elements share the same similarity ID.
     *
     * @param similarityID the new similarity ID of the element
     */
    public void setSimilarityID(int similarityID) {
        this.similarityID = similarityID;
    }

    /**
     * Get the JSON ID of this UML element. The JSON ID is the ID that the element has in the JSON representation of the containing diagram generated by Apollon.
     *
     * @return the JSON ID of the UML element
     */
    public String getJSONElementID() {
        return jsonElementID;
    }

    /**
     * Set the JSON ID of this UML element. The JSON ID is the ID that the element has in the JSON representation of the containing diagram generated by Apollon.
     *
     * @param jsonElementID the new JSON ID of the element
     */
    public void setJsonElementID(String jsonElementID) {
        this.jsonElementID = jsonElementID;
    }

    /**
     * Get the context of this UML element. The context is relevant for the automatic assessment of elements. Only elements that share the same context and the same similarity ID
     * are considered as equal in the automatic assessment process.
     *
     * @return the context of the element
     */
    public Context getContext() {
        return context;
    }

    /**
     * Set the context of this UML element. The context is relevant for the automatic assessment of elements. Only elements that share the same context and the same similarity ID
     * are considered as equal in the automatic assessment process.
     *
     * @param context the new context of the element
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /**
     * Makes sure that the given similarity value is between 0 and 1. It returns 0 if the given value is smaller than 0, it returns 1 if the given value is greater than 1,
     * otherwise it returns the original value.
     *
     * @param similarity the similarity value that should be between 0 and 1
     * @return the similarity value between 0 and 1
     */
    protected double ensureSimilarityRange(double similarity) {
        return Math.min(Math.max(similarity, 0), 1);
    }

    @Override
    public abstract String toString();

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        UMLElement otherElement = (UMLElement) obj;

        return Objects.equals(otherElement.similarityID, similarityID) && Objects.equals(otherElement.jsonElementID, jsonElementID) && Objects.equals(otherElement.context, context)
                && Objects.equals(otherElement.getName(), getName()) && Objects.equals(otherElement.getType(), getType());
    }

    @Override
    public int hashCode() {
        return Objects.hash(similarityID, jsonElementID, context);
    }
}
