import { Component, OnDestroy, OnInit } from '@angular/core';
import { Location } from '@angular/common';
import { HttpResponse } from '@angular/common/http';
import { Exercise, ExerciseCategory, ExerciseService, ExerciseType, participationStatus } from 'app/entities/exercise';
import { CourseService } from 'app/entities/course';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs/Subscription';
import { Result } from 'app/entities/result';
import * as moment from 'moment';
import { AccountService, JhiWebsocketService, User } from 'app/core';
import {
    InitializationState,
    Participation,
    ParticipationService,
    ParticipationWebsocketService,
    ProgrammingExerciseStudentParticipation,
    StudentParticipation,
} from 'app/entities/participation';
import { GuidedTourService } from 'app/guided-tour/guided-tour.service';
import { programmingExerciseFail, programmingExerciseSuccess } from 'app/guided-tour/tours/course-exercise-detail-tour';
import { SourceTreeService } from 'app/components/util/sourceTree.service';
import { CourseScoreCalculationService } from 'app/overview';

const MAX_RESULT_HISTORY_LENGTH = 5;

@Component({
    selector: 'jhi-course-exercise-details',
    templateUrl: './course-exercise-details.component.html',
    styleUrls: ['../course-overview.scss'],
})
export class CourseExerciseDetailsComponent implements OnInit, OnDestroy {
    readonly QUIZ = ExerciseType.QUIZ;
    readonly PROGRAMMING = ExerciseType.PROGRAMMING;
    readonly MODELING = ExerciseType.MODELING;
    readonly TEXT = ExerciseType.TEXT;
    readonly FILE_UPLOAD = ExerciseType.FILE_UPLOAD;
    private currentUser: User;
    private exerciseId: number;
    public courseId: number;
    private subscription: Subscription;
    public exercise: Exercise | null;
    public showMoreResults = false;
    public sortedHistoryResult: Result[]; // might be a subset of the actual results in combinedParticipation.results
    public exerciseCategories: ExerciseCategory[];
    private participationUpdateListener: Subscription;
    combinedParticipation: StudentParticipation;
    isAfterAssessmentDueDate: boolean;

    showWelcomeAlert = false;

    constructor(
        private $location: Location,
        private exerciseService: ExerciseService,
        private courseService: CourseService,
        private jhiWebsocketService: JhiWebsocketService,
        private accountService: AccountService,
        private courseCalculationService: CourseScoreCalculationService,
        private participationWebsocketService: ParticipationWebsocketService,
        private participationService: ParticipationService,
        private sourceTreeService: SourceTreeService,
        private courseServer: CourseService,
        private route: ActivatedRoute,
        private guidedTourService: GuidedTourService,
    ) {}

    ngOnInit() {
        this.subscription = this.route.params.subscribe(params => {
            const didExerciseChange = this.exerciseId !== parseInt(params['exerciseId'], 10);
            const didCourseChange = this.courseId !== parseInt(params['courseId'], 10);
            this.exerciseId = parseInt(params['exerciseId'], 10);
            this.courseId = parseInt(params['courseId'], 10);
            this.accountService.identity().then((user: User) => {
                this.currentUser = user;
            });
            if (didExerciseChange || didCourseChange) {
                this.loadExercise();
            }
        });

        this.route.queryParams.subscribe(queryParams => {
            if (queryParams['welcome'] === '') {
                setTimeout(() => {
                    this.showWelcomeAlert = true;
                }, 500);
            }
        });
    }

    loadExercise() {
        this.exercise = null;
        const cachedParticipations = this.participationWebsocketService.getAllParticipationsForExercise(this.exerciseId);
        if (cachedParticipations && cachedParticipations.length > 0) {
            this.exerciseService.find(this.exerciseId).subscribe((exerciseResponse: HttpResponse<Exercise>) => {
                this.handleNewExercise(exerciseResponse.body!);
            });
        } else {
            this.exerciseService.findResultsForExercise(this.exerciseId).subscribe((exerciseResponse: HttpResponse<Exercise>) => {
                this.handleNewExercise(exerciseResponse.body!);
            });
        }
    }

    handleNewExercise(newExercise: Exercise) {
        this.exercise = newExercise;
        this.exercise.participationStatus = participationStatus(this.exercise);
        this.exercise.studentParticipations = this.filterParticipations(this.exercise.studentParticipations)!;
        this.mergeResultsAndSubmissionsForParticipations();
        this.isAfterAssessmentDueDate = !this.exercise.assessmentDueDate || moment().isAfter(this.exercise.assessmentDueDate);
        this.exerciseCategories = this.exerciseService.convertExerciseCategoriesFromServer(this.exercise);
        this.subscribeForNewResults();
    }

    /**
     * Filter for participations that belong to the current user only. Additionally, we make sure that all results that are not finished (i.e. completionDate is not set) are
     * removed from the participations. We also sort the participations so that FINISHED participations come first.
     */
    private filterParticipations(participations: StudentParticipation[]): StudentParticipation[] | null {
        if (!participations) {
            return null;
        }
        const filteredParticipations = participations.filter((participation: StudentParticipation) => participation.student && participation.student.id === this.currentUser.id);
        filteredParticipations.forEach((participation: Participation) => {
            if (participation.results) {
                participation.results = participation.results.filter((result: Result) => result.completionDate);
            }
        });
        this.sortParticipationsFinishedFirst(filteredParticipations);
        return filteredParticipations;
    }

