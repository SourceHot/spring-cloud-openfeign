/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.openfeign;

import feign.Feign;
import feign.Target;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.util.StringUtils;

@SuppressWarnings("unchecked")
class FeignCircuitBreakerTargeter implements Targeter {

	private final CircuitBreakerFactory circuitBreakerFactory;

	private final boolean circuitBreakerGroupEnabled;

	FeignCircuitBreakerTargeter(CircuitBreakerFactory circuitBreakerFactory, boolean circuitBreakerGroupEnabled) {
		this.circuitBreakerFactory = circuitBreakerFactory;
		this.circuitBreakerGroupEnabled = circuitBreakerGroupEnabled;
	}

	@Override
	public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignContext context,
						Target.HardCodedTarget<T> target) {
		// 如果FeignBuilder对象的类型不是FeignCircuitBreaker.Builder直接构建
		if (!(feign instanceof FeignCircuitBreaker.Builder)) {
			return feign.target(target);
		}
		// 创建FeignCircuitBreaker构造器
		FeignCircuitBreaker.Builder builder = (FeignCircuitBreaker.Builder) feign;
		// 确认名称,名称可能是上下文id或者工厂Bean中的名称
		String name = !StringUtils.hasText(factory.getContextId()) ? factory.getName() : factory.getContextId();
		// 获取fallBack对象
		Class<?> fallback = factory.getFallback();
		// 如果如果fallback对象不是void
		if (fallback != void.class) {
			// 包装fallback后返回
			return targetWithFallback(name, context, target, builder, fallback);
		}
		// 获取fallbackFactory对象
		Class<?> fallbackFactory = factory.getFallbackFactory();
		// 如果fallBackFactory对象不是void
		if (fallbackFactory != void.class) {
			// 包装fallbackFactory后返回
			return targetWithFallbackFactory(name, context, target, builder, fallbackFactory);
		}
		// 先进行构建在包装后返回
		return builder(name, builder).target(target);
	}

	private <T> T targetWithFallbackFactory(String feignClientName, FeignContext context,
											Target.HardCodedTarget<T> target, FeignCircuitBreaker.Builder builder, Class<?> fallbackFactoryClass) {
		// 从上下文中获取FallbackFactory实例
		FallbackFactory<? extends T> fallbackFactory = (FallbackFactory<? extends T>) getFromContext("fallbackFactory",
			feignClientName, context, fallbackFactoryClass, FallbackFactory.class);

		// 构建对象并且在包装后返回
		return builder(feignClientName, builder).target(target, fallbackFactory);
	}

	private <T> T targetWithFallback(String feignClientName, FeignContext context, Target.HardCodedTarget<T> target,
									 FeignCircuitBreaker.Builder builder, Class<?> fallback) {
		// 从上下文中获取fallBack实例
		T fallbackInstance = getFromContext("fallback", feignClientName, context, fallback, target.type());
		// 构建对象并且在包装后返回
		return builder(feignClientName, builder).target(target, fallbackInstance);
	}

	private <T> T getFromContext(String fallbackMechanism, String feignClientName, FeignContext context,
								 Class<?> beanType, Class<T> targetType) {

		Object fallbackInstance = context.getInstance(feignClientName, beanType);
		if (fallbackInstance == null) {
			throw new IllegalStateException(
				String.format("No " + fallbackMechanism + " instance of type %s found for feign client %s",
					beanType, feignClientName));
		}

		if (!targetType.isAssignableFrom(beanType)) {
			throw new IllegalStateException(String.format("Incompatible " + fallbackMechanism
					+ " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
				beanType, targetType, feignClientName));
		}
		return (T) fallbackInstance;
	}

	private FeignCircuitBreaker.Builder builder(String feignClientName, FeignCircuitBreaker.Builder builder) {
		// 构建FeignCircuitBreaker.Builder对象
		// 1. 设置CircuitBreakerFactory对象
		// 2. 设置FeignClient名称
		// 3. 设置是否启动熔断
		return builder
			.circuitBreakerFactory(circuitBreakerFactory)
			.feignClientName(feignClientName)
			.circuitBreakerGroupEnabled(circuitBreakerGroupEnabled);
	}

}
