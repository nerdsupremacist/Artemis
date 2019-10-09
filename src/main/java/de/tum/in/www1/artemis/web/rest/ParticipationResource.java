package de.tum.in.www1.artemis.web.rest;

import static de.tum.in.www1.artemis.web.rest.util.ResponseUtil.badRequest;
import static de.tum.in.www1.artemis.web.rest.util.ResponseUtil.forbidden;
import static java.time.ZonedDateTime.now;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.modeling.ModelingExercise;
import de.tum.in.www1.artemis.domain.quiz.QuizExercise;
import de.tum.in.www1.artemis.service.*;
import de.tum.in.www1.artemis.service.UserService;
import de.tum.in.www1.artemis.service.connectors.ContinuousIntegrationService;
import de.tum.in.www1.artemis.service.connectors.VersionControlService;
import de.tum.in.www1.artemis.web.rest.errors.AccessForbiddenException;
import de.tum.in.www1.artemis.web.rest.errors.BadRequestAlertException;
import de.tum.in.www1.artemis.web.rest.errors.EntityNotFoundException;
import de.tum.in.www1.artemis.web.rest.util.HeaderUtil;
import de.tum.in.www1.artemis.web.rest.util.ResponseUtil;

/**
 * REST controller for managing Participation.
 */
@RestController
@RequestMapping({ "/api", "/api_basic" })
@PreAuthorize("hasRole('ADMIN')")
public class ParticipationResource {

    private final Logger log = LoggerFactory.getLogger(ParticipationResource.class);

    private static final String ENTITY_NAME = "participation";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final ParticipationService participationService;

    private final ProgrammingExerciseParticipationService programmingExerciseParticipationService;

    private final QuizExerciseService quizExerciseService;

    private final ExerciseService exerciseService;

    private final CourseService courseService;

    private final AuthorizationCheckService authCheckService;

    private final Optional<ContinuousIntegrationService> continuousIntegrationService;

    private final Optional<VersionControlService> versionControlService;

    private final TextSubmissionService textSubmissionService;

    private final ResultService resultService;

    private final UserService userService;

    public ParticipationResource(ParticipationService participationService, ProgrammingExerciseParticipationService programmingExerciseParticipationService,
            CourseService courseService, QuizExerciseService quizExerciseService, ExerciseService exerciseService, AuthorizationCheckService authCheckService,
            Optional<ContinuousIntegrationService> continuousIntegrationService, Optional<VersionControlService> versionControlService, TextSubmissionService textSubmissionService,
            ResultService resultService, UserService userService) {
        this.participationService = participationService;
        this.programmingExerciseParticipationService = programmingExerciseParticipationService;
        this.quizExerciseService = quizExerciseService;
        this.exerciseService = exerciseService;
        this.courseService = courseService;
        this.authCheckService = authCheckService;
        this.continuousIntegrationService = continuousIntegrationService;
        this.versionControlService = versionControlService;
        this.textSubmissionService = textSubmissionService;
        this.resultService = resultService;
        this.userService = userService;
    }

