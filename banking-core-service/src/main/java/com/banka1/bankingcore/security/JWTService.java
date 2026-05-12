package com.banka1.bankingcore.security;

/**
 * Service-to-service JWT generator za banking-core (PR_14 C14.4).
 *
 * <p>Generise potpisan token sa role=SERVICE koji RestClient interceptor
 * postavlja na Authorization header pri pozivima ka account-service-u
 * (i drugim internim servisima u buducnosti).
 */
public interface JWTService {

    String generateJwtToken();
}
