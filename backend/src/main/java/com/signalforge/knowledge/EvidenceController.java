package com.signalforge.knowledge;

import com.signalforge.platform.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{project}/knowledge")
public class EvidenceController {
    public record Fetch(@NotNull UUID catalogSnapshotId,@NotBlank @Pattern(regexp="AN[0-9]{10,40}") String providerId) {}
    public record Extract(boolean retryFailed) {}
    public record Review(@NotBlank String decision,@NotBlank @Size(min=3,max=500) String reason) {}
    public record Publish(@NotNull UUID catalogSnapshotId,@NotEmpty @Size(max=8) List<@NotNull UUID> candidateIds) {}
    private final ProjectAccess access;
    private final EvidenceStore store;
    private final ExtractionModel model;
    public EvidenceController(ProjectAccess access,EvidenceStore store,ExtractionModel model) { this.access=access;this.store=store;this.model=model; }
    private ResponseEntity<EvidenceStore.Job> created(EvidenceStore.Creation result) {
        return ResponseEntity.status(result.created()?202:200).body(result.job());
    }
    @PostMapping("/documents") ResponseEntity<EvidenceStore.Job> fetch(@PathVariable UUID project,Authentication auth,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Fetch request) {
        return created(store.fetch(project,access.require(project,auth,true),key,request.catalogSnapshotId(),request.providerId()));
    }
    @GetMapping("/releases/{release}/documents") List<EvidenceStore.Document> documents(@PathVariable UUID project,@PathVariable UUID release,Authentication auth) {
        access.require(project,auth,false);return store.documents(project,release);
    }
    @GetMapping("/documents/{id}") EvidenceStore.Document document(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);return store.document(project,id);
    }
    @GetMapping(value="/documents/{id}/pdf",produces="application/pdf") ResponseEntity<byte[]> pdf(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);var document=store.document(project,id);
        return ResponseEntity.ok().header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\"announcement-"+document.providerId()+".pdf\"").body(store.pdf(project,id));
    }
    @PostMapping("/documents/{id}/extractions") ResponseEntity<EvidenceStore.Job> extract(@PathVariable UUID project,@PathVariable UUID id,Authentication auth,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Extract request) {
        UUID user=access.require(project,auth,true);var prepared=model.prepare(store.document(project,id));
        return created(store.extract(project,user,key,id,prepared,request.retryFailed()));
    }
    @GetMapping("/documents/{id}/extractions") List<EvidenceStore.Run> extractions(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);return store.runs(project,id);
    }
    @GetMapping("/documents/{id}/candidates") List<EvidenceStore.Candidate> candidates(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);return store.candidates(project,id);
    }
    @PostMapping("/candidates/{id}/review") EvidenceStore.Candidate review(@PathVariable UUID project,@PathVariable UUID id,Authentication auth,@Valid @RequestBody Review request) {
        return store.review(project,access.require(project,auth,true),id,request.decision(),request.reason());
    }
    @PostMapping("/publications") ResponseEntity<EvidenceStore.Job> publish(@PathVariable UUID project,Authentication auth,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Publish request) {
        return created(store.publish(project,access.require(project,auth,true),key,request.catalogSnapshotId(),request.candidateIds()));
    }
    @GetMapping("/releases/{release}/publications") List<EvidenceStore.Publication> publications(@PathVariable UUID project,@PathVariable UUID release,Authentication auth) {
        access.require(project,auth,false);return store.publications(project,release);
    }
    @GetMapping("/evidence/jobs") List<EvidenceStore.Job> jobs(@PathVariable UUID project,Authentication auth) {
        access.require(project,auth,false);return store.jobs(project);
    }
    @GetMapping("/evidence/jobs/{id}") EvidenceStore.Job job(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,false);return store.get(project,id);
    }
    @PostMapping("/evidence/jobs/{id}/cancel") EvidenceStore.Job cancel(@PathVariable UUID project,@PathVariable UUID id,Authentication auth) {
        access.require(project,auth,true);return store.cancel(project,id);
    }
    @GetMapping("/evidence/budget") EvidenceStore.Budget budget(@PathVariable UUID project,Authentication auth) {
        access.require(project,auth,false);return store.budget(project);
    }
}
