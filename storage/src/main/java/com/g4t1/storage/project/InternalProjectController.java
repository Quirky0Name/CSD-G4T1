package com.g4t1.storage.project;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// service-token only; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
@RequestMapping("/internal/projects")
public class InternalProjectController {

    private final ProjectService projects;

    public InternalProjectController(ProjectService projects) {
        this.projects = projects;
    }

    // User Management calls this before deleting a project, so the project's papers and draft go with it
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID projectId, @RequestParam(name = "owner_id") UUID ownerId) {
        projects.clear(ownerId, projectId);
    }
}
