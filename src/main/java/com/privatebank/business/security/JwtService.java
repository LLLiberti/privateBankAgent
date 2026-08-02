package com.privatebank.business.security;

import com.privatebank.business.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public IssuedToken issue(String userId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.ttlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(userId)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public boolean shouldRenew(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        return expiresAt != null && expiresAt.isBefore(
                Instant.now().plus(properties.renewalThresholdMinutes(), ChronoUnit.MINUTES));
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
