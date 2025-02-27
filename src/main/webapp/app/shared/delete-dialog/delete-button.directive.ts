import { DeleteDialogData, DeleteDialogService } from 'app/shared/delete-dialog/delete-dialog.service';
import { Output, EventEmitter, Input, Directive, HostListener, Renderer2, ElementRef, OnInit } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

@Directive({ selector: '[jhiDeleteButton]' })
export class DeleteButtonDirective implements OnInit {
    @Input() entityTitle: string;
    @Input() deleteQuestion: string;
    @Input() deleteConfirmationText: string;
    @Input() additionalChecks?: { [key: string]: string };
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
        this.renderer.setProperty(this.deleteTextSpan, 'textContent', this.translateService.instant('entity.action.delete'));
        this.renderer.appendChild(this.el.nativeElement, this.deleteTextSpan);

        // update the span title on each language change
        this.translateService.onLangChange.subscribe(() => {
            this.renderer.setProperty(this.deleteTextSpan, 'textContent', this.translateService.instant('entity.action.delete'));
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
        };
        this.deleteDialogService.openDeleteDialog(deleteDialogData).subscribe((additionalChecksValues: { [key: string]: boolean }) => {
            this.delete.emit(additionalChecksValues);
        });
    }

    @HostListener('click')
    onClick() {
        this.openDeleteDialog();
    }
}
