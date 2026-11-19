package com.signalforge.research;

import com.signalforge.platform.ApiException;
import com.signalforge.finance.MarketSnapshots;
import com.signalforge.platform.ProjectAccess;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/projects/{project}/research/tasks")
public class ResearchController {
    private final ProjectAccess access;
    private final RequestPolicy policy;
    private final TaskStore store;
    private final TaskEvents events;
    private final MarketSnapshots snapshots;
    public ResearchController(ProjectAccess access, RequestPolicy policy, TaskStore store, TaskEvents events, MarketSnapshots snapshots) {
        this.access=access; this.policy=policy; this.store=store; this.events=events; this.snapshots=snapshots;
    }
    @PostMapping ResponseEntity<TaskStore.Task> create(@PathVariable UUID project, Authentication auth,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ResearchRequest request) {
        UUID user=access.require(project,auth,true); policy.validate(request);
        var created=store.create(project,user,key,request);
        return ResponseEntity.status(created.created()?202:200).body(created.task());
    }
    @GetMapping List<TaskStore.Task> list(@PathVariable UUID project, Authentication auth,
            @RequestParam(required=false) String researchType) {
        access.require(project,auth,false);
        if (researchType!=null && !List.of("ETF_RISK_COMPARISON","KNOWLEDGE_QA").contains(researchType))
            throw new ApiException(400,"UNSUPPORTED_RESEARCH","无效的研究任务类型");
        return store.list(project,researchType);
    }
    @GetMapping("/{id}") TaskStore.Task get(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false); return store.get(project,id);
    }
    @PostMapping("/{id}/cancel") TaskStore.Task cancel(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,true); return store.cancel(project,id);
    }
    @GetMapping("/{id}/result") JsonNode result(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false); return store.result(project,id);
    }
    @GetMapping("/{id}/snapshots") List<JsonNode> snapshots(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false); store.requireResultReady(project,id); return snapshots.forTask(project,id);
    }
    @GetMapping(value="/{id}/events",produces="text/event-stream")
    SseEmitter events(@PathVariable UUID project,@PathVariable UUID id,Authentication auth,
            @RequestHeader(value="Last-Event-ID",required=false) String cursor) {
        access.require(project,auth,false);
        long after=0;
        if (cursor!=null) try { after=Long.parseLong(cursor); }
        catch (NumberFormatException e) { throw new ApiException(400,"INVALID_EVENT_CURSOR","事件序号必须为整数"); }
        store.validateCursor(project,id,after);
        return events.subscribe(project,id,after,auth);
    }
}
