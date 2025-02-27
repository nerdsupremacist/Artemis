package de.tum.in.www1.artemis.service.compass;

import static de.tum.in.www1.artemis.service.compass.utils.CompassConfiguration.ELEMENT_CONFIDENCE_THRESHOLD;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.tum.in.www1.artemis.config.Constants;
import de.tum.in.www1.artemis.domain.Feedback;
import de.tum.in.www1.artemis.domain.Result;
import de.tum.in.www1.artemis.domain.Submission;
import de.tum.in.www1.artemis.domain.enumeration.AssessmentType;
import de.tum.in.www1.artemis.domain.enumeration.FeedbackType;
import de.tum.in.www1.artemis.domain.modeling.ModelingSubmission;
import de.tum.in.www1.artemis.service.compass.assessment.Assessment;
import de.tum.in.www1.artemis.service.compass.assessment.CompassResult;
import de.tum.in.www1.artemis.service.compass.assessment.Score;
import de.tum.in.www1.artemis.service.compass.controller.*;
import de.tum.in.www1.artemis.service.compass.grade.Grade;
import de.tum.in.www1.artemis.service.compass.umlmodel.UMLDiagram;
import de.tum.in.www1.artemis.service.compass.umlmodel.UMLElement;
import de.tum.in.www1.artemis.service.compass.umlmodel.classdiagram.UMLAttribute;
import de.tum.in.www1.artemis.service.compass.umlmodel.classdiagram.UMLClass;
import de.tum.in.www1.artemis.service.compass.umlmodel.classdiagram.UMLMethod;
import de.tum.in.www1.artemis.service.compass.umlmodel.classdiagram.UMLPackage;
import de.tum.in.www1.artemis.service.compass.umlmodel.classdiagram.UMLRelationship;

public class CompassCalculationEngine implements CalculationEngine {

    private final Logger log = LoggerFactory.getLogger(CompassCalculationEngine.class);

    private ModelIndex modelIndex;

    private AssessmentIndex assessmentIndex;

    private AutomaticAssessmentController automaticAssessmentController;

    private ModelSelector modelSelector;

    private LocalDateTime lastUsed;

    CompassCalculationEngine(Set<ModelingSubmission> modelingSubmissions) {
        lastUsed = LocalDateTime.now();
        modelIndex = new ModelIndex();
        assessmentIndex = new AssessmentIndex();
        automaticAssessmentController = new AutomaticAssessmentController();
        modelSelector = new ModelSelector();

        for (Submission submission : modelingSubmissions) {
            // We have to unproxy here as sometimes the Submission is a Hibernate proxy resulting in a cast exception
            // when iterating over the ModelingSubmissions directly (i.e. for (ModelingSubmission submission : submissions)).
            ModelingSubmission modelingSubmission = (ModelingSubmission) Hibernate.unproxy(submission);

            String model = modelingSubmission.getModel();
            if (model != null) {
                buildModel(modelingSubmission);

                if (hasCompletedManualAssessment(modelingSubmission)) {
                    addManualAssessmentForSubmission(modelingSubmission);
                }
            }
        }
        assessModelsAutomatically();
    }

    /**
     * Checks if the given modeling submission already has a completed manual assessment. The assessment is completed if the submission has a result with a completion date.
     *
     * @param modelingSubmission the modeling submission to check
     * @return true if the submission already has a completed manual assessment, false otherwise
     */
    private boolean hasCompletedManualAssessment(ModelingSubmission modelingSubmission) {
        return modelingSubmission.getResult() != null && modelingSubmission.getResult().getCompletionDate() != null
                && modelingSubmission.getResult().getAssessmentType().equals(AssessmentType.MANUAL);
    }

