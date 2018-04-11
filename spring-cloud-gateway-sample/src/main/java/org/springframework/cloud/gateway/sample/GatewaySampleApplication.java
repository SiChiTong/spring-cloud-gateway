/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.sample;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * @author Spencer Gibb
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AdditionalRoutes.class)
public class GatewaySampleApplication {

	@Bean
	public RouteLocator customRouteLocator(RouteLocatorBuilder builder, ReadBodyPredicateFactory readBodyPredicateFactory) {
		//@formatter:off
		return builder.routes()
				.route(r -> r.host("**.abc.org").and().path("/image/png")
					.filters(f ->
							f.addResponseHeader("X-TestHeader", "foobar"))
					.uri("http://httpbin.org:80")
				)
				.route("read_body_pred", r -> r.host("*.readbody.org").and().predicate(readBodyPredicateFactory.apply(o -> {}))
					.filters(f ->
							f.addRequestHeader("X-TestHeader", "read_body_pred"))
						.uri("http://httpbin.org:80")
				)
				.route("rewrite_request", r -> r.host("*.rewriterequest.org")
					.filters(f -> f.addRequestHeader("X-TestHeader", "rewrite_request")
							/*TODO: .filter()*/)
						.uri("http://httpbin.org:80")
				)
				.route("rewrite_response", r -> r.host("*.rewriteresponse.org")
					.filters(f -> f.addRequestHeader("X-TestHeader", "rewrite_response")
							/*TODO: .filter()*/)
						.uri("http://httpbin.org:80")
				)
				.route(r -> r.path("/image/webp")
					.filters(f ->
							f.addResponseHeader("X-AnotherHeader", "baz"))
					.uri("http://httpbin.org:80")
				)
				.route(r -> r.order(-1)
					.host("**.throttle.org").and().path("/get")
					.filters(f -> f.filter(new ThrottleGatewayFilter()
									.setCapacity(1)
									.setRefillTokens(1)
									.setRefillPeriod(10)
									.setRefillUnit(TimeUnit.SECONDS)))
					.uri("http://httpbin.org:80")
				)
				.build();
		//@formatter:on
	}

	@Bean
	public RouterFunction<ServerResponse> testFunRouterFunction() {
		RouterFunction<ServerResponse> route = RouterFunctions.route(
				RequestPredicates.path("/testfun"),
				request -> ServerResponse.ok().body(BodyInserters.fromObject("hello")));
		return route;
	}

	@Bean
	public ReadBodyPredicateFactory readBodyPredicateFactory() {
		return new ReadBodyPredicateFactory();
	}

	static class ReadBodyPredicateFactory extends AbstractRoutePredicateFactory {

		@Autowired
		ServerCodecConfigurer serverCodecConfigurer;

		public ReadBodyPredicateFactory() {
			super(Object.class);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Predicate<ServerWebExchange> apply(Object config) {
			return exchange -> {
				MediaType mediaType = exchange.getRequest().getHeaders().getContentType();
				List<HttpMessageReader<?>> readers = serverCodecConfigurer.getReaders();
				ResolvableType elementType = ResolvableType.forClass(String.class);
				Optional<HttpMessageReader<?>> reader = readers.stream()
						.filter(r -> r.canRead(elementType, mediaType))
						.findFirst();
				boolean answer = false;
                if (reader.isPresent()) {
					Mono<String> readMono = reader.get().readMono(elementType, exchange.getRequest(), null)
							.cast(String.class);
					answer = process(readMono, peek -> {
						Optional<HttpMessageWriter<?>> writer = serverCodecConfigurer.getWriters()
								.stream()
								.filter(w -> w.canWrite(elementType, mediaType))
								.findFirst();

						if (writer.isPresent()) {
							Publisher publisher = Mono.just(peek);
							ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
								@Override
								public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
									exchange.getAttributes().put("cachedRequestBody", body);
									return Mono.empty();
								}

								@Override
								public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
									throw new UnsupportedOperationException("writeAndFlushWith");
								}
							};
							writer.get().write(publisher, elementType, mediaType, responseDecorator, null);
						}
						return peek.trim().equalsIgnoreCase("hello");
					});

				}
				return answer;
			};
		}
	}

	public static <T, R> R process(Mono<T> mono, Function<T, R> consumer) {
		MonoProcessor<T> processor = MonoProcessor.create();
		mono.subscribeWith(processor);
		if (processor.isTerminated()) {
			Throwable error = processor.getError();
			if (error != null) {
				throw (RuntimeException) error;
			}
			T peek = processor.peek();

			return consumer.apply(peek);
		}
		else {
			// Should never happen...
			throw new IllegalStateException(
					"SyncInvocableHandlerMethod should have completed synchronously.");
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(GatewaySampleApplication.class, args);
	}
}
