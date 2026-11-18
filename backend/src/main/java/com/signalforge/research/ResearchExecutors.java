package com.signalforge.research;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ResearchExecutors {
    private final Map<String,ResearchExecutor> executors;
    public ResearchExecutors(List<ResearchExecutor> executors) {
        this.executors=executors.stream().collect(Collectors.toUnmodifiableMap(
                ResearchExecutor::researchType, Function.identity()));
    }
    public ResearchExecutor forType(String type) {
        var executor=executors.get(type);
        if (executor==null) throw new IllegalArgumentException("UNSUPPORTED_RESEARCH");
        return executor;
    }
}
