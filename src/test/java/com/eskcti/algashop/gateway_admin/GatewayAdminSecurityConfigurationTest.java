package com.eskcti.algashop.gateway_admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"eureka.client.enabled=false",
				"spring.cloud.discovery.enabled=false",
				"management.health.redis.enabled=false"
		})
class GatewayAdminSecurityConfigurationTest {

	private static final String TOKEN = "token";

	private static final Jwt JWT = Jwt.withTokenValue(TOKEN)
			.header("alg", "none")
			.subject("admin-1")
			.claim("scope", "products:read categories:read")
			.build();

	@LocalServerPort
	private int port;

	@MockitoBean
	private ReactiveJwtDecoder jwtDecoder;

	@MockitoBean
	private RedisRateLimiter redisRateLimiter;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		Mockito.when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(JWT));
		Mockito.when(redisRateLimiter.isAllowed(Mockito.anyString(), Mockito.anyString()))
				.thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
		Mockito.when(redisRateLimiter.getConfigClass()).thenReturn(RedisRateLimiter.Config.class);
		Mockito.when(redisRateLimiter.newConfig()).thenReturn(new RedisRateLimiter.Config());
		Mockito.when(redisRateLimiter.getConfig()).thenReturn(new ConcurrentHashMap<>());
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void shouldAllowHealthEndpointWithoutAuthentication() {
		webTestClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk();
	}

	@Test
	void shouldRequireAuthenticationForApiRoutes() {
		webTestClient.get()
				.uri("/api/v1/products")
				.exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void shouldAllowAuthenticatedRequestsToApiRoutes() {
		webTestClient.get()
				.uri("/api/v1/products")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void shouldDenyAnyOtherExchangeForAuthenticatedUsers() {
		webTestClient.get()
				.uri("/any-other-path")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void shouldAllowCorsPreflightRequestsFromAdminApp() {
		webTestClient.options()
				.uri("/api/v1/products")
				.header(HttpHeaders.ORIGIN, "http://admin.algashop.local:4200")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
						"http://admin.algashop.local:4200");
	}

	@Test
	void shouldRejectCorsPreflightRequestsFromUnknownOrigin() {
		webTestClient.options()
				.uri("/api/v1/products")
				.header(HttpHeaders.ORIGIN, "http://evil.example.com")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.exchange()
				.expectStatus().isForbidden();
	}

}
