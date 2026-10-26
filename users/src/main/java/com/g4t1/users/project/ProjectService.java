package com.g4t1.users.project;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final StorageClient storage;

    public ProjectService(ProjectRepository projects, StorageClient storage) {
        this.projects = projects;
        this.storage = storage;
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
        Project project = requireOwn(ownerId, projectId);
        String name = request.name().strip();
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a project called " + name);
        }
        project.rename(name);
        return ProjectResponse.from(projects.save(project));
    }

    // Storage first: if clearing its papers and draft fails, the project is still here to delete again
    public void delete(UUID ownerId, UUID projectId) {
        requireOwn(ownerId, projectId);
        try {
            storage.clearProject(ownerId, projectId);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Storage Management couldn't clear project " + projectId + ", so it wasn't deleted");
        }
        projects.deleteById(projectId);
    }

    public List<ProjectResponse> list(UUID ownerId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(ProjectResponse::from).toList();
    }

    // someone else's project gets the same 404 as a missing one, so an id never shows whose it is
    private Project requireOwn(UUID ownerId, UUID projectId) {
        return projects.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No project " + projectId));
    }
}
