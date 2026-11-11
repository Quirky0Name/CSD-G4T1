package com.g4t1.storage.chunk;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// The real Postgres with pgvector, the image production runs, for what H2 can't do: the vector(1536)
// column, its HNSW index and cosine distance. The tests are skipped when Docker is missing.
@Testcontainers(disabledWithoutDocker = true)
abstract class PgvectorContainer {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
}
