package com.g4t1.storage.chunk;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// The real Postgres with pgvector, the image production runs, for what H2 can't do: the vector(1536)
// column, its HNSW index and cosine distance. The tests are skipped when Docker is missing.
// One container for every test class, started once: Spring caches the application context across
// classes, so a container restarted per class would leave that context pointing at a stopped one.
@Testcontainers(disabledWithoutDocker = true)
abstract class PgvectorContainer {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
