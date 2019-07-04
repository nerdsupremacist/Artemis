package de.tum.in.www1.artemis.service.connectors;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import de.tum.in.www1.artemis.domain.Participation;
import de.tum.in.www1.artemis.service.ResultService;

@Service
public class BambooScheduleService {

    private final Logger log = LoggerFactory.getLogger(BambooScheduleService.class);

    private final BambooService bambooService;

    private final ResultService resultService;

    private Map<Long, ScheduledFuture> scheduledFutures = new HashMap<>(); // Participation_id -> ScheduledFuture

    private static ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
    static {
        threadPoolTaskScheduler.setThreadNamePrefix("BambooScheduler");
        threadPoolTaskScheduler.setPoolSize(3);
        threadPoolTaskScheduler.initialize();
    }

    public BambooScheduleService(BambooService bambooService, @Lazy ResultService resultService) { // TODO: check if @Lazy is fine
        this.bambooService = bambooService;
        this.resultService = resultService;
    }

    public void startResultScheduler(Participation participation) {
        log.debug("Scheduling checkResult() for Participation " + participation.getId());

        cancelResultScheduler(participation); // Cancel running schedulers

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, 30);

        ScheduledFuture scheduledFuture = threadPoolTaskScheduler.scheduleWithFixedDelay(() -> checkResult(participation), calendar.getTime(), 30000);

        scheduledFutures.put(participation.getId(), scheduledFuture);
    }

    private void checkResult(Participation participation) {
        log.info("Checking result for participation " + participation.getId());
        ContinuousIntegrationService.BuildStatus buildStatus = bambooService.getBuildStatus(participation);
        if (buildStatus.equals(ContinuousIntegrationService.BuildStatus.INACTIVE)) {
            log.info("Inactive build state with missing result");

            // We did not get notified by Bamboo -> We fetch the result manually
            resultService.onResultNotifiedOld(participation);
            cancelResultScheduler(participation);
            return;
        }

        log.info("Retrying checkResult as build status is not inactive for Participation " + participation.getId());

        // If the build status is not inactive, we will not cancel the schedule and check for the result again
    }

    public void cancelResultScheduler(Participation participation) {
        ScheduledFuture scheduledFuture = scheduledFutures.get(participation.getId());
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFutures.remove(participation.getId());
        }
    }
}
