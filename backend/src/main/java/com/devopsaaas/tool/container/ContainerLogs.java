package com.devopsaaas.tool.container;

import java.util.List;

public record ContainerLogs(String serviceName, List<LogLine> lines, boolean truncated) {

    public ContainerLogs {
        lines = List.copyOf(lines);
    }
}