    /**
     * POST /participations : Create a new participation.
     *
     * @param participation the participation to create
     * @return the ResponseEntity with status 201 (Created) and with body the new participation, or with status 400 (Bad Request) if the participation has already an ID
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PostMapping("/participations")
    @PreAuthorize("hasAnyRole('TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Participation> createParticipation(@RequestBody StudentParticipation participation) throws URISyntaxException {
        log.debug("REST request to save Participation : {}", participation);
        checkAccessPermissionAtLeastTA(participation);
        if (participation.getId() != null) {
            throw new BadRequestAlertException("A new participation cannot already have an ID", ENTITY_NAME, "idexists");
        }
        Participation result = participationService.save(participation);
        return ResponseEntity.created(new URI("/api/participations/" + result.getId()))
                .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, result.getId().toString())).body(result);
    }

    /**
     * POST /courses/:courseId/exercises/:exerciseId/participations : start the "participationId" exercise for the current user.
     *
     * @param courseId   only included for API consistency, not actually used
     * @param exerciseId the participationId of the exercise for which to init a participation
     * @param principal  the current user principal
     * @return the ResponseEntity with status 201 (Created) and the participation within the body, or with status 404 (Not Found)
     * @throws URISyntaxException If the URI for the created participation could not be created
     */
    @PostMapping(value = "/courses/{courseId}/exercises/{exerciseId}/participations")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Participation> initParticipation(@PathVariable Long courseId, @PathVariable Long exerciseId, Principal principal) throws URISyntaxException {
        log.debug("REST request to start Exercise : {}", exerciseId);
        Exercise exercise = exerciseService.findOne(exerciseId);
        Course course = exercise.getCourse();
        if (!authCheckService.isAtLeastStudentInCourse(course, null)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
        if (participationService.findOneByExerciseIdAndStudentLoginAnyState(exerciseId, principal.getName()).isPresent()) {
            // participation already exists
            return ResponseEntity.badRequest().headers(HeaderUtil.createFailureAlert(applicationName, true, "participation", "participationAlreadyExists",
                    "There is already a participation for the given exercise and user.")).body(null);
        }

        // if the user is a student and the exercise has a release date, he cannot start the exercise before the release date
        if (exercise.getReleaseDate() != null && exercise.getReleaseDate().isAfter(now())) {

            if (authCheckService.isOnlyStudentInCourse(course, null)) {
                return forbidden();
            }
        }

        StudentParticipation participation = participationService.startExercise(exercise, principal.getName());
        // remove sensitive information before sending participation to the client
        participation.getExercise().filterSensitiveInformation();
        return ResponseEntity.created(new URI("/api/participations/" + participation.getId())).body(participation);
    }

    /**
     * POST /courses/:courseId/exercises/:exerciseId/resume-participation: resume the participation of the current user in the exercise identified by participationId
     *
     * @param courseId   only included for API consistency, not actually used
     * @param exerciseId participationId of the exercise for which to resume participation
     * @param principal  current user principal
     * @return ResponseEntity with status 200 (OK) and with updated participation as a body, or with status 500 (Internal Server Error)
     */
    @PutMapping(value = "/courses/{courseId}/exercises/{exerciseId}/resume-programming-participation")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ProgrammingExerciseStudentParticipation> resumeParticipation(@PathVariable Long courseId, @PathVariable Long exerciseId, Principal principal) {
        log.debug("REST request to resume Exercise : {}", exerciseId);
        Exercise exercise;
        try {
            exercise = exerciseService.findOne(exerciseId);
        }
        catch (EntityNotFoundException e) {
            log.info("Request to resume participation of non-existing Exercise with participationId {}.", exerciseId);
            throw new BadRequestAlertException(e.getMessage(), "exercise", "exerciseNotFound");
        }

        ProgrammingExerciseStudentParticipation participation = programmingExerciseParticipationService.findStudentParticipationByExerciseIdAndStudentId(exerciseId,
                principal.getName());
        if (participation == null) {
            log.info("Request to resume participation that is non-existing of Exercise with participationId {}.", exerciseId);
            throw new BadRequestAlertException("No participation was found for the given exercise and user.", "editor", "participationNotFound");
        }

        checkAccessPermissionOwner(participation);
        if (exercise instanceof ProgrammingExercise) {
            participation = participationService.resumeExercise(exercise, participation);
            if (participation != null) {
                addLatestResultToParticipation(participation);
                participation.getExercise().filterSensitiveInformation();
                return ResponseEntity.ok().headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, participation.getStudent().getFirstName()))
                        .body(participation);
            }
        }
        log.info("Exercise with participationId {} is not an instance of ProgrammingExercise. Ignoring the request to resume participation", exerciseId);
        // remove sensitive information before sending participation to the client
        participation.getExercise().filterSensitiveInformation();
        return ResponseEntity.badRequest().headers(HeaderUtil.createFailureAlert(applicationName, true, ENTITY_NAME, "notProgrammingExercise",
                "Exercise is not an instance of ProgrammingExercise. Ignoring the request to resume participation")).body(participation);
    }

    /**
     * This makes sure the client can display the latest result immediately after loading this participation
     * 
     * @param participation The participation to which the latest result should get added
     */
    private void addLatestResultToParticipation(Participation participation) {
        // Load results of participation as they are not contained in the current object
        participation = participationService.findOneWithEagerResults(participation.getId());

        Result result = participation.findLatestResult();
        if (result != null) {
            participation.setResults(Set.of(result));
        }
    }

    /**
     * PUT /participations : Updates an existing participation.
     *
     * @param participation the participation to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated participation, or with status 400 (Bad Request) if the participation is not valid, or with status
     *         500 (Internal Server Error) if the participation couldn't be updated
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PutMapping("/participations")
    @PreAuthorize("hasAnyRole('TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Participation> updateParticipation(@RequestBody StudentParticipation participation) throws URISyntaxException {
        log.debug("REST request to update Participation : {}", participation);
        Course course = participation.getExercise().getCourse();
        if (!courseService.userHasAtLeastTAPermissions(course)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
        if (participation.getId() == null) {
            return createParticipation(participation);
        }
        if (participation.getPresentationScore() > 1) {
            participation.setPresentationScore(1);
        }
        if (participation.getPresentationScore() < 0 || participation.getPresentationScore() == null) {
            participation.setPresentationScore(0);
        }

        StudentParticipation currentParticipation = participationService.findOneStudentParticipation(participation.getId());
        if (currentParticipation.getPresentationScore() != null && currentParticipation.getPresentationScore() > participation.getPresentationScore()) {
            User user = userService.getUser();
            log.info(user.getLogin() + " removed the presentation score of " + participation.getStudent().getLogin() + " for exercise with participationId "
                    + participation.getExercise().getId());
        }

        Participation result = participationService.save(participation);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, participation.getStudent().getFirstName())).body(result);
    }

    /**
     * GET /exercise/:exerciseId/participations : get all the participations for an exercise
     *
     * @param exerciseId The participationId of the exercise
     * @param withEagerResults Whether the {@link Result results} for the participations should also be fetched
     * @return A list of all participations for the exercise
     */
    @GetMapping(value = "/exercise/{exerciseId}/participations")
    @PreAuthorize("hasAnyRole('TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<List<StudentParticipation>> getAllParticipationsForExercise(@PathVariable Long exerciseId,
            @RequestParam(defaultValue = "false") boolean withEagerResults) {
        log.debug("REST request to get all Participations for Exercise {}", exerciseId);
        Exercise exercise = exerciseService.findOne(exerciseId);
        Course course = exercise.getCourse();
        if (!courseService.userHasAtLeastTAPermissions(course)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }

        List<StudentParticipation> participations;
        if (withEagerResults) {
            participations = participationService.findByExerciseIdWithEagerResults(exerciseId);
        }
        else {
            participations = participationService.findByExerciseId(exerciseId);
        }
        participations = participations.stream().filter(participation -> participation.getStudent() != null).collect(Collectors.toList());

        return ResponseEntity.ok(participations);
    }

    /**
     * GET /exercise/{exerciseId}/participation-without-assessment Given an exerciseId of a text exercise, retrieve a participation where the latest submission has no assessment
     * returns 404 If any, it creates the result and assign to the tutor, as a draft. This also involves a soft lock (setting the assessor of the result) so that other tutors
     * cannot assess the submission. TODO unify this approach with the one for modeling exercises
     *
     * @param exerciseId the participationId of the exercise of which we want a submission
     * @return a student participation
     */
    @GetMapping(value = "/exercise/{exerciseId}/participation-without-assessment")
    @PreAuthorize("hasAnyRole('TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Participation> getParticipationForTextExerciseWithoutAssessment(@PathVariable Long exerciseId) {
        Exercise exercise = exerciseService.findOne(exerciseId);
        if (!authCheckService.isAtLeastTeachingAssistantForExercise(exercise)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
        if (!(exercise instanceof TextExercise)) {
            return badRequest();
        }

        // Check if the limit of simultaneously locked submissions has been reached
        textSubmissionService.checkSubmissionLockLimit(exercise.getCourse().getId());

        Optional<TextSubmission> textSubmissionWithoutAssessment = textSubmissionService.getTextSubmissionWithoutManualResult((TextExercise) exercise);
        if (!textSubmissionWithoutAssessment.isPresent()) {
            // TODO return null and avoid 404 in this case
            throw new EntityNotFoundException("No text Submission without assessment has been found");
        }

        Participation participation = textSubmissionWithoutAssessment.get().getParticipation();

        Result result = new Result();
        result.setParticipation(participation);
        result.setSubmission(textSubmissionWithoutAssessment.get());
        resultService.createNewManualResult(result, false);
        participation.setResults(new HashSet<>());
        participation.addResult(result);

        if (!authCheckService.isAtLeastInstructorForExercise(exercise) && participation instanceof StudentParticipation) {
            ((StudentParticipation) participation).filterSensitiveInformation();
        }

        return ResponseEntity.ok(participation);
    }

    /**
     * GET /courses/:courseId/participations : get all the participations for a course
     *
     * @param courseId The participationId of the course
     * @return A list of all participations for the given course
     */
    @GetMapping(value = "/courses/{courseId}/participations")
    @PreAuthorize("hasAnyRole('TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<List<StudentParticipation>> getAllParticipationsForCourse(@PathVariable Long courseId) {
        long start = System.currentTimeMillis();
        log.debug("REST request to get all Participations for Course {}", courseId);
        Course course = courseService.findOne(courseId);
        if (!courseService.userHasAtLeastTAPermissions(course)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
        List<StudentParticipation> participations = participationService.findByCourseIdWithRelevantResults(courseId, false, false);
        int resultCount = 0;
        for (StudentParticipation participation : participations) {
            // we only need participationId, title, dates and max points
            // remove unnecessary elements
            Exercise exercise = participation.getExercise();
            exercise.setCourse(null);
            exercise.setStudentParticipations(null);
            exercise.setTutorParticipations(null);
            exercise.setExampleSubmissions(null);
            exercise.setAttachments(null);
            exercise.setCategories(null);
            exercise.setProblemStatement(null);
            exercise.setStudentQuestions(null);
            exercise.setGradingInstructions(null);
            exercise.setDifficulty(null);
            if (exercise instanceof ProgrammingExercise) {
                ProgrammingExercise programmingExercise = (ProgrammingExercise) exercise;
                programmingExercise.setSolutionParticipation(null);
                programmingExercise.setTemplateParticipation(null);
                programmingExercise.setTestRepositoryUrl(null);
                programmingExercise.setShortName(null);
                programmingExercise.setPublishBuildPlanUrl(null);
                programmingExercise.setProgrammingLanguage(null);
                programmingExercise.setPackageName(null);
                programmingExercise.setAllowOnlineEditor(null);
            }
            else if (exercise instanceof QuizExercise) {
                QuizExercise quizExercise = (QuizExercise) exercise;
                quizExercise.setQuizQuestions(null);
                quizExercise.setQuizPointStatistic(null);
            }
            else if (exercise instanceof TextExercise) {
                TextExercise textExercise = (TextExercise) exercise;
                textExercise.setSampleSolution(null);
            }
            else if (exercise instanceof ModelingExercise) {
                ModelingExercise modelingExercise = (ModelingExercise) exercise;
                modelingExercise.setSampleSolutionModel(null);
                modelingExercise.setSampleSolutionExplanation(null);
            }
            resultCount += participation.getResults().size();
        }
        long end = System.currentTimeMillis();
        log.info("Found " + participations.size() + " particpations with " + resultCount + " results in " + (end - start) + " ms");
        return ResponseEntity.ok().body(participations);
    }

    /**
     * GET /participations/:participationId : get the participation for the given "participationId" including its latest result.
     *
     * @param participationId the participationId of the participation to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the participation, or with status 404 (Not Found)
     */
    @GetMapping("/participations/{participationId}/withLatestResult")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<StudentParticipation> getParticipationWithLatestResult(@PathVariable Long participationId) {
        log.debug("REST request to get Participation : {}", participationId);
        StudentParticipation participation = participationService.findOneWithEagerResults(participationId);
        participationService.canAccessParticipation(participation);
        Result result = participation.getExercise().findLatestResultWithCompletionDate(participation);
        Set<Result> results = new HashSet<>();
        if (result != null) {
            results.add(result);
        }
        participation.setResults(results);
        return new ResponseEntity<>(participation, HttpStatus.OK);
    }

    /**
     * GET /participations/:participationId : get the participation for the given "participationId".
     *
     * @param participationId the participationId of the participation to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the participation, or with status 404 (Not Found)
     */
    @GetMapping("/participations/{participationId}")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<StudentParticipation> getParticipation(@PathVariable Long participationId) {
        log.debug("REST request to get participation : {}", participationId);
        StudentParticipation participation = participationService.findOneStudentParticipation(participationId);
        checkAccessPermissionOwner(participation);
        return Optional.ofNullable(participation).map(result -> new ResponseEntity<>(result, HttpStatus.OK)).orElse(ResponseUtil.notFound());
    }

    /**
     * Finds the repository web URL for a given programming exercise participation
     *
     * @param participationId The participationId of the participation
     * @return The URL of the participation repository as a String
     */
    @GetMapping(value = "/participations/{participationId}/repositoryWebUrl")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<String> getParticipationRepositoryWebUrl(@PathVariable Long participationId) {
        log.debug("REST request to get repositoryWebUrl of participation : {}", participationId);
        ProgrammingExerciseStudentParticipation participation = participationService.findProgrammingExerciseParticipation(participationId);
        checkAccessPermissionOwner(participation);
        URL url = versionControlService.get().getRepositoryWebUrl(participation);
        return Optional.ofNullable(url).map(result -> new ResponseEntity<>(url.toString(), HttpStatus.OK)).orElse(ResponseUtil.notFound());
    }

    /**
     * Looks up the web URL for the build plan in Bamboo of a given participation
     *
     * @param participationId The participationId of the participation
     * @return The URL of the participation build plan as a String
     */
    @GetMapping(value = "/participations/{participationId}/buildPlanWebUrl")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<String> getParticipationBuildPlanWebUrl(@PathVariable Long participationId) {
        log.debug("REST request to get Participation : {}", participationId);
        ProgrammingExerciseStudentParticipation participation = (ProgrammingExerciseStudentParticipation) participationService.findOneStudentParticipation(participationId);
        Course course = participation.getExercise().getCourse();
        if (!authCheckService.isOwnerOfParticipation(participation)) {
            if (!courseService.userHasAtLeastTAPermissions(course)) {
                throw new AccessForbiddenException("You are not allowed to access this resource");
            }
        }
        else if (!courseService.userHasAtLeastTAPermissions(course)) {
            // Check if build plan URL is published, if user is owner of participation and is not TA or higher
            if (participation.getExercise() instanceof ProgrammingExercise) {
                ProgrammingExercise programmingExercise = (ProgrammingExercise) participation.getExercise();
                if (!programmingExercise.isPublishBuildPlanUrl()) {
                    throw new AccessForbiddenException("You are not allowed to access this resource");
                }
            }
        }

        URL url = continuousIntegrationService.get().getBuildPlanWebUrl(participation);
        return Optional.ofNullable(url).map(result -> new ResponseEntity<>(url.toString(), HttpStatus.OK)).orElse(ResponseUtil.notFound());
    }

    /**
     * Retrieves the lates build artifact of a given programming exercise participation
     *
     * @param participationId The participationId of the participation
     * @return The latest build artifact (JAR/WAR) for the participation
     */
    @GetMapping(value = "/participations/{participationId}/buildArtifact")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    public ResponseEntity getParticipationBuildArtifact(@PathVariable Long participationId) {
        log.debug("REST request to get Participation build artifact: {}", participationId);
        ProgrammingExerciseStudentParticipation participation = participationService.findProgrammingExerciseParticipation(participationId);
        checkAccessPermissionOwner(participation);

        return continuousIntegrationService.get().retrieveLatestArtifact(participation);
    }

    /**
     * GET /courses/:courseId/exercises/:exerciseId/participation: get the user's participation for a specific exercise. Please note: 'courseId' is only included in the call for
     * API consistency, it is not actually used //TODO remove courseId from the URL
     *
     * @param exerciseId the participationId of the exercise for which to retrieve the participation
     * @param principal The principal in form of the user's identity
     * @return the ResponseEntity with status 200 (OK) and with body the participation, or with status 404 (Not Found)
     */
    @GetMapping(value = "/courses/{courseId}/exercises/{exerciseId}/participation")
    @PreAuthorize("hasAnyRole('USER', 'TA', 'INSTRUCTOR', 'ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<MappingJacksonValue> getParticipation(@PathVariable Long exerciseId, Principal principal) {
        log.debug("REST request to get Participation for Exercise : {}", exerciseId);
        Exercise exercise = exerciseService.findOne(exerciseId);
        Course course = exercise.getCourse();
        if (!courseService.userHasAtLeastStudentPermissions(course)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
        MappingJacksonValue response;
        if (exercise instanceof QuizExercise) {
            response = participationForQuizExercise((QuizExercise) exercise, principal.getName());
        }
        else if (exercise instanceof ModelingExercise) {
            Optional<StudentParticipation> optionalParticipation = participationService.findOneByExerciseIdAndStudentLoginAnyState(exerciseId, principal.getName());
            if (!optionalParticipation.isPresent()) {
                throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY, "No participation found for " + principal.getName() + " in exercise " + exerciseId);
            }
            Participation participation = optionalParticipation.get();
            participation.getResults().size(); // eagerly load the association
            response = new MappingJacksonValue(participation);
        }
        else {
            Optional<StudentParticipation> participation = participationService.findOneByExerciseIdAndStudentLoginAnyState(exerciseId, principal.getName());
            response = participation.isEmpty() ? null : new MappingJacksonValue(participation.get());
        }
        if (response == null) {
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }
        else {
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
    }

    private MappingJacksonValue participationForQuizExercise(QuizExercise quizExercise, String username) {
        if (!quizExercise.isStarted()) {
            // Quiz hasn't started yet => no Result, only quizExercise without questions
            quizExercise.filterSensitiveInformation();
            StudentParticipation participation = new StudentParticipation().exercise(quizExercise);
            return new MappingJacksonValue(participation);
        }
        else if (quizExercise.isSubmissionAllowed()) {
            // Quiz is active => construct Participation from
            // filtered quizExercise and submission from HashMap
            quizExercise = quizExerciseService.findOneWithQuestions(quizExercise.getId());
            quizExercise.filterForStudentsDuringQuiz();
            StudentParticipation participation = participationService.participationForQuizWithResult(quizExercise, username);
            // set view
            Class view = quizExerciseService.viewForStudentsInQuizExercise(quizExercise);
            MappingJacksonValue value = new MappingJacksonValue(participation);
            value.setSerializationView(view);
            return value;
        }
        else {
            // quiz has ended => get participation from database and add full quizExercise
            quizExercise = quizExerciseService.findOneWithQuestions(quizExercise.getId());
            StudentParticipation participation = participationService.participationForQuizWithResult(quizExercise, username);
            // avoid problems due to bidirectional associations between submission and result during serialization
            if (participation == null) {
                return null;
            }
            for (Result result : participation.getResults()) {
                if (result.getSubmission() != null) {
                    result.getSubmission().setResult(null);
                    result.getSubmission().setParticipation(null);
                }
            }
            return new MappingJacksonValue(participation);
        }
    }

    /**
     * GET /participations/:participationId/status: get build status of the user's participation for the "participationId" participation.
     *
     * @param id the participation participationId
     * @return the ResponseEntity with status 200 (OK) and with body the participation, or with status 404 (Not Found)
     */
    @GetMapping(value = "/participations/{participationId}/status")
    public ResponseEntity<?> getParticipationStatus(@PathVariable Long id) {
        StudentParticipation participation = participationService.findOneStudentParticipation(id);
        // NOTE: Disable Authorization check for increased performance
        // (Unauthorized users being unable to see any participation's status is not a priority!)
        if (participation.getExercise() instanceof QuizExercise) {
            QuizExercise.Status status = QuizExercise.statusForQuiz((QuizExercise) participation.getExercise());
            return new ResponseEntity<>(status, HttpStatus.OK);
        }
        else if (participation.getExercise() instanceof ProgrammingExercise) {
            ContinuousIntegrationService.BuildStatus buildStatus = continuousIntegrationService.get().getBuildStatus((ProgrammingExerciseParticipation) participation);
            return Optional.ofNullable(buildStatus).map(status -> new ResponseEntity<>(status, HttpStatus.OK)).orElse(ResponseUtil.notFound());
        }
        return ResponseEntity.unprocessableEntity().build();
    }

    /**
     * DELETE /participations/:participationId : delete the "participationId" participation. This only works for student participations - other participations should not be deleted here!
     *
     * @param participationId the participationId of the participation to delete
     * @param deleteBuildPlan True, if the build plan should also get deleted
     * @param deleteRepository True, if the repository should also get deleted
     * @param principal The identity of the user accessing this resource
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("/participations/{participationId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Void> deleteParticipation(@PathVariable Long participationId, @RequestParam(defaultValue = "false") boolean deleteBuildPlan,
            @RequestParam(defaultValue = "false") boolean deleteRepository, Principal principal) {
        StudentParticipation participation = participationService.findOneStudentParticipation(participationId);
        checkAccessPermissionAtInstructor(participation);
        String username = participation.getStudent().getFirstName();
        log.info("Delete Participation {} of exercise {} for {}, deleteBuildPlan: {}, deleteRepository: {} by {}", participationId, participation.getExercise().getTitle(),
                username, deleteBuildPlan, deleteRepository, principal.getName());
        participationService.delete(participationId, deleteBuildPlan, deleteRepository);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, "participation", username)).build();
    }

    /**
     * DELETE /participations/:participationId : remove the build plan of the ProgrammingExerciseStudentParticipation of the "participationId".
     * This only works for programming exercises.
     *
     * @param participationId the participationId of the ProgrammingExerciseStudentParticipation for which the build plan should be removed
     * @param principal The identity of the user accessing this resource
     * @return the ResponseEntity with status 200 (OK)
     */
    @PutMapping("/participations/{participationId}/cleanupBuildPlan")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<Participation> cleanupBuildPlan(@PathVariable Long participationId, Principal principal) {
        ProgrammingExerciseStudentParticipation participation = (ProgrammingExerciseStudentParticipation) participationService.findOneStudentParticipation(participationId);
        checkAccessPermissionAtInstructor(participation);
        log.info("Clean up participation with build plan {} by {}", participation.getBuildPlanId(), principal.getName());
        participationService.cleanupBuildPlan(participation);
        return ResponseEntity.ok().body(participation);
    }

    private void checkAccessPermissionAtInstructor(StudentParticipation participation) {
        Course course = findCourseFromParticipation(participation);
        User user = userService.getUserWithGroupsAndAuthorities();

        if (!authCheckService.isAtLeastInstructorInCourse(course, user)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
    }

    private void checkAccessPermissionAtLeastTA(StudentParticipation participation) {
        Course course = findCourseFromParticipation(participation);
        if (!courseService.userHasAtLeastTAPermissions(course)) {
            throw new AccessForbiddenException("You are not allowed to access this resource");
        }
    }

    private void checkAccessPermissionOwner(StudentParticipation participation) {
        Course course = findCourseFromParticipation(participation);
        if (!authCheckService.isOwnerOfParticipation(participation)) {
            if (!courseService.userHasAtLeastTAPermissions(course)) {
                throw new AccessForbiddenException("You are not allowed to access this resource");
            }
        }
    }

    private Course findCourseFromParticipation(StudentParticipation participation) {
        if (participation.getExercise() != null && participation.getExercise().getCourse() != null) {
            return participation.getExercise().getCourse();
        }

        return participationService.findOneWithEagerCourse(participation.getId()).getExercise().getCourse();
    }

    @GetMapping(value = "/participations/{participationId}/submissions")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<List<Submission>> getSubmissionsOfParticipation(@PathVariable Long participationId) {
        StudentParticipation participation = participationService.findOneStudentParticipation(participationId);
        checkAccessPermissionAtInstructor(participation);
        List<Submission> submissions = participationService.getSubmissionsWithParticipationId(participationId);
        return ResponseEntity.ok(submissions);
    }

}
