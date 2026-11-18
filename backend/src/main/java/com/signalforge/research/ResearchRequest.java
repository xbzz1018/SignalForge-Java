package com.signalforge.research;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record ResearchRequest(
        @NotNull @Pattern(regexp="1\\.0") String schemaVersion,
        @NotNull String researchType,
        @NotNull @Size(min=2,max=2) List<@NotNull @Valid Security> securities,
        @NotNull OffsetDateTime knowledgeAsOf,
        @NotNull @Valid PriceRange priceRange,
        @NotNull String priceAdjustment,
        @NotNull @Size(min=1,max=3) List<@NotNull Metric> metrics,
        @NotNull String executionMode,
        @Null @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.signalforge.knowledge.KnowledgeQuestion question) {
    // Preserve the deterministic public contract and existing callers.
    public ResearchRequest(String schemaVersion,String researchType,List<Security> securities,
            OffsetDateTime knowledgeAsOf,PriceRange priceRange,String priceAdjustment,List<Metric> metrics,String executionMode) {
        this(schemaVersion,researchType,securities,knowledgeAsOf,priceRange,priceAdjustment,metrics,executionMode,null);
    }
    public static ResearchRequest forQuestion(com.signalforge.knowledge.KnowledgeQuestion question) {
        return new ResearchRequest("1.0","KNOWLEDGE_QA",List.of(),null,null,null,List.of(),"AGENT",question);
    }
    public enum Metric { TOTAL_RETURN, ANNUALIZED_VOLATILITY, MAX_DRAWDOWN }
    public record Security(@NotBlank @Size(max=128) String securityId, @NotBlank String market) {}
    public record PriceRange(@NotNull LocalDate start, @NotNull LocalDate end, @NotNull String frequency) {}
}