    /**
     * @param modelingSubmission modelingSubmission the modelingAssessment belongs to
     * @param modelingAssessment assessment to check for conflicts
     * @return a list of conflicts modelingAssessment causes with the current manual assessment data
     */
    public Map<String, List<Feedback>> getConflictingFeedbacks(ModelingSubmission modelingSubmission, List<Feedback> modelingAssessment) {
        HashMap<String, List<Feedback>> elementConflictingFeedbackMapping = new HashMap<>();

        UMLDiagram model = getModel(modelingSubmission);

        if (model == null) {
            return elementConflictingFeedbackMapping;
        }

        modelingAssessment.forEach(currentFeedback -> {
            UMLElement currentElement = model.getElementByJSONID(currentFeedback.getReferenceElementId()); // TODO MJ return Optional ad throw Exception if no UMLElement found?

            assessmentIndex.getAssessment(currentElement.getSimilarityID()).ifPresent(assessment -> {
                List<Feedback> feedbacks = assessment.getFeedbacks(currentElement.getContext());
                List<Feedback> feedbacksInConflict = feedbacks.stream().filter(feedback -> !scoresAreConsideredEqual(feedback.getCredits(), currentFeedback.getCredits()))
                        .collect(Collectors.toList());

                if (!feedbacksInConflict.isEmpty()) {
                    elementConflictingFeedbackMapping.put(currentElement.getJSONElementID(), feedbacksInConflict);
                }
            });
        });
        return elementConflictingFeedbackMapping;
    }

    private UMLDiagram getModel(ModelingSubmission modelingSubmission) {
        UMLDiagram model = modelIndex.getModel(modelingSubmission.getId());
        // TODO properly handle this case and make sure after server restart the modelIndex is reloaded properly
        if (model == null) {
            // handle the case that model is null (e.g. after server restart)
            buildModel(modelingSubmission);
            model = modelIndex.getModel(modelingSubmission.getId());
        }
        return model;
    }

    /**
     * Creates a JSONObject from the model contained in the given modeling submission. Afterwards, it builds an UMLClassDiagramm from the JSONObject, analyzes the similarity and
     * sets the similarity ID of each model element and adds the model to the model index of the calculation engine. The model index contains all models of the corresponding
     * exercise.
     *
     * @param modelingSubmission the modeling submission containing the model as JSON string
     */
    private void buildModel(ModelingSubmission modelingSubmission) {
        if (modelingSubmission.getModel() != null) {
            buildModel(modelingSubmission.getId(), new JsonParser().parse(modelingSubmission.getModel()).getAsJsonObject());
        }
    }

    /**
     * Build an UMLClassDiagramm from a JSON representation of the model, analyzes the similarity and sets the similarity ID of each model element. Afterwards, the model is added
     * to the model index of the calculation engine which contains all models of the corresponding exercise.
     *
     * @param modelSubmissionId the id of the modeling submission the model belongs to
     * @param jsonModel         JSON representation of the UML model
     */
    private void buildModel(long modelSubmissionId, JsonObject jsonModel) {
        try {
            UMLDiagram model = JSONParser.buildModelFromJSON(jsonModel, modelSubmissionId);
            SimilarityDetector.analyzeSimilarity(model, modelIndex);
            modelIndex.addModel(model);
        }
        catch (IOException e) {
            log.error("Error while building and adding model!", e);
        }
    }

    /**
     * Adds the manual assessment of the given submission to Compass so that it can be used for automatic assessments. Additionally, it marks the submission as assessed, i.e. the
     * submission is not considered when providing a submission for manual assessment to the client.
     *
     * @param submission the submission for which the manual assessment is added
     */
    private void addManualAssessmentForSubmission(ModelingSubmission submission) {
        UMLDiagram model = modelIndex.getModelMap().get(submission.getId());

        if (model == null || submission.getResult() == null || submission.getResult().getCompletionDate() == null) {
            log.error("Could not build assessment for submission {}", submission.getId());
            return;
        }

        addNewManualAssessment(submission.getResult().getFeedbacks(), model);

        modelSelector.removeModelWaitingForAssessment(submission.getId());
        modelSelector.addAlreadyHandledModel(submission.getId());
    }

    protected Collection<UMLDiagram> getUmlModelCollection() {
        return modelIndex.getModelCollection();
    }

    protected Map<Long, UMLDiagram> getModelMap() {
        return modelIndex.getModelMap();
    }

    @SuppressWarnings("unused")
    private double getTotalCoverage() {
        return automaticAssessmentController.getTotalCoverage();
    }