    /**
     * Sort the given participations so that FINISHED participations come first.
     *
     * Note, that this function directly operates on the array passed as argument and does not return anything.
     */
    private sortParticipationsFinishedFirst(participations: StudentParticipation[]) {
        if (participations && participations.length > 1) {
            participations.sort((a, b) => (b.initializationState === InitializationState.FINISHED ? 1 : -1));
        }
    }

    ngOnDestroy() {
        if (this.participationUpdateListener) {
            this.participationUpdateListener.unsubscribe();
        }
    }

    sortResults() {
        if (this.hasResults) {
            this.combinedParticipation.results = this.combinedParticipation.results.sort((a, b) => {
                const aValue = moment(a.completionDate!).valueOf();
                const bValue = moment(b.completionDate!).valueOf();
                return aValue - bValue;
            });
            const sortedResultLength = this.combinedParticipation.results.length;
            const startingElement = Math.max(sortedResultLength - MAX_RESULT_HISTORY_LENGTH, 0);
            this.sortedHistoryResult = this.combinedParticipation.results.slice(startingElement, sortedResultLength);
        }
    }

    mergeResultsAndSubmissionsForParticipations() {
        if (this.exercise && this.exercise.studentParticipations && this.exercise.studentParticipations.length > 0) {
            if (this.exercise.type === ExerciseType.PROGRAMMING) {
                this.combinedParticipation = this.participationService.mergeProgrammingParticipations(this.exercise
                    .studentParticipations as ProgrammingExerciseStudentParticipation[]);
            } else {
                this.combinedParticipation = this.participationService.mergeStudentParticipations(this.exercise.studentParticipations);
            }
            this.sortResults();
        }
    }

    subscribeForNewResults() {
        if (this.exercise && this.exercise.studentParticipations && this.exercise.studentParticipations.length > 0) {
            this.exercise.studentParticipations.forEach(participation => {
                this.participationWebsocketService.addParticipation(participation, this.exercise!);
            });
            if (this.currentResult) {
                if (this.currentResult.successful) {
                    this.guidedTourService.enableTourForExercise(this.exercise, programmingExerciseSuccess);
                } else if (this.currentResult.hasFeedback && !this.currentResult.successful) {
                    this.guidedTourService.enableTourForExercise(this.exercise, programmingExerciseFail);
                }
            }
        } else {
            this.participationWebsocketService.addExerciseForNewParticipation(this.exercise!.id);
        }
        this.participationUpdateListener = this.participationWebsocketService.subscribeForParticipationChanges().subscribe((changedParticipation: StudentParticipation) => {
            if (changedParticipation && this.exercise && changedParticipation.exercise.id === this.exercise.id) {
                this.exercise.studentParticipations =
                    this.exercise.studentParticipations && this.exercise.studentParticipations.length > 0
                        ? this.exercise.studentParticipations.map(el => {
                              return el.id === changedParticipation.id ? changedParticipation : el;
                          })
                        : [changedParticipation];
                this.mergeResultsAndSubmissionsForParticipations();
            }
        });
    }

    backToCourse() {
        this.$location.back();
    }

    exerciseRatedBadge(result: Result): string {
        return result.rated ? 'badge-success' : 'badge-info';
    }

    get exerciseIsOver(): boolean {
        return this.exercise ? moment(this.exercise!.dueDate!).isBefore(moment()) : true;
    }

    get hasMoreResults(): boolean {
        return this.combinedParticipation.results.length > MAX_RESULT_HISTORY_LENGTH;
    }

    get exerciseRouterLink(): string | null {
        if (this.exercise && this.exercise.type === ExerciseType.MODELING) {
            return `/course/${this.courseId}/exercise/${this.exercise!.id}/assessment`;
        } else if (this.exercise && this.exercise.type === ExerciseType.TEXT) {
            return `/text/${this.exercise.id}/assessment`;
        } else if (this.exercise && this.exercise.type === ExerciseType.FILE_UPLOAD) {
            return `/file-upload-exercise/${this.exercise.id}/assessment`;
        } else {
            return null;
        }
    }

    get showResults(): boolean {
        if (this.exercise!.type === ExerciseType.MODELING || this.exercise!.type === ExerciseType.TEXT) {
            return this.hasResults && this.isAfterAssessmentDueDate;
        }
        return this.hasResults;
    }

    get hasResults(): boolean {
        if (!this.exercise || !this.exercise.studentParticipations || this.exercise.studentParticipations.length === 0) {
            return false;
        }
        return this.exercise.studentParticipations.some((participation: Participation) => participation.results && participation.results.length > 0);
    }

    /**
     * Returns the latest finished result for modeling and text exercises. It does not have to be rated.
     * For other exercise types it returns a rated result.
     */
    get currentResult(): Result | null {
        if (!this.hasResults) {
            return null;
        }

        if (this.exercise!.type === ExerciseType.MODELING || this.exercise!.type === ExerciseType.TEXT) {
            return this.combinedParticipation.results.find((result: Result) => !!result.completionDate) || null;
        }

        const ratedResults = this.combinedParticipation.results.filter((result: Result) => result.rated);
        const latestResult = ratedResults.length ? ratedResults[ratedResults.length - 1] : null;
        if (latestResult) {
            latestResult.participation = this.combinedParticipation;
        }
        return latestResult;
    }
}
