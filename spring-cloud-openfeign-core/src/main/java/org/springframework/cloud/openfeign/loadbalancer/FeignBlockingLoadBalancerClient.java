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

package org.springframework.cloud.openfeign.loadbalancer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import feign.Client;
import feign.Request;
import feign.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.buildRequestData;
import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing;

/**
 * A {@link Client} implementation that uses {@link LoadBalancerClient} to select a
 * {@link ServiceInstance} to use while resolving the request host.
 *
 * @author Olga Maciaszek-Sharma
 * @since 2.2.0
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class FeignBlockingLoadBalancerClient implements Client {

	private static final Log LOG = LogFactory.getLog(FeignBlockingLoadBalancerClient.class);
	/**
	 * Feign客户端对象
	 */
	private final Client delegate;
	/**
	 * 负载均衡客户端
	 */
	private final LoadBalancerClient loadBalancerClient;
	/**
	 * 负载均衡属性
	 */
	private final LoadBalancerProperties properties;
	/**
	 *负载均衡客户端工厂
	 */
	private final LoadBalancerClientFactory loadBalancerClientFactory;

	public FeignBlockingLoadBalancerClient(Client delegate, LoadBalancerClient loadBalancerClient,
			LoadBalancerProperties properties, LoadBalancerClientFactory loadBalancerClientFactory) {
		this.delegate = delegate;
		this.loadBalancerClient = loadBalancerClient;
		this.properties = properties;
		this.loadBalancerClientFactory = loadBalancerClientFactory;
	}

	@Override
	public Response execute(Request request, Request.Options options) throws IOException {
		// 从请求对象中获取url属性，将url属性转换为URI对象
		final URI originalUri = URI.create(request.url());
		// 提取host
		String serviceId = originalUri.getHost();
		// 如果host为空抛出异常
		Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);
		// 获取hint属性
		String hint = getHint(serviceId);
		// 创建lb请求对象
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
			new RequestDataContext(buildRequestData(request), hint));

		// 获取生命周期接口
		Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
			.getSupportedLifecycleProcessors(
				loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
				RequestDataContext.class, ResponseData.class, ServiceInstance.class);
		// 执行生命周期接口中的onStart方法
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
		// 选择服务实例
		ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
		// 构建lb响应对象
		org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
			instance);
		// 服务实例为空的情况
		if (instance == null) {
			// 输出日志
			String message = "Load balancer does not contain an instance for the service " + serviceId;
			if (LOG.isWarnEnabled()) {
				LOG.warn(message);
			}
			// 执行生命周期接口的onComplete方法
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
				.onComplete(new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
					CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
			// 构建响应，此时构建的响应是服务未找到
			return Response.builder().request(request).status(HttpStatus.SERVICE_UNAVAILABLE.value())
				.body(message, StandardCharsets.UTF_8).build();
		}
		// 确认最终发送请求的地址
		String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
		// 构建请求对象
		Request newRequest = buildRequest(request, reconstructedUrl);
		// 执行请求并且返回
		return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, lbRequest, lbResponse,
			supportedLifecycleProcessors);
	}

	protected Request buildRequest(Request request, String reconstructedUrl) {
		return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
				request.charset(), request.requestTemplate());
	}

	// Visible for Sleuth instrumentation
	public Client getDelegate() {
		return delegate;
	}

	private String getHint(String serviceId) {
		String defaultHint = properties.getHint().getOrDefault("default", "default");
		String hintPropertyValue = properties.getHint().get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
