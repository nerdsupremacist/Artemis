package de.tum.in.www1.artemis.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import de.tum.in.www1.artemis.domain.FileUploadSubmission;

/**
 * Spring Data JPA repository for the FileUploadSubmission entity.
 */
@Repository
public interface FileUploadSubmissionRepository extends JpaRepository<FileUploadSubmission, Long> {

    /**
     * @param submissionId the submission id we are interested in
     * @return the submission with its feedback and assessor
     */
    @Query("select distinct submission from FileUploadSubmission submission left join fetch submission.result r left join fetch r.feedbacks left join fetch r.assessor where submission.id = :#{#submissionId}")
    Optional<FileUploadSubmission> findByIdWithEagerResultAndFeedback(@Param("submissionId") Long submissionId);

    /**
     * @param submissionId the submission id we are interested in
     * @return the submission with its assessor
     */
    @Query("select distinct submission from FileUploadSubmission submission left join fetch submission.result r left join fetch r.assessor where submission.id = :#{#submissionId}")
    Optional<FileUploadSubmission> findByIdWithEagerResult(@Param("submissionId") Long submissionId);

    /**
     * Load the file upload submission with the given id together with its result, the feedback list of the result, the assessor of the result, its participation and all results of
     * the participation.
     *
     * @param submissionId the id of the file upload submission that should be loaded from the database
     * @return the file upload submission with its result, the feedback list of the result, the assessor of the result, its participation and all results of the participation
     */
    @EntityGraph(attributePaths = { "result", "result.feedbacks", "result.assessor", "participation", "participation.results" })
    Optional<FileUploadSubmission> findWithEagerResultAndFeedbackAndAssessorAndParticipationResultsById(Long submissionId);

    /**
     * @param courseId the course id we are interested in
     * @return the number of submissions belonging to the course id, which have the submitted flag set to true and the submission date before the exercise due date, or no exercise
     *         due date at all
     */
    @Query("SELECT COUNT (DISTINCT submission) FROM FileUploadSubmission submission WHERE submission.participation.exercise.course.id = :#{#courseId} AND submission.submitted = TRUE AND (submission.submissionDate < submission.participation.exercise.dueDate OR submission.participation.exercise.dueDate IS NULL)")
    long countByCourseIdSubmittedBeforeDueDate(@Param("courseId") Long courseId);

    /**
     * @param exerciseId the exercise id we are interested in
     * @return the number of submissions belonging to the exercise id, which have the submitted flag set to true and the submission date before the exercise due date, or no
     *         exercise due date at all
     */
    @Query("SELECT COUNT (DISTINCT submission) FROM FileUploadSubmission submission WHERE submission.participation.exercise.id = :#{#exerciseId} AND submission.submitted = TRUE AND (submission.submissionDate < submission.participation.exercise.dueDate OR submission.participation.exercise.dueDate IS NULL)")
    long countByExerciseIdSubmittedBeforeDueDate(@Param("exerciseId") Long exerciseId);

}
