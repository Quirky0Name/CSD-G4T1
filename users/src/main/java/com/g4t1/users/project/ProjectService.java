package com.g4t1.users.project;

import com.g4t1.users.auth.AccountGoneException;
import com.g4t1.users.auth.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final UserRepository users;

    public ProjectService(ProjectRepository projects, StorageClient storage, UserRepository users) {
        this.projects = projects;
        this.storage = storage;
        this.users = users;
    }

    public ProjectResponse create(UUID ownerId, ProjectRequest request) {
        // otherwise the insert breaks the owner foreign key and comes back as a misleading 409
        if (!users.existsById(ownerId)) {
            throw new AccountGoneException();
        }
        String name = nameOf(request);
        requireFree(ownerId, name);
        return saveNamed(new Project(ownerId, name), name);
    }

    public List<ProjectResponse> list(UUID ownerId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(ProjectResponse::from).toList();
    }

    public ProjectResponse rename(UUID ownerId, UUID projectId, ProjectRequest request) {
        Project project = requireOwn(ownerId, projectId);
        String name = nameOf(request);
        // keeping its own name isn't a clash with itself
        if (name.equals(project.getName())) {
            return ProjectResponse.from(project);
        }
        requireFree(ownerId, name);
        project.rename(name);
        return saveNamed(project, name);
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

    // the spaces around a name are typing noise, not part of it
    private static String nameOf(ProjectRequest request) {
        return request.name().strip();
    }

    private void requireFree(UUID ownerId, String name) {
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            throw nameTaken(name);
        }
    }

    // another create or rename can take the name between the check and this save; the unique constraint catches it
    private ProjectResponse saveNamed(Project project, String name) {
        try {
            return ProjectResponse.from(projects.saveAndFlush(project));
        } catch (DataIntegrityViolationException e) {
            throw nameTaken(name);
        }
    }

    private static ResponseStatusException nameTaken(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "You already have a project called " + name);
    }

    // someone else's project gets the same 404 as a missing one, so an id never shows whose it is
    private Project requireOwn(UUID ownerId, UUID projectId) {
        return projects.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No project " + projectId));
    }
}
