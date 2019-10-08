import { Routes } from '@angular/router';
import { UserRouteAccessService } from 'app/core';
import { TextAssessmentComponent } from './text-assessment.component';
import { TextAssessmentDashboardComponent } from './text-assessment-dashboard/text-assessment-dashboard.component';

export const textAssessmentRoutes: Routes = [
    {
        path: 'course-admin/:courseId/text-exercise/:exerciseId/submission/:submissionId/assessment',
        component: TextAssessmentComponent,
        data: {
            authorities: ['ROLE_ADMIN', 'ROLE_INSTRUCTOR', 'ROLE_TA'],
            pageTitle: 'artemisApp.textExercise.home.title',
        },
        canActivate: [UserRouteAccessService],
    },
    {
        path: 'course-admin/:courseId/text-exercise/:exerciseId/assessment',
        component: TextAssessmentDashboardComponent,
        data: {
            authorities: ['ROLE_ADMIN', 'ROLE_INSTRUCTOR', 'ROLE_TA'],
            pageTitle: 'assessmentDashboard.title',
        },
        canActivate: [UserRouteAccessService],
    },
];
