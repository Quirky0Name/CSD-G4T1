package com.g4t1.users.project;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public ProjectResponse create(ProjectRequest request) {
        return ProjectResponse.from(projects.save(new Project(request.name())));
    }

    public List<ProjectResponse> list() {
        return projects.findAllByOrderByCreatedAtDesc().stream().map(ProjectResponse::from).toList();
    }
}