    @SuppressWarnings("unused")
    private double getTotalConfidence() {
        return automaticAssessmentController.getTotalConfidence();
    }

    private void assessModelsAutomatically() {
        automaticAssessmentController.assessModelsAutomatically(modelIndex, assessmentIndex);
    }

    @Override
    public List<Long> getNextOptimalModels(int numberOfModels) {
        lastUsed = LocalDateTime.now();
        return modelSelector.selectNextModels(modelIndex, numberOfModels);
    }

    @Override
    public Grade getGradeForModel(long modelSubmissionId) {
        lastUsed = LocalDateTime.now();
        if (!modelIndex.getModelMap().containsKey(modelSubmissionId)) {
            return null;
        }

        UMLDiagram model = modelIndex.getModelMap().get(modelSubmissionId);
        CompassResult compassResult = model.getLastAssessmentCompassResult();

        if (compassResult == null) {
            return automaticAssessmentController.assessModelAutomatically(model, assessmentIndex);
        }
        return compassResult;
    }

    @Override
    public Collection<Long> getModelIds() {
        return modelIndex.getModelMap().keySet();
    }

    @Override
    public void notifyNewAssessment(List<Feedback> modelingAssessment, long assessedModelSubmissionId) {
        lastUsed = LocalDateTime.now();
        modelSelector.addAlreadyHandledModel(assessedModelSubmissionId);
        UMLDiagram model = modelIndex.getModel(assessedModelSubmissionId);
        if (model == null) {
            log.warn("Cannot add manual assessment to Compass, because the model in modelIndex is null for submission id " + assessedModelSubmissionId);
            return;
        }
        addNewManualAssessment(modelingAssessment, model);
        modelSelector.removeModelWaitingForAssessment(model.getModelSubmissionId());
        assessModelsAutomatically();
    }

    @Override
    public void notifyNewModel(String model, long modelId) {
        lastUsed = LocalDateTime.now();
        // Do not add models that might already exist
        if (modelIndex.getModelMap().containsKey(modelId)) {
            return;
        }
        buildModel(modelId, new JsonParser().parse(model).getAsJsonObject());
    }

    @Override
    public LocalDateTime getLastUsedAt() {
        return lastUsed;
    }

    @Override
    public List<Long> getModelsWaitingForAssessment() {
        return modelSelector.getModelsWaitingForAssessment();
    }

    @Override
    public void removeModelWaitingForAssessment(long modelSubmissionId, boolean isAssessed) {
        modelSelector.removeModelWaitingForAssessment(modelSubmissionId);
        if (isAssessed) {
            modelSelector.addAlreadyHandledModel(modelSubmissionId);
        }
        else {
            modelSelector.removeAlreadyHandledModel(modelSubmissionId);
        }
    }

    @Override
    public void markModelAsUnassessed(long modelSubmissionId) {
        modelSelector.removeAlreadyHandledModel(modelSubmissionId);
    }

    @Override
    public List<Feedback> convertToFeedback(Grade grade, long modelId, Result result) {
        UMLDiagram model = this.modelIndex.getModelMap().get(modelId);

        if (model == null || grade == null || grade.getJsonIdPointsMapping() == null) {
            return new ArrayList<>();
        }

        List<Feedback> feedbackList = new ArrayList<>();

        for (Map.Entry<String, Double> gradePointsEntry : grade.getJsonIdPointsMapping().entrySet()) {
            Feedback feedback = new Feedback();

            String jsonElementID = gradePointsEntry.getKey();
            UMLElement umlElement = model.getElementByJSONID(jsonElementID);

            if (umlElement == null) {
                log.error("Element " + jsonElementID + " was not found in Model");
                continue;
            }

            // Get the confidence for this element of the model. If the confidence is less than the configured threshold, no automatic feedback will be created for this element
            // and the loop will continue with the next model element.
            double elementConfidence = getConfidenceForElement(jsonElementID, modelId);
            if (elementConfidence < ELEMENT_CONFIDENCE_THRESHOLD) {
                log.debug("Confidence " + elementConfidence + " of element " + jsonElementID + " is smaller than configured confidence threshold " + ELEMENT_CONFIDENCE_THRESHOLD);
                continue;
            }

            // Set the values of the automatic feedback using the values of the Grade that Compass calculated earlier in the automatic assessment process.
            feedback.setCredits(gradePointsEntry.getValue());
            feedback.setPositive(feedback.getCredits() >= 0);
            feedback.setText(grade.getJsonIdCommentsMapping().getOrDefault(jsonElementID, ""));
            feedback.setReference(buildReferenceString(umlElement, jsonElementID));
            feedback.setType(FeedbackType.AUTOMATIC);
            feedback.setResult(result);

            feedbackList.add(feedback);
        }

        // TODO: in the future we want to store this information as well, but for now we ignore it.
        // jsonObject.addProperty(JSONMapping.assessmentElementConfidence,
        // grade.getConfidence());
        // jsonObject.addProperty(JSONMapping.assessmentElementCoverage,
        // grade.getCoverage());

        return feedbackList;
    }

