package com.g4t1.users.project;

import com.g4t1.users.auth.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

// Storage Management's internal clear-a-project and clear-a-user calls (docs/CONTRACTS.md)
@Component
public class StorageClient {

    private final RestClient rest;
    private final TokenService tokens;

    public StorageClient(RestClient.Builder builder, @Value("${users.storage-url}") String baseUrl,
                         TokenService tokens) {
        this.rest = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
    }

    public void clearProject(UUID ownerId, UUID projectId) {
        rest.delete()
                .uri(uri -> uri.path("/internal/projects/{id}").queryParam("owner_id", ownerId).build(projectId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueServiceToken())
                .retrieve()
                .toBodilessEntity();
    }

    public void clearUser(UUID userId) {
        rest.delete()
                .uri("/internal/users/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueServiceToken())
                .retrieve()
                .toBodilessEntity();
    }
}
