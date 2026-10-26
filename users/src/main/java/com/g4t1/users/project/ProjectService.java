package com.g4t1.users.project;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public ProjectResponse create(UUID ownerId, ProjectRequest request) {
        // the spaces around a name are typing noise, not part of it
        String name = request.name().strip();
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a project called " + name);
        }
        return ProjectResponse.from(projects.save(new Project(ownerId, name)));
    }

    public ProjectResponse rename(UUID ownerId, UUID projectId, ProjectRequest request) {
        String name = request.name().strip();
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a project called " + name);
        }
        Project project = projects.findById(projectId).orElseThrow();
        project.rename(name);
        return ProjectResponse.from(projects.save(project));
    }

    public List<ProjectResponse> list(UUID ownerId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(ProjectResponse::from).toList();
    }
}
