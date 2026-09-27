package com.devopsaaas.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure scenarios for the demo (docs/07-plano-do-mvp.md, slice 3). Each one produces a state the agent's
 * tools can observe: an unhealthy or hanging healthcheck, an exit code, a controlled log line.
 */
@RestController
class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final int MAX_LOG_MESSAGE = 500;

    private final ChaosState state;
    /** Held on purpose until the heap is full; see {@link #oom()}. */
    private final List<long[]> heapHog = Collections.synchronizedList(new ArrayList<>());

    ChaosController(ChaosState state) {
        this.state = state;
    }

    @GetMapping("/")
    Map<String, String> hello() {
        log.info("request ok path=/");
        return Map.of("service", "demo-api", "status", state.unhealthy() ? "degraded" : "ok");
    }

    @PostMapping("/chaos/unhealthy")
    Map<String, String> unhealthy() {
        state.makeUnhealthy();
        log.error("chaos: health indicator forced DOWN");
        return Map.of("chaos", "unhealthy");
    }

    @PostMapping("/chaos/hang")
    Map<String, String> hang() {
        state.makeHang();
        log.error("chaos: health indicator will hang");
        return Map.of("chaos", "hang");
    }

    @PostMapping("/chaos/recover")
    Map<String, String> recover() {
        state.recover();
        log.info("chaos: recovered");
        return Map.of("chaos", "recovered");
    }

    /** Exits with the given code shortly after answering, so the caller still gets a response. */
    @PostMapping("/chaos/crash")
    ResponseEntity<Map<String, Object>> crash(@RequestParam(defaultValue = "1") int code) {
        int exitCode = Math.clamp(code, 1, 255);
        log.error("chaos: exiting with code {}", exitCode);
        CompletableFuture.runAsync(() -> Runtime.getRuntime().halt(exitCode),
                CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
        return ResponseEntity.accepted().body(Map.of("chaos", "crash", "exitCode", exitCode));
    }

    /**
     * Fills the heap. The image runs the JVM with -XX:+ExitOnOutOfMemoryError, so the process exits with
     * code 3. This is not a kernel OOM kill: Docker reports OOMKilled=false (documented limitation).
     */
    @PostMapping("/chaos/oom")
    ResponseEntity<Map<String, String>> oom() {
        log.error("chaos: filling the heap");
        CompletableFuture.runAsync(() -> {
            while (true) {
                heapHog.add(new long[1024 * 1024]);
            }
        });
        return ResponseEntity.accepted().body(Map.of("chaos", "oom"));
    }

    /**
     * Writes one controlled line to the log, for the prompt-injection scenarios: the agent must treat log
     * content as data. Line breaks are replaced, so a request cannot forge extra log lines.
     */
    @PostMapping("/chaos/log")
    Map<String, String> logLine(@RequestParam String msg) {
        String line = msg.replace('\r', ' ').replace('\n', ' ');
        if (line.length() > MAX_LOG_MESSAGE) {
            line = line.substring(0, MAX_LOG_MESSAGE);
        }
        log.warn("chaos log: {}", line);
        return Map.of("chaos", "log");
    }
}
