package com.g4t1.users.project;

import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public ProjectResponse create(ProjectRequest request) {
        return ProjectResponse.from(projects.save(new Project(request.name())));
    }
}