    /**
     * Creates the reference string to be stored in an Feedback element for modeling submissions. The reference of a modeling Feedback stores the type of the corresponding UML
     * element and its jsonElementId and is of the form "<umlElementType>:<jsonElementIds>".
     *
     * @param umlElement    the UML model element the Feedback belongs to
     * @param jsonElementId the jsonElementId of the UML model element
     * @return the formatted reference string containing the element type and its jsonElementId
     */
    private String buildReferenceString(UMLElement umlElement, String jsonElementId) {
        return umlElement.getType() + ":" + jsonElementId;
    }

    /**
     * format: uniqueElements [{id} name apollonId conflicts] numberModels numberConflicts totalConfidence totalCoverage models [{id} confidence coverage conflicts]
     *
     * @return statistics about the UML model
     */
    // TODO: I don't think we should expose JSONObject to this internal server class. It would be better to return Java objects here
    @Override
    public JsonObject getStatistics() {
        JsonObject jsonObject = new JsonObject();

        JsonObject uniqueElements = new JsonObject();
        int numberOfConflicts = 0;
        for (UMLElement umlElement : modelIndex.getUniqueElements()) {
            JsonObject uniqueElement = new JsonObject();
            uniqueElement.addProperty("name", umlElement.toString());
            uniqueElement.addProperty("apollonId", umlElement.getJSONElementID());
            boolean hasConflict = hasConflict(umlElement.getSimilarityID());
            if (hasConflict) {
                numberOfConflicts++;
            }
            uniqueElement.addProperty("conflicts", hasConflict);
            uniqueElements.add(umlElement.getSimilarityID() + "", uniqueElement);
        }
        jsonObject.add("uniqueElements", uniqueElements);

        jsonObject.addProperty("numberModels", modelIndex.getModelCollection().size());
        jsonObject.addProperty("numberConflicts", numberOfConflicts);
        jsonObject.addProperty("totalConfidence", getTotalConfidence());
        jsonObject.addProperty("totalCoverage", getTotalCoverage());

        JsonObject models = new JsonObject();
        for (Map.Entry<Long, UMLDiagram> modelEntry : getModelMap().entrySet()) {
            JsonObject model = new JsonObject();
            model.addProperty("coverage", modelEntry.getValue().getLastAssessmentCoverage());
            model.addProperty("confidence", modelEntry.getValue().getLastAssessmentConfidence());
            int numberOfModelConflicts = 0;
            List<UMLElement> elements = new ArrayList<>(modelEntry.getValue().getAllModelElements());
            for (UMLElement element : elements) {
                boolean modelHasConflict = hasConflict(element.getSimilarityID());
                if (modelHasConflict) {
                    numberOfModelConflicts++;
                }
            }
            model.addProperty("conflicts", numberOfModelConflicts);
            model.addProperty("elements", elements.size());
            model.addProperty("classes", elements.stream().filter(umlElement -> umlElement instanceof UMLClass).count());
            model.addProperty("attributes", elements.stream().filter(umlElement -> umlElement instanceof UMLAttribute).count());
            model.addProperty("methods", elements.stream().filter(umlElement -> umlElement instanceof UMLMethod).count());
            model.addProperty("associations", elements.stream().filter(umlElement -> umlElement instanceof UMLRelationship).count());
            models.add(modelEntry.getKey().toString(), model);
        }
        jsonObject.add("models", models);

        return jsonObject;
    }

