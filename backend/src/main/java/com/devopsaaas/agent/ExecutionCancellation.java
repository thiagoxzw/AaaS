package com.devopsaaas.agent;

import com.devopsaaas.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** RF-23: the requester cancels an active execution. */
@Service
public class ExecutionCancellation {

    private final ExecutionJournal journal;
    private final ExecutionQueries queries;

    ExecutionCancellation(ExecutionJournal journal, ExecutionQueries queries) {
        this.journal = journal;
        this.queries = queries;
    }

    public ExecutionView cancel(CurrentUser user, UUID executionId) {
        return queries.view(journal.cancel(user, executionId));
    }
}
