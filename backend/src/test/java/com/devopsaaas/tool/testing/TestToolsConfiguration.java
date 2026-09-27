package com.devopsaaas.tool.testing;

import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test tools, and the fake runtime in place of the Docker adapter. Tests that exercise the real Docker
 * Engine through the proxy set {@value #REAL_RUNTIME_PROPERTY}=true, and then every tool (production and
 * test) runs against the real adapter.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestToolsConfiguration {

    public static final String REAL_RUNTIME_PROPERTY = "devops.test.real-runtime";

    @Bean
    @Primary
    @ConditionalOnProperty(name = REAL_RUNTIME_PROPERTY, havingValue = "false", matchIfMissing = true)
    FakeContainerRuntime fakeContainerRuntime() {
        return new FakeContainerRuntime();
    }

    @Bean
    TestTools.StatusTool statusTool(ContainerRuntime runtime) {
        return new TestTools.StatusTool(runtime);
    }

    @Bean
    TestTools.RiskyTool riskyTool(ContainerRuntime runtime) {
        return new TestTools.RiskyTool(runtime);
    }

    @Bean
    TestTools.FlakyTool flakyTool() {
        return new TestTools.FlakyTool();
    }

    @Bean
    TestTools.UnavailableTool unavailableTool() {
        return new TestTools.UnavailableTool();
    }

    @Bean
    TestTools.SlowReadTool slowReadTool() {
        return new TestTools.SlowReadTool();
    }

    @Bean
    TestTools.SlowWriteTool slowWriteTool() {
        return new TestTools.SlowWriteTool();
    }

    @Bean
    TestTools.LeakyTool leakyTool() {
        return new TestTools.LeakyTool();
    }

    @Bean
    TestTools.BigOutputTool bigOutputTool() {
        return new TestTools.BigOutputTool();
    }

    @Bean
    TestTools.CrashingTool crashingTool() {
        return new TestTools.CrashingTool();
    }

    @Bean
    TestTools.ProbeTool probeTool(JdbcTemplate jdbc) {
        return new TestTools.ProbeTool(jdbc);
    }
}