    @Override
    public double getConfidenceForElement(String elementId, long submissionId) {
        UMLDiagram model = modelIndex.getModel(submissionId);
        UMLElement element = model.getElementByJSONID(elementId);
        if (element == null) {
            return 0.0;
        }

        Optional<Assessment> optionalAssessment = assessmentIndex.getAssessment(element.getSimilarityID());
        return optionalAssessment.map(assessment -> assessment.getScore(element.getContext()).getConfidence()).orElse(0.0);
    }

    /**
     * Adds the given feedback elements of a manual assessment to the automatic assessment controller where it can be used for automatic assessments.
     *
     * @param modelingAssessment the feedback of a manual assessment
     * @param model              the corresponding model
     */
    private void addNewManualAssessment(List<Feedback> modelingAssessment, UMLDiagram model) {
        Map<String, Feedback> feedbackMapping = createElementIdFeedbackMapping(modelingAssessment);
        automaticAssessmentController.addFeedbacksToAssessment(assessmentIndex, feedbackMapping, model);
    }

    /**
     * Create a jsonModelId to Feedback mapping generated from the feedback list of a submission.
     *
     * @param feedbackList the feedbackList
     * @return a map of elementIds to scores
     */
    private Map<String, Feedback> createElementIdFeedbackMapping(List<Feedback> feedbackList) {
        Map<String, Feedback> elementIdFeedbackMap = new HashMap<>();
        feedbackList.forEach(feedback -> {
            String jsonElementId = feedback.getReferenceElementId();
            if (jsonElementId != null) {
                elementIdFeedbackMap.put(jsonElementId, feedback);
            }
        });
        return elementIdFeedbackMap;
    }

    private boolean hasConflict(int elementId) {
        Optional<Assessment> assessment = this.assessmentIndex.getAssessment(elementId);
        if (assessment.isPresent()) {
            for (List<Feedback> feedbacks : assessment.get().getContextFeedbackList().values()) {
                double uniqueScore = -1;
                for (Feedback feedback : feedbacks) {
                    if (uniqueScore != -1 && uniqueScore != feedback.getCredits()) {
                        return true;
                    }
                    uniqueScore = feedback.getCredits();
                }
            }
        }
        return false;
    }

    private boolean scoresAreConsideredEqual(double score1, double score2) {
        return Math.abs(score1 - score2) < Constants.COMPASS_SCORE_EQUALITY_THRESHOLD;
    }

