/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.context.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionRegistration;
import org.springframework.cloud.function.context.FunctionRegistry;
import org.springframework.cloud.function.context.FunctionType;
import org.springframework.cloud.function.context.HybridFunctionalRegistrationTests.UppercaseFunction;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.context.catalog.exception.FunctionDefinitionDoesNotExistException;
import org.springframework.cloud.function.context.catalog.exception.UnsupportedFunctionException;
import org.springframework.cloud.function.context.config.JsonMessageConverter;
import org.springframework.cloud.function.context.config.NegotiatingMessageConverterWrapper;
import org.springframework.cloud.function.json.GsonMapper;
import org.springframework.cloud.function.json.JsonMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Oleg Zhurakousky
 *
 */
public class SimpleFunctionRegistryTests {

	private CompositeMessageConverter messageConverter;

	private ConversionService conversionService;

	@BeforeEach
	public void before() {
		List<MessageConverter> messageConverters = new ArrayList<>();
		JsonMapper jsonMapper = new GsonMapper(new Gson());
		messageConverters.add(NegotiatingMessageConverterWrapper.wrap(new JsonMessageConverter(jsonMapper)));
		messageConverters.add(NegotiatingMessageConverterWrapper.wrap(new ByteArrayMessageConverter()));
		messageConverters.add(NegotiatingMessageConverterWrapper.wrap(new StringMessageConverter()));
		this.messageConverter = new CompositeMessageConverter(messageConverters);

		this.conversionService = new DefaultConversionService();
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testSCF588() {

		UpperCase function = new UpperCase();
		FunctionRegistration<UpperCase> registration = new FunctionRegistration<>(
				function, "foo").type(FunctionType.of(UppercaseFunction.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(registration);

		FunctionInvocationWrapper lookedUpFunction = catalog.lookup("uppercase");

		Message<String> message = MessageBuilder.withPayload("hello")
				.setHeader("scf-sink-url", "blah")
				.setHeader("scf-func-name", "blah")
				.build();
		Object result = lookedUpFunction.apply(message);
		assertThat(result).isInstanceOf(Message.class);
		assertThat(((Message<String>) result).getPayload()).isEqualTo("HELLO");
	}

	@Test
	public void testFunctionLookup() {

		TestFunction function = new TestFunction();
		FunctionRegistration<TestFunction> registration = new FunctionRegistration<>(
				function, "foo").type(FunctionType.of(TestFunction.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(registration);

		FunctionInvocationWrapper lookedUpFunction = catalog.lookup("hello");
		assertThat(lookedUpFunction).isNotNull(); // because we only have one and can look it up with any name
		FunctionRegistration<TestFunction> registration2 = new FunctionRegistration<>(
				function, "foo2").type(FunctionType.of(TestFunction.class));
		catalog.register(registration2);
		lookedUpFunction = catalog.lookup("hello");
		assertThat(lookedUpFunction).isNull();
	}

	@Test
	public void testStrictLookup() {

		TestFunction function = new TestFunction();
		FunctionRegistration<TestFunction> registration = new FunctionRegistration<>(
			function, "foo").type(FunctionType.of(TestFunction.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(registration);

		FunctionInvocationWrapper lookedUpFunction = catalog.lookupStrict("hello");
		assertThat(lookedUpFunction).isNotNull(); // because we only have one and can look it up with any name

		FunctionRegistration<TestFunction> registration2 = new FunctionRegistration<>(
			function, "foo2").type(FunctionType.of(TestFunction.class));
		catalog.register(registration2);
		assertThatThrownBy(() ->
			catalog.lookupStrict("hello")).isInstanceOf(FunctionDefinitionDoesNotExistException.class);

		Function<KStream, KStream> kafkaStreamFunction = kStream -> kStream;
		FunctionRegistration<Function<KStream, KStream>> kstreamRegistration = new FunctionRegistration<>(
			kafkaStreamFunction, "kstreamFunction")
			.type(new ParameterizedTypeReference<Function<KStream, KStream>>() { }.getType());
		catalog.register(kstreamRegistration);
		assertThatThrownBy(() ->
			catalog.lookupStrict("kstreamFunction|foo2")).isInstanceOf(UnsupportedFunctionException.class);
	}

	@Test
	public void testFunctionComposition() {
		FunctionRegistration<UpperCase> upperCaseRegistration = new FunctionRegistration<>(
				new UpperCase(), "uppercase").type(FunctionType.of(UpperCase.class));
		FunctionRegistration<Reverse> reverseRegistration = new FunctionRegistration<>(
				new Reverse(), "reverse").type(FunctionType.of(Reverse.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(upperCaseRegistration);
		catalog.register(reverseRegistration);

		Function<Flux<String>, Flux<String>> lookedUpFunction = catalog
				.lookup("uppercase|reverse");
		assertThat(lookedUpFunction).isNotNull();
		assertThat(lookedUpFunction.apply(Flux.just("star")).blockFirst())
				.isEqualTo("RATS");
	}

	@Test
	public void testFunctionCompositionImplicit() {
		FunctionRegistration<Words> wordsRegistration = new FunctionRegistration<>(
				new Words(), "words").type(FunctionType.of(Words.class));
		FunctionRegistration<Reverse> reverseRegistration = new FunctionRegistration<>(
				new Reverse(), "reverse").type(FunctionType.of(Reverse.class));
		FunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(wordsRegistration);
		catalog.register(reverseRegistration);

		// There's only one function, we should be able to leave that blank
		Supplier<String> lookedUpFunction = catalog.lookup("words|");

		assertThat(lookedUpFunction).isNotNull();
		assertThat(lookedUpFunction.get()).isEqualTo("olleh");
	}

	@Test
	@Disabled
	public void testFunctionCompletelyImplicitComposition() {
		FunctionRegistration<Words> wordsRegistration = new FunctionRegistration<>(
				new Words(), "words").type(FunctionType.of(Words.class));
		FunctionRegistration<Reverse> reverseRegistration = new FunctionRegistration<>(
				new Reverse(), "reverse").type(FunctionType.of(Reverse.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(wordsRegistration);
		catalog.register(reverseRegistration);

		// There's only one function, we should be able to leave that blank
		Supplier<Flux<String>> lookedUpFunction = catalog.lookup("|");

		assertThat(lookedUpFunction).isNotNull();
		assertThat(lookedUpFunction.get().blockFirst()).isEqualTo("olleh");
	}

	@Test
	public void testFunctionCompositionExplicit() {
		FunctionRegistration<Words> wordsRegistration = new FunctionRegistration<>(
				new Words(), "words").type(FunctionType.of(Words.class));
		FunctionRegistration<Reverse> reverseRegistration = new FunctionRegistration<>(
				new Reverse(), "reverse").type(FunctionType.of(Reverse.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(wordsRegistration);
		catalog.register(reverseRegistration);

		Supplier<String> lookedUpFunction = catalog.lookup("words|reverse");

		assertThat(lookedUpFunction).isNotNull();
		assertThat(lookedUpFunction.get()).isEqualTo("olleh");
	}

	@Test
	public void testFunctionCompositionWithMessages() {
		FunctionRegistration<UpperCaseMessage> upperCaseRegistration = new FunctionRegistration<>(
				new UpperCaseMessage(), "uppercase")
						.type(FunctionType.of(UpperCaseMessage.class));
		FunctionRegistration<ReverseMessage> reverseRegistration = new FunctionRegistration<>(
				new ReverseMessage(), "reverse")
						.type(FunctionType.of(ReverseMessage.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(upperCaseRegistration);
		catalog.register(reverseRegistration);

		Function<Flux<Message<String>>, Flux<Message<String>>> lookedUpFunction = catalog
				.lookup("uppercase|reverse");

		assertThat(lookedUpFunction).isNotNull();
		assertThat(lookedUpFunction
				.apply(Flux.just(MessageBuilder.withPayload("star").build())).blockFirst()
				.getPayload()).isEqualTo("RATS");
	}

	@Test
	public void testFunctionCompositionMixedMessages() {
		FunctionRegistration<UpperCaseMessage> upperCaseRegistration = new FunctionRegistration<>(
				new UpperCaseMessage(), "uppercase")
						.type(FunctionType.of(UpperCaseMessage.class));
		FunctionRegistration<Reverse> reverseRegistration = new FunctionRegistration<>(
				new Reverse(), "reverse").type(FunctionType.of(Reverse.class));
		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(upperCaseRegistration);
		catalog.register(reverseRegistration);

		Function<Message<String>, String> lookedUpFunction = catalog
				.lookup("uppercase|reverse");

		assertThat(lookedUpFunction).isNotNull();
		String result = lookedUpFunction.apply(MessageBuilder.withPayload("star").setHeader("foo", "bar").build());
		assertThat(result).isEqualTo("RATS");
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testReactiveFunctionMessages() {
		FunctionRegistration<ReactiveFunction> registration = new FunctionRegistration<>(new ReactiveFunction(), "reactive")
			.type(FunctionType.of(ReactiveFunction.class));

		SimpleFunctionRegistry catalog = new SimpleFunctionRegistry(this.conversionService, this.messageConverter);
		catalog.register(registration);

		Function lookedUpFunction = catalog.lookup("reactive");

		assertThat(lookedUpFunction).isNotNull();
		Flux<List<String>> result = (Flux<List<String>>) lookedUpFunction
			.apply(Flux.just(MessageBuilder
				.withPayload("[{\"name\":\"item1\"},{\"name\":\"item2\"}]")
				.setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
				.build()
			));
		Assertions.assertIterableEquals(result.blockFirst(), Arrays.asList("item1", "item2"));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testWithCustomMessageConverter() {
		FunctionCatalog catalog = this.configureCatalog(CustomConverterConfiguration.class);
		Function function = catalog.lookup("func");
		Object result = function.apply(MessageBuilder.withPayload("Jim Lahey").setHeader(MessageHeaders.CONTENT_TYPE, "text/person").build());
		assertThat(result).isEqualTo("Jim Lahey");
	}

	private FunctionCatalog configureCatalog(Class<?>... configClass) {
		ApplicationContext context = new SpringApplicationBuilder(configClass)
				.run("--logging.level.org.springframework.cloud.function=DEBUG",
						"--spring.main.lazy-initialization=true");
		FunctionCatalog catalog = context.getBean(FunctionCatalog.class);
		return catalog;
	}

	@EnableAutoConfiguration
	private static class CustomConverterConfiguration {
		@Bean
		public MessageConverter stringToPersonConverter() {
			return new AbstractMessageConverter(MimeType.valueOf("text/person")) {
				@Override
				protected Object convertFromInternal(Message<?> message, Class<?> targetClass, @Nullable Object conversionHint) {
					String payload =  message.getPayload() instanceof byte[] ? new String((byte[]) message.getPayload()) : (String) message.getPayload();
					Person person = new Person();
					person.setName(payload);
					return person;
				}

				@Override
				protected boolean canConvertFrom(Message<?> message, @Nullable Class<?> targetClass) {
					return supportsMimeType(message.getHeaders()) && Person.class.isAssignableFrom(targetClass) && (
							message.getPayload() instanceof String || message.getPayload() instanceof byte[]);
				}

				@Override
				public Object convertToInternal(Object rawPayload, MessageHeaders headers, Object conversionHint) {
					return rawPayload.toString();
				}

				@Override
				protected boolean canConvertTo(Object payload, @Nullable MessageHeaders headers) {
					return true;
				}

				@Override
				protected boolean supports(Class<?> clazz) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Bean
		public Function<Person, String> func() {
			return person -> person.getName();
		}
	}

	public static class Person {
		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}

	private static class Words implements Supplier<String> {

		@Override
		public String get() {
			return "hello";
		}

	}

	private static class UpperCase implements Function<String, String> {

		@Override
		public String apply(String t) {
			return t.toUpperCase();
		}

	}

	private static class UpperCaseMessage
			implements Function<Message<String>, Message<String>> {

		@Override
		public Message<String> apply(Message<String> t) {
			return MessageBuilder.withPayload(t.getPayload().toUpperCase())
					.copyHeaders(t.getHeaders()).build();
		}

	}

	private static class Reverse implements Function<String, String> {

		@Override
		public String apply(String t) {
			return new StringBuilder(t).reverse().toString();
		}

	}

	private static class ReverseMessage
			implements Function<Message<String>, Message<String>> {

		@Override
		public Message<String> apply(Message<String> t) {
			return MessageBuilder
					.withPayload(new StringBuilder(t.getPayload()).reverse().toString())
					.copyHeaders(t.getHeaders()).build();
		}

	}

	private static class TestFunction implements Function<Integer, String> {

		@Override
		public String apply(Integer t) {
			return "i=" + t;
		}

	}

	private static class ReactiveFunction implements Function<Flux<Message<List<Person>>>, Flux<List<String>>> {

		@Override
		public Flux<List<String>> apply(Flux<Message<List<Person>>> listFlux) {
			return listFlux
				.map(Message::getPayload)
				.map(lst -> lst.stream().map(Person::getName).collect(Collectors.toList()));
		}
	}

}
