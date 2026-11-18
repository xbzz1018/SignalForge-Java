package com.signalforge.knowledge;

import com.signalforge.platform.ProjectAccess;
import com.signalforge.research.ResearchRequest;
import com.signalforge.research.TaskStore;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{project}/knowledge/questions")
public class KnowledgeQuestionController {
    private final ProjectAccess access;
    private final TaskStore tasks;
    public KnowledgeQuestionController(ProjectAccess access, TaskStore tasks) {
        this.access=access; this.tasks=tasks;
    }
    @PostMapping
    ResponseEntity<TaskStore.Task> create(@PathVariable UUID project, Authentication auth,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody KnowledgeQuestion request) {
        UUID user=access.require(project,auth,true);
        var question=new KnowledgeQuestion(KnowledgeEvidence.security(request.securityId()),
                request.question().strip(),request.releaseId());
        var result=tasks.create(project,user,key,ResearchRequest.forQuestion(question));
        return ResponseEntity.status(result.created()?202:200).header("Cache-Control","private, no-store")
                .body(result.task());
    }
}
