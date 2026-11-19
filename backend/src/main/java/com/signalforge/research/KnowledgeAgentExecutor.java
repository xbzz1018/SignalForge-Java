package com.signalforge.research;

import com.signalforge.infrastructure.BoundedHttpBody;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.knowledge.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reuses PI Agent over a fixed local endpoint; browser credentials never leave Java. */
@Component
public class KnowledgeAgentExecutor implements ResearchExecutor {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private final KnowledgeQuery query;
    private final AgentRunStore runs;
    private final JsonCodec json;
    private final URI endpoint;
    private final String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public KnowledgeAgentExecutor(KnowledgeQuery query, AgentRunStore runs, JsonCodec json,
            @Value("${signalforge.agent-url}") String url, @Value("${signalforge.agent-token}") String token) {
        this.query=query; this.runs=runs; this.json=json; this.token=token;
        URI base=URI.create(url);
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) ||
                base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null ||
                !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Agent endpoint must be a fixed loopback HTTP origin");
        endpoint=base.resolve("/api/internal/knowledge-agent");
    }
    @Override public String researchType() { return "KNOWLEDGE_QA"; }
    @Override public String toolName() { return "pi_knowledge_agent"; }
    @Override public String execute(TaskStore.Claim c) throws Exception {
        if (!runs.active(c)) throw AgentRunStore.failure("AGENT_EXECUTION_REVOKED");
        var saved=runs.find(c.task().id());
        if (saved!=null) {
            if (!saved.state().equals("RETURNED")) throw AgentRunStore.failure("AGENT_OUTCOME_UNKNOWN");
            return answer(json.read(saved.input(),KnowledgeAnswer.Input.class),saved.response());
        }
        var question=c.task().request().question();
        var evidence=query.query(c.task().projectId(),new KnowledgeQuery.Request(question.securityId(),question.releaseId(),null,5));
        var input=new KnowledgeAnswer.Input("1.0",c.task().id(),c.task().projectId(),question,KnowledgeAnswer.PROMPT_VERSION,evidence);
        String inputJson = json.write(input);
        if (evidence.claims().isEmpty()) {
            runs.begin(c,inputJson,0);
            runs.returned(c,"{}",0,true);
            return json.write(KnowledgeAnswer.empty(input));
        }
        if (token.length()<32) throw AgentRunStore.failure("AGENT_NOT_CONFIGURED");
        runs.begin(c,inputJson,65_536);
        try {
            String response=invoke(c,inputJson);
            var body=json.tree(response);
            // Account even an unsuccessful/cancelled run when the provider reported usage.
            var usage=KnowledgeAnswer.usage(body);
            runs.returned(c,response,usage.totalTokens(),usage.source().equals("provider") && body.path("status").asText().equals("COMPLETED"));
            return answer(input,response);
        } catch (Exception e) {
            runs.unknown(c);
            throw e;
        }
    }
    private String invoke(TaskStore.Claim c,String input) throws Exception {
        var request=HttpRequest.newBuilder(endpoint).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(75)).header("Content-Type","application/json")
                .header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(input)).build();
        var pending=client.sendAsync(request, info -> new BoundedHttpBody(MAX_RESPONSE_BYTES));
        try {
            while (true) {
                if (!runs.active(c)) throw AgentRunStore.failure("AGENT_EXECUTION_REVOKED");
                try {
                    var response=pending.get(500,TimeUnit.MILLISECONDS);
                    if (response.statusCode()!=200)
                        throw AgentRunStore.failure("AGENT_SERVICE_UNAVAILABLE");
                    return new String(response.body(),java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.util.concurrent.ExecutionException failed) {
                    var error = AgentRunStore.failure("AGENT_SERVICE_UNAVAILABLE");
                    error.initCause(failed.getCause());
                    throw error;
                } catch (TimeoutException waiting) { /* Poll ownership while HTTP is in flight. */ }
            }
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    private String answer(KnowledgeAnswer.Input input,String raw) {
        if (input.evidence().claims().isEmpty()) return json.write(KnowledgeAnswer.empty(input));
        var body=json.tree(raw);
        if (!body.path("status").asText().equals("COMPLETED")) throw AgentRunStore.failure("AGENT_RUN_INCOMPLETE");
        return json.write(KnowledgeAnswer.accept(input,body));
    }
    @Override public void verify(ResearchRequest request,String response) {
        UUID task=UUID.fromString(json.tree(response).path("taskId").asText());
        var saved=runs.find(task);
        if (saved==null || !saved.state().equals("RETURNED")) throw AgentRunStore.failure("AGENT_RESPONSE_REJECTED");
        var input=json.read(saved.input(),KnowledgeAnswer.Input.class);
        if (!input.question().equals(request.question()) || !answer(input,saved.response()).equals(response))
            throw AgentRunStore.failure("AGENT_RESPONSE_REJECTED");
    }
}
