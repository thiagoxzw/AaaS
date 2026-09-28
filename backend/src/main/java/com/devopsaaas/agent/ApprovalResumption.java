package com.devopsaaas.agent;

import com.devopsaaas.approval.ApprovalDecided;
import com.devopsaaas.approval.ApprovalProperties;
import com.devopsaaas.approval.ApprovalWorkflow;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Slice 7: gets an execution going again once its approvals are decided.
 * <ul>
 *   <li>Right after a decision commits, the execution is dispatched for resumption.</li>
 *   <li>A sweep, idempotent and driven only by the stored state, closes approvals past their deadline and
 *       resumes every execution in WAITING_APPROVAL with nothing PENDING left: it covers a lost event, a full
 *       queue and a restart between the decision and the resumption (RNF-CONF-08).</li>
 * </ul>
 * Resuming twice is harmless: the execution and each call leave WAITING_APPROVAL through conditional
 * transitions under their row locks.
 */
@Component
public class ApprovalResumption {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumption.class);

    private final ExecutionJournal journal;
    private final ExecutionDispatcher dispatcher;
    private final ApprovalWorkflow approvals;
    private final ApprovalProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("approval-sweep").factory());

    ApprovalResumption(ExecutionJournal journal, ExecutionDispatcher dispatcher, ApprovalWorkflow approvals,
            ApprovalProperties properties) {
        this.journal = journal;
        this.dispatcher = dispatcher;
        this.approvals = approvals;
        this.properties = properties;
    }

    public record SweepResult(int expired, int resumed) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onApprovalDecided(ApprovalDecided decided) {
        dispatcher.resume(new ExecutionRef(decided.agentExecutionId(), decided.organizationId()));
    }

    @EventListener(ApplicationReadyEvent.class)
    void startSweep() {
        if (properties.sweepEnabled()) {
            long interval = properties.sweepInterval().toMillis();
            long first = Math.min(interval, Duration.ofSeconds(5).toMillis());
            scheduler.scheduleWithFixedDelay(this::sweepSafely, first, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** One pass: expire what is due, then resume what no longer waits for anyone. */
    public SweepResult sweep() {
        int expired = approvals.expireDue();
        int resumed = 0;
        for (ExecutionRef ref : journal.waitingForApproval()) {
            if (!approvals.hasPending(ref.organizationId(), ref.executionId()) && dispatcher.resume(ref)) {
                resumed++;
            }
        }
        return new SweepResult(expired, resumed);
    }

    private void sweepSafely() {
        try {
            SweepResult result = sweep();
            if (result.expired() > 0 || result.resumed() > 0) {
                log.info("Approval sweep: {} expired, {} executions resumed", result.expired(), result.resumed());
            }
        } catch (RuntimeException exception) {
            log.error("Approval sweep failed; it runs again at the next interval", exception);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
