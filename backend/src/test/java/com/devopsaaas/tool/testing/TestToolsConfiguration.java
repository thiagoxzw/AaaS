package com.devopsaaas.tool.testing;

import com.devopsaaas.tool.container.FakeContainerRuntime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
public class TestToolsConfiguration {

    @Bean
    FakeContainerRuntime fakeContainerRuntime() {
        return new FakeContainerRuntime();
    }

    @Bean
    TestTools.StatusTool statusTool(FakeContainerRuntime runtime) {
        return new TestTools.StatusTool(runtime);
    }

    @Bean
    TestTools.RiskyTool riskyTool(FakeContainerRuntime runtime) {
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
