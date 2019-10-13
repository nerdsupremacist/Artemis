import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { JhiAlertService, JhiEventManager } from 'ng-jhipster';

import { ProgrammingExercise } from './programming-exercise.model';
import { ProgrammingExerciseService } from './services/programming-exercise.service';
import { CourseExerciseService, CourseService } from '../course';
import { ActivatedRoute, Router } from '@angular/router';
import { ExerciseComponent } from 'app/entities/exercise/exercise.component';
import { TranslateService } from '@ngx-translate/core';
import { AccountService } from 'app/core';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { ProgrammingExerciseImportComponent } from 'app/entities/programming-exercise/programming-exercise-import.component';
import { ExerciseService } from 'app/entities/exercise';
import { finalize } from 'rxjs/operators';
import { ActionType } from 'app/shared/delete-dialog/delete-dialog.model';

@Component({
    selector: 'jhi-programming-exercise',
    templateUrl: './programming-exercise.component.html',
})
export class ProgrammingExerciseComponent extends ExerciseComponent implements OnInit, OnDestroy {
    @Input() programmingExercises: ProgrammingExercise[];
    readonly ActionType = ActionType;
    isDeleting: boolean;
    isCleaning: boolean;
    isResetting: boolean;

    constructor(
        private programmingExerciseService: ProgrammingExerciseService,
        private courseExerciseService: CourseExerciseService,
        private exerciseService: ExerciseService,
        private accountService: AccountService,
        private jhiAlertService: JhiAlertService,
        private modalService: NgbModal,
        private router: Router,
        courseService: CourseService,
        translateService: TranslateService,
        eventManager: JhiEventManager,
        route: ActivatedRoute,
    ) {
        super(courseService, translateService, route, eventManager);
        this.programmingExercises = [];
    }

    protected loadExercises(): void {
        this.courseExerciseService.findAllProgrammingExercisesForCourse(this.courseId).subscribe(
            (res: HttpResponse<ProgrammingExercise[]>) => {
                this.programmingExercises = res.body!;
                // reconnect exercise with course
                this.programmingExercises.forEach(exercise => {
                    exercise.course = this.course;
                    exercise.isAtLeastTutor = this.accountService.isAtLeastTutorInCourse(exercise.course);
                    exercise.isAtLeastInstructor = this.accountService.isAtLeastInstructorInCourse(exercise.course);
                });
                this.emitExerciseCount(this.programmingExercises.length);
            },
            (res: HttpErrorResponse) => this.onError(res),
        );
    }

    trackId(index: number, item: ProgrammingExercise) {
        return item.id;
    }

    /**
     * Deletes programming exercise
     * @param programmingExerciseId the id of the programming exercise that we want to delete
     * @param $event passed from delete dialog to represent if checkboxes were checked
     */
    deleteProgrammingExercise(programmingExerciseId: number, $event: { [key: string]: boolean }) {
        this.isDeleting = true;
        this.programmingExerciseService.delete(programmingExerciseId, $event.deleteStudentReposBuildPlans, $event.deleteBaseReposBuildPlans).subscribe(
            () => {
                this.eventManager.broadcast({
                    name: 'programmingExerciseListModification',
                    content: 'Deleted an programmingExercise',
                });
            },
            error => this.onError(error),
            () => (this.isDeleting = false),
        );
    }

    /**
     * Cleans up programming exercise
     * @param programmingExerciseId the id of the programming exercise that we want to delete
     * @param $event passed from delete dialog to represent if checkboxes were checked
     */
    cleanupProgrammingExercise(programmingExerciseId: number, $event: { [key: string]: boolean }) {
        this.isCleaning = true;
        this.exerciseService.cleanup(programmingExerciseId, $event.deleteRepositories).subscribe(
            () => {
                if ($event.deleteRepositories) {
                    this.jhiAlertService.success('Cleanup was successful. All build plans and repositories have been deleted. All participations have been marked as Finished.');
                } else {
                    this.jhiAlertService.success('Cleanup was successful. All build plans have been deleted. Students can resume their participation.');
                }
            },
            (error: HttpErrorResponse) => {
                this.jhiAlertService.error(error.message);
            },
            () => (this.isCleaning = false),
        );
    }

    /**
     * Resets programming exercise
     * @param programmingExerciseId the id of the programming exercise that we want to delete
     */
    resetProgrammingExercise(programmingExerciseId: number) {
        this.isResetting = true;
        this.exerciseService.reset(programmingExerciseId).pipe(finalize(() => (this.isResetting = false)));
    }

    /**
     * Check if we are performing any critical operations, so the user can click on the buttons
     */
    isButtonDisabled() {
        return this.isDeleting || this.isResetting || this.isCleaning;
    }

    protected getChangeEventName(): string {
        return 'programmingExerciseListModification';
    }

    private onError(error: HttpErrorResponse) {
        this.jhiAlertService.error(error.message);
    }

    callback() {}

    openImportModal() {
        const modalRef = this.modalService.open(ProgrammingExerciseImportComponent, { size: 'lg', backdrop: 'static' });
        modalRef.result.then(
            (result: ProgrammingExercise) => {
                this.router.navigate(['course', this.courseId, 'programming-exercise', 'import', result.id]);
            },
            reason => {},
        );
    }
}
