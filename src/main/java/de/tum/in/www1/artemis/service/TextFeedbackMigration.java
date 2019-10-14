package de.tum.in.www1.artemis.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.in.www1.artemis.domain.Feedback;
import de.tum.in.www1.artemis.domain.TextBlock;
import de.tum.in.www1.artemis.domain.TextExercise;
import de.tum.in.www1.artemis.domain.TextSubmission;
import de.tum.in.www1.artemis.repository.FeedbackRepository;
import de.tum.in.www1.artemis.repository.TextBlockRepository;
import de.tum.in.www1.artemis.repository.TextExerciseRepository;

@Service
public class TextFeedbackMigration {

    private final Logger log = LoggerFactory.getLogger(TextFeedbackMigration.class);

    private final String textBlockIdRegex = "^[a-f0-9]{40}$";

    private final Pattern textBlockIdPattern = Pattern.compile(textBlockIdRegex);

    private final FeedbackRepository feedbackRepository;

    private final TextExerciseRepository textExerciseRepository;

    private final TextBlockRepository textBlockRepository;

    public TextFeedbackMigration(FeedbackRepository feedbackRepository, TextExerciseRepository textExerciseRepository, TextBlockRepository textBlockRepository) {
        this.feedbackRepository = feedbackRepository;
        this.textExerciseRepository = textExerciseRepository;
        this.textBlockRepository = textBlockRepository;
    }

    @NotNull
    private List<TextExercise> findAllTextExercises() {
        return textExerciseRepository.findAll();
    }

    @NotNull
    private List<Long> findAllTextExerciseIds() {
        return findAllTextExercises().parallelStream().map(TextExercise::getId).collect(Collectors.toList());
    }

    @NotNull
    private List<Feedback> findAllTextFeedbacks() {
        return feedbackRepository.findAllByResult_Submission_Participation_ExerciseIdIn(findAllTextExerciseIds());
    }

    @NotNull
    private List<Feedback> findAllTextFeedbacksNeedingMigration() {
        return findAllTextFeedbacks().parallelStream().filter(feedback -> feedback.getReference() != null && !textBlockIdPattern.matcher(feedback.getReference()).matches())
                .collect(Collectors.toList());
    }

    /**
     * Migrate Feedback on Text Exercises to use TextBlocks as reference.
     */
    @PostConstruct()
    public void migrate() {
        log.info("Starting Migration of Text Feedback");

        final List<Feedback> feedbackList = findAllTextFeedbacksNeedingMigration();
        log.info("Found {} Feedback Elements in need of a migration.", feedbackList.size());

        final List<TextBlock> textBlockList = new ArrayList<>();
        for (Feedback feedback : feedbackList) {
            final TextBlock textBlock = new TextBlock();
            final TextSubmission submission = (TextSubmission) feedback.getResult().getSubmission();
            final String submissionText = submission.getText();
            final String feedbackReference = feedback.getReference();

            final int startIndex = submissionText.indexOf(feedbackReference);
            final int endIndex = startIndex + feedbackReference.length();

            textBlock.setSubmission(submission);
            textBlock.setText(feedbackReference);
            textBlock.setStartIndex(startIndex);
            textBlock.setEndIndex(endIndex);
            textBlock.computeId();
            textBlockList.add(textBlock);

            feedback.setReference(textBlock.getId());
        }

        feedbackRepository.saveAll(feedbackList);
        textBlockRepository.saveAll(textBlockList);

        log.info("Finished migrating {} Feedback Elements and created {} new Text Blocks.", feedbackList.size(), textBlockList.size());
    }
}
