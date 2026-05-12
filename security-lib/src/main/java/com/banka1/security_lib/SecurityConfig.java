package com.banka1.security_lib;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//@Configuration
//@EnableWebSecurity
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * PR_19 C19.X: HS256 JwtDecoder bean. Spring Boot OAuth2 resource server
     * auto-config kreira JwtDecoder samo ako je konfigurisan issuer-uri /
     * jwk-set-uri / public-key-location. Posto banka koristi HS256 sa shared
     * secret-om (jwt.secret), moramo eksplicitno deklarisati bean.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value("${jwt.secret}") String secret) {
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        return org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
    }

    /**
     * Registruje password encoder koji se koristi za cuvanje i proveru lozinki.
     *
     * @return Argon2 password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
        ROLE_ADMIN > ROLE_SUPERVISOR
        ROLE_SUPERVISOR > ROLE_AGENT
        ROLE_AGENT > ROLE_BASIC
        ROLE_CLIENT_TRADING > ROLE_CLIENT_BASIC
    """);
    }

    @Bean
    public static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler =
                new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }



    @Bean
    @Order(1)
    public SecurityFilterChain authChain(HttpSecurity http,SecurityProperties props) throws Exception {
        http.securityMatcher(props.getPermitAll())
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http,
                                        JwtAuthenticationConverter converter) throws Exception {

        http.cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/employees/**").hasAnyRole("BASIC","ADMIN","SUPERVISOR","AGENT","SERVICE")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                );

        return http.build();
    }


    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(SecurityProperties props) {
        JwtGrantedAuthoritiesConverter rolesConverter =
                new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName(props.getRolesClaim());
        rolesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(rolesConverter.convert(jwt));
            List<String> permissions = jwt.getClaimAsStringList(props.getPermissionsClaim());
            if (permissions != null) {
                permissions.forEach(p ->
                        authorities.add(new SimpleGrantedAuthority(p))
                );
            }
            return authorities;
        });

        return converter;
    }
    /**
     * CORS bean koji čita SecurityProperties.cors blok. Default-i (localhost:4200, eksplicitne
     * HTTP metode i header-i) važe samo za lokalni dev. Production deploy MORA da postavi
     * BANKA_SECURITY_CORS_ALLOWED_ORIGINS env var na pravi frontend domen, inače će CORS
     * preflight iz produkcije fail-ovati. Wildcard "*" se nigde ne koristi za methods/headers
     * jer kombinacija sa allowCredentials=true otvara CSRF-like vektor.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        SecurityProperties.Cors corsProps = props.getCors();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProps.getAllowedOrigins());
        config.setAllowedMethods(corsProps.getAllowedMethods());
        config.setAllowedHeaders(corsProps.getAllowedHeaders());
        config.setExposedHeaders(corsProps.getExposedHeaders());
        config.setAllowCredentials(corsProps.isAllowCredentials());
        config.setMaxAge(corsProps.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
