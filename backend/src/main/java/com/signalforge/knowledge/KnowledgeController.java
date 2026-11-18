package com.signalforge.knowledge;

import com.signalforge.platform.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{project}/knowledge")
public class KnowledgeController {
    public record Request(@NotBlank @Size(max=9) String securityId) {}
    private final ProjectAccess access;
    private final KnowledgeStore store;
    private final GraphProjection projection;
    private final ModelConnection model;
    public KnowledgeController(ProjectAccess access,KnowledgeStore store,GraphProjection projection,ModelConnection model) {
        this.access=access; this.store=store; this.projection=projection; this.model=model;
    }
    @PostMapping("/collections") ResponseEntity<KnowledgeStore.Job> create(@PathVariable UUID project,Authentication auth,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Request request) {
        UUID user=access.require(project,auth,true);
        var created=store.create(project,user,key,KnowledgeEvidence.security(request.securityId()));
        return ResponseEntity.status(created.created()?202:200).body(created.job());
    }
    @GetMapping("/collections") List<KnowledgeStore.Job> list(@PathVariable UUID project,Authentication auth) {
        access.require(project,auth,false); return store.list(project);
    }
    @GetMapping("/collections/{id}") KnowledgeStore.Job get(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false); return store.get(project,id);
    }
    @PostMapping("/collections/{id}/cancel") KnowledgeStore.Job cancel(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,true); return store.cancel(project,id);
    }
    @GetMapping("/releases/{id}") KnowledgeEvidence.Graph graph(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false); return projection.read(store.snapshot(project,id));
    }
    @GetMapping(value="/releases/{id}/source",produces="application/json")
    ResponseEntity<String> source(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);
        var snapshot=store.snapshot(project,id);
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(snapshot.payload());
    }
    @GetMapping("/model") ModelConnection.Status model(@PathVariable UUID project,Authentication auth) {
        access.require(project,auth,false); return model.status();
    }
    @PostMapping("/model/probe") ModelConnection.Status probe(@PathVariable UUID project,Authentication auth) {
        access.require(project,auth,true); return model.probe();
    }
}
