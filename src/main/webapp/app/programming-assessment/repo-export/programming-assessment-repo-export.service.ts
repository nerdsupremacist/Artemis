import { Injectable } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SERVER_API_URL } from 'app/app.constants';
import { Moment } from 'moment';

export type RepositoryExportOptions = {
    exportAllStudents: boolean;
    filterLateSubmissions: boolean;
    filterLateSubmissionsDate: Moment | null;
    addStudentName: boolean;
    squashAfterInstructor: boolean;
    normalizeCodeStyle: boolean;
};

@Injectable({ providedIn: 'root' })
export class ProgrammingAssessmentRepoExportService {
    // TODO: We should move this endpoint to api/programming-exercises.
    public resourceUrl = SERVER_API_URL + 'api/exercises';

    constructor(private http: HttpClient) {}

    exportRepos(exerciseId: number, students: string[], repositoryExportOptions: RepositoryExportOptions): Observable<HttpResponse<Blob>> {
        return this.http.post(`${this.resourceUrl}/${exerciseId}/participations/${students}`, repositoryExportOptions, {
            observe: 'response',
            responseType: 'blob',
        });
    }
}
