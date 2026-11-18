package com.signalforge.knowledge;

import com.signalforge.platform.ProjectAccess;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{project}/knowledge")
public class KnowledgeQueryController {
    private final ProjectAccess access;
    private final KnowledgeQuery query;
    public KnowledgeQueryController(ProjectAccess access, KnowledgeQuery query) {
        this.access=access; this.query=query;
    }
    @PostMapping("/query")
    ResponseEntity<KnowledgeQuery.Result> query(@PathVariable UUID project, Authentication auth,
                                               @Valid @RequestBody KnowledgeQuery.Request request) {
        access.require(project,auth,false);
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(query.query(project,request));
    }
}
