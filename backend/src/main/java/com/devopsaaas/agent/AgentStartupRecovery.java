package com.devopsaaas.agent;

import com.devopsaaas.tool.execution.ToolExecutionHistory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (RNF-CONF-08/09). Without a broker, a crash loses the in-memory workers but not the state:
 * <ul>
 *   <li>RUNNING executions cannot continue: INTERRUPTED;</li>
 *   <li>RUNNING tool calls may have reached the runtime: OUTCOME_UNKNOWN;</li>
 *   <li>QUEUED executions never started: dispatched again;</li>
 *   <li>WAITING_APPROVAL executions are untouched: they wait for a human, not for this process. A decision
 *       this process never acted on is picked up by the approval sweep ({@link ApprovalResumption}).</li>
 * </ul>
 */
@Component
public class AgentStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(AgentStartupRecovery.class);

    private final ExecutionJournal journal;
    private final ToolExecutionHistory tools;
    private final ExecutionDispatcher dispatcher;
    private final AgentProperties properties;

    AgentStartupRecovery(ExecutionJournal journal, ToolExecutionHistory tools, ExecutionDispatcher dispatcher,
            AgentProperties properties) {
        this.journal = journal;
        this.tools = tools;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    public record Result(int interruptedExecutions, int outcomeUnknownToolCalls, int redispatched) {
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (properties.recoverOnStartup()) {
            Result result = recover();
            log.info("Startup recovery: {} executions interrupted, {} tool calls with unknown outcome, "
                    + "{} queued executions dispatched again", result.interruptedExecutions(),
                    result.outcomeUnknownToolCalls(), result.redispatched());
        }
    }

    public Result recover() {
        int interrupted = journal.interruptRunning();
        int unknown = tools.recoverInterrupted();
        List<ExecutionRef> queued = journal.queued();
        dispatcher.redispatch(queued);
        return new Result(interrupted, unknown, queued.size());
    }
}
