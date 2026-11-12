package com.g4t1.storage.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokensTest {

    // a 64-byte secret, which would give HS512 if the key's length picked the algorithm
    @Test
    void aLongSecretStillSignsWithHs256() {
        byte[] secret = new byte[64];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i + 1);
        }
        String token = new ServiceTokens(Encoders.BASE64.encode(secret)).mint();

        var parsed = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(Encoders.BASE64.encode(secret))))
                .build().parseSignedClaims(token);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("svc:storage");
    }
}
