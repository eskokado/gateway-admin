package com.eskcti.algashop.gateway_admin;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayAdminRoutesConfigurationTest {

	@Autowired
	private GatewayProperties gatewayProperties;

	@Autowired
	private GlobalCorsProperties globalCorsProperties;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	private TimeLimiterRegistry timeLimiterRegistry;

	@Autowired
	private Environment environment;

	@Test
	void shouldRouteCategoriesToProductCatalog() {
		RouteDefinition route = route("categories-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://product-catalog"));
		assertThat(predicate(route, "Path").getArgs().values())
				.contains("/api/v1/categories", "/api/v1/categories/**");
		assertThat(predicate(route, "Method").getArgs().values())
				.containsExactlyInAnyOrder("GET", "POST", "PUT");
	}

	@Test
	void shouldRouteProductsImagesAndUploadRequestsToProductCatalog() {
		RouteDefinition route = route("products-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://product-catalog"));
		assertThat(predicate(route, "Path").getArgs().values())
				.contains("/api/v1/products", "/api/v1/products/**",
						"/api/v1/upload-requests", "/api/v1/upload-requests/**");
		assertThat(predicate(route, "Method").getArgs().values())
				.containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE");
	}

	@Test
	void shouldRouteCustomersAndOrdersToOrdering() {
		RouteDefinition customersRoute = route("customers-route");
		RouteDefinition ordersRoute = route("orders-route");

		assertThat(customersRoute.getUri()).isEqualTo(URI.create("lb://ordering"));
		assertThat(predicate(customersRoute, "Path").getArgs().values())
				.contains("/api/v1/customers", "/api/v1/customers/**");
		assertThat(ordersRoute.getUri()).isEqualTo(URI.create("lb://ordering"));
		assertThat(predicate(ordersRoute, "Path").getArgs().values())
				.contains("/api/v1/orders", "/api/v1/orders/**");
	}

	@Test
	void shouldRouteOrderInvoiceToBillingBeforeOrdersRoute() {
		RouteDefinition route = route("order-invoice-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://billing"));
		assertThat(route.getOrder()).isEqualTo(-1);
		assertThat(predicate(route, "Path").getArgs().values())
				.contains("/api/v1/orders/{orderId}/invoice");
		assertThat(predicate(route, "Method").getArgs().values())
				.containsExactly("GET");
	}

	@Test
	void shouldRouteUsersToAuthorizationServer() {
		RouteDefinition route = route("users-route");

		assertThat(route.getUri()).isEqualTo(URI.create("lb://authorization-server"));
		assertThat(predicate(route, "Path").getArgs().values())
				.contains("/api/v1/users", "/api/v1/users/**");
		assertThat(predicate(route, "Method").getArgs().values())
				.containsExactlyInAnyOrder("GET", "POST", "PUT");
	}

	@Test
	void shouldConfigureOrderingRoutesWithCircuitBreaker() {
		FilterDefinition customersCircuitBreaker = filter(route("customers-route"), "CircuitBreaker");
		FilterDefinition ordersCircuitBreaker = filter(route("orders-route"), "CircuitBreaker");

		assertThat(customersCircuitBreaker.getArgs())
				.containsEntry("name", "ordering-route-cb")
				.containsEntry("statusCodes.0", "502")
				.containsEntry("statusCodes.1", "504")
				.containsEntry("statusCodes.2", "500");
		assertThat(ordersCircuitBreaker.getArgs())
				.containsEntry("name", "ordering-route-cb");
	}

	@Test
	void shouldConfigureResilienceDefaultFilters() {
		assertThat(gatewayProperties.getDefaultFilters()).extracting(FilterDefinition::getName)
				.contains("DedupeResponseHeader", "Retry", "RequestRateLimiter");

		FilterDefinition retry = defaultFilter("Retry");

		assertThat(retry.getArgs())
				.containsEntry("retries", "3")
				.containsEntry("statuses", "BAD_GATEWAY, INTERNAL_SERVER_ERROR, GATEWAY_TIMEOUT")
				.containsEntry("methods", "GET,PUT")
				.containsEntry("backoff.firstBackoff", "10ms")
				.containsEntry("backoff.maxBackoff", "20ms")
				.containsEntry("backoff.factor", "2")
				.containsEntry("backoff.basedOnPreviousValue", "false");

		FilterDefinition rateLimiter = defaultFilter("RequestRateLimiter");

		assertThat(rateLimiter.getArgs())
				.containsEntry("redis-rate-limiter.replenishRate", "50")
				.containsEntry("redis-rate-limiter.burstCapacity", "200")
				.containsEntry("redis-rate-limiter.requestedTokens", "1")
				.containsEntry("key-resolver", "#{@rateLimitKeyResolver}");
	}

	@Test
	void shouldNotEnableGlobalResponseCache() {
		assertThat(environment.getProperty(
				"spring.cloud.gateway.server.webflux.filter.local-response-cache.enabled", Boolean.class))
				.isNotEqualTo(Boolean.TRUE);
	}

	@Test
	void shouldConfigureHttpClientTimeouts() {
		assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.connect-timeout"))
				.isEqualTo("1000");
		assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.response-timeout"))
				.isEqualTo("5000");
	}

	@Test
	void shouldConfigureCorsForAdminApp() {
		CorsConfiguration cors = globalCorsProperties.getCorsConfigurations().get("/**");

		assertThat(cors).isNotNull();
		assertThat(cors.getAllowedOrigins()).containsExactly("http://admin.algashop.local:4200");
		assertThat(cors.getAllowedMethods())
				.containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
		assertThat(cors.getAllowedHeaders()).containsExactly("*");
	}

	@Test
	void shouldConfigureOrderingCircuitBreakerInstance() {
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ordering-route-cb");
		CircuitBreakerConfig config = circuitBreaker.getCircuitBreakerConfig();

		assertThat(config.getSlidingWindowSize()).isEqualTo(8);
		assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
		assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
		assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
		assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
		assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
		assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void shouldConfigureDefaultTimeLimiter() {
		assertThat(timeLimiterRegistry.getDefaultConfig().getTimeoutDuration())
				.isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void shouldExposeActuatorEndpoints() {
		assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
				.contains("gateway", "circuitbreakers");
		assertThat(environment.getProperty(
				"resilience4j.circuitbreaker.instances.ordering-route-cb.registerHealthIndicator",
				Boolean.class))
				.isTrue();
	}

	private RouteDefinition route(String id) {
		List<RouteDefinition> routes = gatewayProperties.getRoutes();
		return routes.stream()
				.filter(route -> id.equals(route.getId()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Route not found: " + id));
	}

	private PredicateDefinition predicate(RouteDefinition route, String name) {
		return route.getPredicates().stream()
				.filter(predicate -> name.equals(predicate.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Predicate not found: " + name));
	}

	private FilterDefinition filter(RouteDefinition route, String name) {
		return route.getFilters().stream()
				.filter(filter -> name.equals(filter.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Filter not found: " + name));
	}

	private FilterDefinition defaultFilter(String name) {
		return gatewayProperties.getDefaultFilters().stream()
				.filter(filter -> name.equals(filter.getName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Default filter not found: " + name));
	}

}
