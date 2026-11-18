package com.signalforge.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Separate public DTO: knowledge questions never require fabricated price dates. */
public record KnowledgeQuestion(@NotBlank @Size(max=9) String securityId,
                                @NotBlank @Size(max=1000) String question, UUID releaseId) {}
