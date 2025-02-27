import { NgModule } from '@angular/core';
import { ArtemisSharedModule } from 'app/shared';
import { PresentationScoreComponent } from 'app/components/exercise/presentation-score/presentation-score.component';

@NgModule({
    imports: [ArtemisSharedModule],
    declarations: [PresentationScoreComponent],
    exports: [PresentationScoreComponent],
})
export class ArtemisPresentationScoreModule {}
