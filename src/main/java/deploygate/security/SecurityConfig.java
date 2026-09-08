package deploygate.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bearer-token security for the whole API.
 *
 * <p>The rule that matters most: approving or rejecting requires a personal deployer
 * token ({@code ROLE_DEPLOYER}). The CI service token can ask for decisions but can
 * never cast an approval vote, so a compromised pipeline secret cannot self-approve
 * a production deploy.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Claim required to read the admin views and the audit log. */
    public static final String ADMIN_READ_CLAIM = "admin:read";

    private final String[] allowedOrigins;

    public SecurityConfig(@Value("${deploygate.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiTokenAuthFilter apiTokenAuthFilter)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        return http
                // No cookies or sessions are used, so there is no CSRF vector to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Liveness/readiness must stay reachable by orchestrators and CI.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Casting a vote requires a proven personal identity.
                        .requestMatchers("/approval/*/approve", "/approval/*/reject").hasRole(CallerType.DEPLOYER.name())
                        .requestMatchers("/admin/**", "/approval/history").hasAuthority(ADMIN_READ_CLAIM)
                        .anyRequest().authenticated())
                .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "valid API token required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(objectMapper, response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "token lacks the permission required for this endpoint")))
                .build();
    }

    /** Matches the {result, message} error shape used by GlobalExceptionHandler. */
    private void writeError(ObjectMapper objectMapper, HttpServletResponse response,
                            int status, String result, String message) throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("message", message);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/approval/**", configuration);
        source.registerCorsConfiguration("/admin/**", configuration);
        return source;
    }
}
