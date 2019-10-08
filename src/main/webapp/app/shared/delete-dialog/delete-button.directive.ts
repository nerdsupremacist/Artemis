import { DeleteDialogData, DeleteDialogService } from 'app/shared/delete-dialog/delete-dialog.service';
import { Output, EventEmitter, Input, Directive, HostListener, Renderer2, ElementRef, OnInit } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

/**
 * Defines the type of the action handled by delete dialog
 */
export const enum ActionType {
    Delete = 'delete',
    Reset = 'reset',
    Cleanup = 'cleanup',
}

@Directive({ selector: '[jhiDeleteButton]' })
export class DeleteButtonDirective implements OnInit {
    @Input() entityTitle: string;
    @Input() deleteQuestion: string;
    @Input() deleteConfirmationText: string;
    @Input() additionalChecks?: { [key: string]: string };
    @Input() actionType: ActionType = ActionType.Delete;
    @Output() delete = new EventEmitter<{ [key: string]: boolean }>();

    deleteTextSpan: HTMLElement;

    constructor(private deleteDialogService: DeleteDialogService, private renderer: Renderer2, private el: ElementRef, private translateService: TranslateService) {}

    /**
     * This method appends classes and type property to the button on which directive was used, additionally adds a span tag with delete text.
     * We can't use component, as Angular would wrap it in it's own tag and this will break button grouping that we are using for other buttons.
     */
    ngOnInit() {
        // set button classes and submit property
        this.renderer.addClass(this.el.nativeElement, 'btn');
        this.renderer.addClass(this.el.nativeElement, 'btn-danger');
        this.renderer.addClass(this.el.nativeElement, 'btn-sm');
        this.renderer.addClass(this.el.nativeElement, 'mr-1');
        this.renderer.setProperty(this.el.nativeElement, 'type', 'submit');

        // create a span with delete text
        this.deleteTextSpan = this.renderer.createElement('span');
        this.renderer.addClass(this.deleteTextSpan, 'd-none');
        this.renderer.addClass(this.deleteTextSpan, 'd-md-inline');
        this.setTextContent();
        this.renderer.appendChild(this.el.nativeElement, this.deleteTextSpan);

        // update the span title on each language change
        this.translateService.onLangChange.subscribe(() => {
            this.setTextContent();
        });
    }

    /**
     * Opens delete dialog
     */
    openDeleteDialog() {
        const deleteDialogData: DeleteDialogData = {
            entityTitle: this.entityTitle,
            deleteQuestion: this.deleteQuestion,
            deleteConfirmationText: this.deleteConfirmationText,
            additionalChecks: this.additionalChecks,
            actionType: this.actionType,
        };
        this.deleteDialogService.openDeleteDialog(deleteDialogData).subscribe((additionalChecksValues: { [key: string]: boolean }) => {
            this.delete.emit(additionalChecksValues);
        });
    }

    @HostListener('click')
    onClick() {
        this.openDeleteDialog();
    }

    private setTextContent() {
        switch (this.actionType) {
            case ActionType.Delete:
                this.renderer.setProperty(this.deleteTextSpan, 'textContent', this.translateService.instant('entity.action.delete'));
                break;
            case ActionType.Reset:
                this.renderer.setProperty(this.deleteTextSpan, 'textContent', this.translateService.instant('entity.action.reset'));
                break;
            case ActionType.Cleanup:
                this.renderer.setProperty(this.deleteTextSpan, 'textContent', this.translateService.instant('entity.action.archive'));
                break;
        }
    }
}