    @Override
    public void printStatistic(long exerciseId, List<Result> finishedResults) {
        log.debug("Statistics for exercise " + exerciseId + "\n\n\n");

        long totalNumberOfFeedback = 0;
        long totalNumberOfAutomaticFeedback = 0;
        long totalNumberOfAdaptedFeedback = 0;
        long totalNumberOfManualFeedback = 0;

        long numberOfAssessedClasses = 0;
        long numberOfAssessedAttrbutes = 0;
        long numberOfAssessedMethods = 0;
        long numberOfAssessedRelationships = 0;
        long numberOfAssessedPackages = 0;

        long totalLengthOfFeedback = 0;
        long totalLengthOfPositiveFeedback = 0;
        long totalNumberOfPositiveFeedbackItems = 0;
        long totalLengthOfNegativeFeedback = 0;
        long totalNumberOfNegativeFeedbackItems = 0;
        long totalLengthOfNeutralFeedback = 0;
        long totalNumberOfNeutralFeedbackItems = 0;

        for (Result result : finishedResults) {
            List<Feedback> referenceFeedback = result.getFeedbacks().stream().filter(feedback -> feedback.getReference() != null).collect(Collectors.toList());

            totalNumberOfFeedback += referenceFeedback.size();
            totalNumberOfAutomaticFeedback += referenceFeedback.stream().filter(feedback -> feedback.getType() == FeedbackType.AUTOMATIC).count();
            totalNumberOfAdaptedFeedback += referenceFeedback.stream().filter(feedback -> feedback.getType() == FeedbackType.AUTOMATIC_ADAPTED).count();
            totalNumberOfManualFeedback += referenceFeedback.stream().filter(feedback -> feedback.getType() == FeedbackType.MANUAL).count();

            numberOfAssessedClasses += referenceFeedback.stream()
                    .filter(feedback -> feedback.getReferenceElementType().equals("Class") || feedback.getReferenceElementType().equals("AbstractClass")
                            || feedback.getReferenceElementType().equals("Interface") || feedback.getReferenceElementType().equals("Enumeration"))
                    .count();
            numberOfAssessedAttrbutes += referenceFeedback.stream().filter(feedback -> feedback.getReferenceElementType().equals("ClassAttribute")).count();
            numberOfAssessedMethods += referenceFeedback.stream().filter(feedback -> feedback.getReferenceElementType().equals("ClassMethod")).count();
            numberOfAssessedRelationships += referenceFeedback.stream()
                    .filter(feedback -> feedback.getReferenceElementType().equals("ClassBidirectional") || feedback.getReferenceElementType().equals("ClassUnidirectional")
                            || feedback.getReferenceElementType().equals("ClassAggregation") || feedback.getReferenceElementType().equals("ClassInheritance")
                            || feedback.getReferenceElementType().equals("ClassDependency") || feedback.getReferenceElementType().equals("ClassComposition")
                            || feedback.getReferenceElementType().equals("ClassRealization"))
                    .count();
            numberOfAssessedPackages += referenceFeedback.stream().filter(feedback -> feedback.getReferenceElementType().equals("Package")).count();

            for (Feedback feedback : referenceFeedback) {
                int feedbackLength = 0;

                if (feedback.getText() != null) {
                    feedbackLength = feedback.getText().length();
                }

                totalLengthOfFeedback += feedbackLength;

                if (feedback.getCredits() > 0) {
                    totalLengthOfPositiveFeedback += feedbackLength;
                    totalNumberOfPositiveFeedbackItems++;
                }
                else if (feedback.getCredits() == 0) {
                    totalLengthOfNeutralFeedback += feedbackLength;
                    totalNumberOfNeutralFeedbackItems++;
                }
                else if (feedback.getCredits() < 0) {
                    totalLengthOfNegativeFeedback += feedbackLength;
                    totalNumberOfNegativeFeedbackItems++;
                }
            }
        }

        long numberOfModels = modelIndex.getModelCollection().size();
        Map<UMLElement, Integer> modelElementMapping = modelIndex.getModelElementMapping();
        long numberOfModelElements = modelElementMapping.size();
        long numberOfClasses = 0;
        long numberOfAttrbutes = 0;
        long numberOfMethods = 0;
        long numberOfRelationships = 0;
        long numberOfPackages = 0;

        for (UMLElement element : modelElementMapping.keySet()) {
            if (element instanceof UMLClass) {
                numberOfClasses++;
            }
            else if (element instanceof UMLAttribute) {
                numberOfAttrbutes++;
            }
            else if (element instanceof UMLMethod) {
                numberOfMethods++;
            }
            else if (element instanceof UMLRelationship) {
                numberOfRelationships++;
            }
            else if (element instanceof UMLPackage) {
                numberOfPackages++;
            }
        }

        // General information
        log.debug("################################################## General information ##################################################" + "\n");

        log.debug("Number of models: " + numberOfModels + "\n");
        log.debug("Number of model elements: " + numberOfModelElements + "\n");
        log.debug("Number of classes: " + numberOfClasses + "\n");
        log.debug("Number of attributes: " + numberOfAttrbutes + "\n");
        log.debug("Number of methods: " + numberOfMethods + "\n");
        log.debug("Number of relationships: " + numberOfRelationships + "\n");
        log.debug("Number of packages: " + numberOfPackages + "\n");
        double elementsPerModel = numberOfModelElements * 1.0 / numberOfModels;
        log.debug("Average number of elements per model: " + elementsPerModel + "\n");

        log.debug("Number of assessed models: " + finishedResults.size() + "\n");
        log.debug("Number of assessed model elements: " + totalNumberOfFeedback + "\n");
        log.debug("Number of assessed classes: " + numberOfAssessedClasses + " (" + Math.round(numberOfAssessedClasses * 10000.0 / numberOfClasses) / 100.0 + "%)" + "\n");
        log.debug("Number of assessed attributes: " + numberOfAssessedAttrbutes + " (" + Math.round(numberOfAssessedAttrbutes * 10000.0 / numberOfAttrbutes) / 100.0 + "%)" + "\n");
        log.debug("Number of assessed methods: " + numberOfAssessedMethods + " (" + Math.round(numberOfAssessedMethods * 10000.0 / numberOfMethods) / 100.0 + "%)" + "\n");
        log.debug("Number of assessed relationships: " + numberOfAssessedRelationships + " (" + Math.round(numberOfAssessedRelationships * 10000.0 / numberOfRelationships) / 100.0
                + "%)" + "\n");
        log.debug("Number of assessed packages: " + numberOfAssessedPackages + " (" + Math.round(numberOfAssessedPackages * 10000.0 / numberOfPackages) / 100.0 + "%)" + "\n");
        double feedbackPerAssessment = totalNumberOfFeedback * 1.0 / finishedResults.size();
        log.debug("Average number of feedback elements per assessment: " + feedbackPerAssessment + "\n\n\n");

        // Feedback type
        log.debug("################################################## Feedback type ##################################################" + "\n");

        log.debug("Automatic feedback: " + totalNumberOfAutomaticFeedback + " (" + Math.round(totalNumberOfAutomaticFeedback * 10000.0 / totalNumberOfFeedback) / 100.0 + "%)"
                + "\n");
        log.debug("Adapted feedback: " + totalNumberOfAdaptedFeedback + " (" + Math.round(totalNumberOfAdaptedFeedback * 10000.0 / totalNumberOfFeedback) / 100.0 + "%)" + "\n");
        log.debug("Manual feedback: " + totalNumberOfManualFeedback + " (" + Math.round(totalNumberOfManualFeedback * 10000.0 / totalNumberOfFeedback) / 100.0 + "%)" + "\n");
        log.debug("Amount of automatic feedback that was adapted: "
                + Math.round(totalNumberOfAdaptedFeedback * 10000.0 / (totalNumberOfAutomaticFeedback + totalNumberOfAdaptedFeedback)) / 100.0 + "%\n\n\n");

        // Feedback length
        log.debug("################################################## Feedback length ##################################################" + "\n");

        log.debug("Total amount of feedback: " + totalNumberOfFeedback + "\n");
        log.debug("Average length of feedback: " + totalLengthOfFeedback * 1.0 / totalNumberOfFeedback + "\n");
        log.debug("Total amount of positive feedback: " + totalNumberOfPositiveFeedbackItems + "\n");
        log.debug("Average length of positive feedback: " + totalLengthOfPositiveFeedback * 1.0 / totalNumberOfPositiveFeedbackItems + "\n");
        log.debug("Total amount of neutral feedback: " + totalNumberOfNeutralFeedbackItems + "\n");
        log.debug("Average length of neutral feedback: " + totalLengthOfNeutralFeedback * 1.0 / totalNumberOfNeutralFeedbackItems + "\n");
        log.debug("Total amount of negative feedback: " + totalNumberOfNegativeFeedbackItems + "\n");
        log.debug("Average length of negative feedback: " + totalLengthOfNegativeFeedback * 1.0 / totalNumberOfNegativeFeedbackItems + "\n\n\n");

        // Similarity sets
        log.debug("################################################## Similarity sets ##################################################" + "\n");

        // Note, that these two value refer to all similarity sets that have an assessment, i.e. it is not the total number as it excludes the sets without assessments. This might
        // distort the analysis values below.
        long numberOfSimilaritySets = 0;
        long numberOfElementsInSimilaritySets = 0;

        long numberOfSimilaritySetsPositiveScore = 0;
        long numberOfSimilaritySetsPositiveScoreRegardingConfidence = 0;

        for (Assessment assessment : assessmentIndex.getAssessmentsMap().values()) {
            numberOfSimilaritySets += assessment.getContextFeedbackList().size();
            for (List<Feedback> feedbackList : assessment.getContextFeedbackList().values()) {
                numberOfElementsInSimilaritySets += feedbackList.size();
            }

            for (Score score : assessment.getContextScoreList().values()) {
                if (score.getPoints() > 0) {
                    numberOfSimilaritySetsPositiveScore += 1;
                    if (score.getConfidence() >= 0.8) {
                        numberOfSimilaritySetsPositiveScoreRegardingConfidence += 1;
                    }
                }
            }
        }

        log.debug("Number of unique elements (without context) of submitted models: " + modelIndex.getNumberOfUniqueElements() + "\n");
        log.debug("Number of similarity sets (including context) of assessed models: " + numberOfSimilaritySets + "\n");
        log.debug("Average number of elements per similarity set: " + numberOfElementsInSimilaritySets * 1.0 / numberOfSimilaritySets + "\n");
        // The optimal correction effort describes the maximum amount of model elements that tutors would have to assess in an optimal scenario
        log.debug("Optimal correction effort (# similarity sets / # model elements): " + numberOfSimilaritySets * 1.0 / numberOfElementsInSimilaritySets + "\n");

        log.debug("Number of similarity sets with positive score: " + numberOfSimilaritySetsPositiveScore + "\n");
        log.debug("Number of similarity sets with positive score and confidence at least 80%: " + numberOfSimilaritySetsPositiveScoreRegardingConfidence + "\n\n\n");

        // Variability index
        log.debug("################################################## Variability index ##################################################" + "\n");

        log.debug("Variability index #1 (positive score): " + numberOfSimilaritySetsPositiveScore / elementsPerModel + "\n");
        log.debug("Variability index #2 (positive score and confidence >= 80%): " + numberOfSimilaritySetsPositiveScoreRegardingConfidence / elementsPerModel + "\n");
        log.debug("Variability index #3 (based on \"all\" similarity sets): " + numberOfSimilaritySets / elementsPerModel + "\n");

        log.debug("Normalized variability index #1 (positive score): " + (numberOfSimilaritySetsPositiveScore - elementsPerModel) / (numberOfModelElements - elementsPerModel)
                + "\n");
        log.debug("Normalized variability index #2 (positive score and confidence >= 80%): "
                + (numberOfSimilaritySetsPositiveScoreRegardingConfidence - elementsPerModel) / (numberOfModelElements - elementsPerModel) + "\n");
        log.debug("Normalized variability index #3 (based on \"all\" similarity sets): " + (numberOfSimilaritySets - elementsPerModel) / (numberOfModelElements - elementsPerModel)
                + "\n");

        // Alternative calculation of the variability index considering the average feedback items per assessment instead of the average elements per model
        log.debug("Alternative variability index #1 (positive score): " + numberOfSimilaritySetsPositiveScore / feedbackPerAssessment + "\n");
        log.debug("Alternative variability index #2 (positive score and confidence >= 80%): " + numberOfSimilaritySetsPositiveScoreRegardingConfidence / feedbackPerAssessment
                + "\n");
        log.debug("Alternative variability index #3 (based on \"all\" similarity sets): " + numberOfSimilaritySets / feedbackPerAssessment + "\n");

        log.debug("Normalized alternative variability index #1 (positive score): "
                + (numberOfSimilaritySetsPositiveScore - feedbackPerAssessment) / (totalNumberOfFeedback - feedbackPerAssessment) + "\n");
        log.debug("Normalized alternative variability index #2 (positive score and confidence >= 80%): "
                + (numberOfSimilaritySetsPositiveScoreRegardingConfidence - feedbackPerAssessment) / (totalNumberOfFeedback - feedbackPerAssessment) + "\n");
        log.debug("Normalized alternative variability index #3 (based on \"all\" similarity sets): "
                + (numberOfSimilaritySets - feedbackPerAssessment) / (totalNumberOfFeedback - feedbackPerAssessment) + "\n");
    }
}
