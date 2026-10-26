package com.g4t1.users.project;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ProjectRequest request) {
        return projects.create(userId, request);
    }

    @GetMapping
    public ProjectListResponse list(@AuthenticationPrincipal UUID userId) {
        return new ProjectListResponse(projects.list(userId));
    }

    @PutMapping("/{projectId}")
    public ProjectResponse rename(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                  @Valid @RequestBody ProjectRequest request) {
        return projects.rename(userId, projectId, request);
    }
}
