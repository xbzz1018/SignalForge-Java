package com.signalforge.knowledge;

import com.signalforge.research.AgentRunStore;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** The model selects citations; factual wording is rendered from authoritative source text. */
public final class KnowledgeAnswer {
    public static final String PROMPT_VERSION="knowledge-qa-1";
    public record Input(String schemaVersion, UUID taskId, UUID projectId, KnowledgeQuestion question,
                        String promptVersion, KnowledgeQuery.Result evidence) {}
    public record Statement(String citationId, String modality, String text) {}
    public record Usage(int inputTokens, int outputTokens, int totalTokens, String source) {}
    public record Report(String schemaVersion, UUID taskId, UUID projectId, String securityId, String question,
                         String status, List<Statement> statements, KnowledgeQuery.Result evidence,
                         String provider, String model, String promptVersion, int turns, Usage usage) {}
    private KnowledgeAnswer() {}
    public static Report empty(Input input) {
        return report(input,List.of(),"none","none",0,new Usage(0,0,0,"none"));
    }
    public static Report accept(Input input, JsonNode response) {
        require(response.path("schemaVersion").asText().equals("1.0") &&
                response.path("taskId").asText().equals(input.taskId().toString()) &&
                response.path("promptVersion").asText().equals(PROMPT_VERSION));
        require(response.path("status").asText().equals("COMPLETED") && response.path("toolObserved").isBoolean() && response.path("toolObserved").asBoolean());
        var ids=response.path("citationIds"); var observed=response.path("observedCitationIds");
        require(ids.isArray() && ids.size()<=5 && observed.isArray() && observed.size()<=5);
        Set<String> available=new HashSet<>(), seen=new HashSet<>();
        input.evidence().claims().forEach(c->available.add(c.id()));
        for (var id:observed) require(id.isString() && available.contains(id.asText()) && seen.add(id.asText()));
        var selected=new ArrayList<String>();
        for (var id:ids) require(id.isString() && seen.contains(id.asText()) && !selected.contains(id.asText()) && selected.add(id.asText()));
        String provider=response.path("provider").asText(), model=response.path("model").asText();
        require(provider.matches("[A-Za-z0-9_-]{1,40}") && !model.isBlank() && model.length()<=100);
        var turnCount = response.path("turns");
        require(turnCount.isIntegralNumber() && turnCount.canConvertToInt());
        int turns = turnCount.asInt();
        require(turns >= 1 && turns <= 3);
        return report(input,selected,provider,model,turns,usage(response));
    }
    public static Usage usage(JsonNode response) {
        var u=response.path("usage");
        for (String field:List.of("inputTokens","outputTokens","totalTokens"))
            require(u.path(field).isIntegralNumber() && u.path(field).asLong(-1)>=0 && u.path(field).asLong()<=1_000_000);
        int in=u.path("inputTokens").asInt(), out=u.path("outputTokens").asInt(), total=u.path("totalTokens").asInt();
        String source=response.path("usageSource").asText();
        require(total==in+out && Set.of("provider","estimated","cache_estimated","mixed","partial").contains(source));
        return new Usage(in,out,total,source);
    }
    private static Report report(Input input,List<String> ids,String provider,String model,int turns,Usage usage) {
        var statements=new ArrayList<Statement>();
        for (String id:ids) {
            var citation=input.evidence().claims().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->AgentRunStore.failure("AGENT_CITATION_REJECTED"));
            statements.add(new Statement(id,citation.modality(),citation.quote()));
        }
        return new Report("1.0",input.taskId(),input.projectId(),input.question().securityId(),input.question().question(),
                statements.isEmpty()?"INSUFFICIENT_EVIDENCE":"ANSWERED",List.copyOf(statements),input.evidence(),provider,model,PROMPT_VERSION,turns,usage);
    }
    private static void require(boolean valid) {
        if (!valid) throw AgentRunStore.failure("AGENT_RESPONSE_REJECTED");
    }
}
