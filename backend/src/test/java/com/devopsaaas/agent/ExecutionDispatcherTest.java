package com.devopsaaas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionDispatcherTest {

    @Test
    void capacity_isWorkersPlusQueue_andAReleasedSlotCanBeReused() {
        AgentProperties properties = new AgentProperties(10, 8, Duration.ofMinutes(5), 10, 1, 1, false);
        ExecutionDispatcher dispatcher = new ExecutionDispatcher(mock(AgentOrchestrator.class), properties,
                new SimpleMeterRegistry());

        Optional<ExecutionDispatcher.Reservation> first = dispatcher.tryReserve();
        Optional<ExecutionDispatcher.Reservation> second = dispatcher.tryReserve();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(dispatcher.tryReserve()).as("full: the API answers 503 before writing anything").isEmpty();
        first.get().release();
        first.get().release();
        assertThat(dispatcher.tryReserve()).isPresent();
        assertThat(dispatcher.tryReserve()).as("a double release frees one slot only").isEmpty();
        dispatcher.shutdown();
    }
}
