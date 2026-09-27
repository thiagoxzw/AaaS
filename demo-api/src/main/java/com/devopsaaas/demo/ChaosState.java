package com.devopsaaas.demo;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** What the chaos endpoints have broken so far. */
@Component
class ChaosState {

    private final AtomicBoolean unhealthy = new AtomicBoolean();
    private final AtomicBoolean hanging = new AtomicBoolean();

    boolean unhealthy() {
        return unhealthy.get();
    }

    boolean hanging() {
        return hanging.get();
    }

    void makeUnhealthy() {
        unhealthy.set(true);
    }

    void makeHang() {
        hanging.set(true);
    }

    void recover() {
        unhealthy.set(false);
        hanging.set(false);
    }
}
