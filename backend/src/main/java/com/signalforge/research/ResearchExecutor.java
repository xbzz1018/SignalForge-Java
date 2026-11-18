package com.signalforge.research;

public interface ResearchExecutor {
    String researchType();
    String toolName();
    String execute(TaskStore.Claim claim) throws Exception;
    void verify(ResearchRequest request, String response);
}
