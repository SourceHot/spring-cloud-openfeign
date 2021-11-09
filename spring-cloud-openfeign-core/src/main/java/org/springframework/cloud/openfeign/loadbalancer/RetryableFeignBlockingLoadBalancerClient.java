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

import feign.Client;
import feign.Request;
import feign.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.buildRequestData;

/**
 * A {@link Client} implementation that provides Spring Retry support for requests
 * load-balanced with Spring Cloud LoadBalancer.
 *
 * @author Olga Maciaszek-Sharma
 * @since 2.2.6
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class RetryableFeignBlockingLoadBalancerClient implements Client {

	private static final Log LOG = LogFactory.getLog(FeignBlockingLoadBalancerClient.class);
	/**
	 * 客户端对象
	 */
	private final Client delegate;
	/**
	 * 负载均衡客户端
	 */
	private final LoadBalancerClient loadBalancerClient;
	/**
	 * 负载均衡重试工厂
	 */
	private final LoadBalancedRetryFactory loadBalancedRetryFactory;
	/**
	 * 负载均衡属性
	 */
	private final LoadBalancerProperties properties;
	/**
	 * 负载均衡客户端工厂
	 */
	private final LoadBalancerClientFactory loadBalancerClientFactory;

	public RetryableFeignBlockingLoadBalancerClient(Client delegate, LoadBalancerClient loadBalancerClient,
													LoadBalancedRetryFactory loadBalancedRetryFactory, LoadBalancerProperties properties,
													LoadBalancerClientFactory loadBalancerClientFactory) {
		this.delegate = delegate;
		this.loadBalancerClient = loadBalancerClient;
		this.loadBalancedRetryFactory = loadBalancedRetryFactory;
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
		// 创建负载均衡策略
		final LoadBalancedRetryPolicy retryPolicy = loadBalancedRetryFactory.createRetryPolicy(serviceId,
			loadBalancerClient);
		// 创建重试模板对象
		RetryTemplate retryTemplate = buildRetryTemplate(serviceId, request, retryPolicy);
		// 通过重试模板对象进行请求
		return retryTemplate.execute(context -> {
				// 请求对象
				Request feignRequest = null;
				// 最终会被调用的服务实例
				ServiceInstance retrievedServiceInstance = null;
				// 获取生命周期处理器
				Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
					.getSupportedLifecycleProcessors(
						loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
						RetryableRequestContext.class, ResponseData.class, ServiceInstance.class);
				// 获取hint属性
				String hint = getHint(serviceId);
				// 创建请求对象
				DefaultRequest<RetryableRequestContext> lbRequest = new DefaultRequest<>(
					new RetryableRequestContext(null, buildRequestData(request), hint));
				// On retries the policy will choose the server and set it in the context
				// and extract the server and update the request being made
				// 如果上下文类型是LoadBalancedRetryContext
				if (context instanceof LoadBalancedRetryContext) {
					// 上下文类型转换
					LoadBalancedRetryContext lbContext = (LoadBalancedRetryContext) context;
					// 从上下文中获取服务实例
					ServiceInstance serviceInstance = lbContext.getServiceInstance();
					// 如果服务实例为空
					if (serviceInstance == null) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("Service instance retrieved from LoadBalancedRetryContext: was null. "
								+ "Reattempting service instance selection");
						}
						// 获取上一个服务实例
						ServiceInstance previousServiceInstance = lbContext.getPreviousServiceInstance();
						// 在上下文中设置上一个服务实例
						lbRequest.getContext().setPreviousServiceInstance(previousServiceInstance);
						// 生命周期处理器执行onStart方法
						supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
						// 选择服务实例
						retrievedServiceInstance = loadBalancerClient.choose(serviceId, lbRequest);
						if (LOG.isDebugEnabled()) {
							LOG.debug(String.format("Selected service instance: %s", retrievedServiceInstance));
						}
						// 向上下文中设置服务实例
						lbContext.setServiceInstance(retrievedServiceInstance);
					}

					// 最终会被调用的服务实例为空的情况
					if (retrievedServiceInstance == null) {
						if (LOG.isWarnEnabled()) {
							LOG.warn("Service instance was not resolved, executing the original request");
						}
						// 创建响应对象
						org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
							retrievedServiceInstance);
						// 生命周期处理器执行onComplete
						supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
							.onComplete(new CompletionContext<ResponseData, ServiceInstance, RetryableRequestContext>(
								CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
						// 请求对象信息设置
						feignRequest = request;
					}
					// 最终会被调用的服务实例不为空的情况
					else {
						if (LOG.isDebugEnabled()) {
							LOG.debug(String.format("Using service instance from LoadBalancedRetryContext: %s",
								retrievedServiceInstance));
						}
						// 解析url地址
						String reconstructedUrl = loadBalancerClient.reconstructURI(retrievedServiceInstance, originalUri)
							.toString();
						// 构建请求对象
						feignRequest = buildRequest(request, reconstructedUrl);
					}
				}
				// 创建响应对象
				org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
					retrievedServiceInstance);
				// 执行请求将获取响应对象
				Response response = LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing(delegate, options,
					feignRequest, lbRequest, lbResponse, supportedLifecycleProcessors,
					retrievedServiceInstance != null);
				// 获取响应对象的状态码
				int responseStatus = response.status();
				// 确认是否需要重试
				if (retryPolicy != null && retryPolicy.retryableStatusCode(responseStatus)) {
					if (LOG.isDebugEnabled()) {
						LOG.debug(String.format("Retrying on status code: %d", responseStatus));
					}
					// 关闭响应
					response.close();
					// 抛出异常
					throw new RetryableStatusCodeException(serviceId, responseStatus, response, URI.create(request.url()));
				}
				return response;
			},
			// 回调对象
			new LoadBalancedRecoveryCallback<Response, Response>() {
				@Override
				protected Response createResponse(Response response, URI uri) {
					return response;
				}
			});
	}

	protected Request buildRequest(Request request, String reconstructedUrl) {
		return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
			request.charset(), request.requestTemplate());
	}

	private RetryTemplate buildRetryTemplate(String serviceId, Request request, LoadBalancedRetryPolicy retryPolicy) {
		RetryTemplate retryTemplate = new RetryTemplate();
		BackOffPolicy backOffPolicy = this.loadBalancedRetryFactory.createBackOffPolicy(serviceId);
		retryTemplate.setBackOffPolicy(backOffPolicy == null ? new NoBackOffPolicy() : backOffPolicy);
		RetryListener[] retryListeners = this.loadBalancedRetryFactory.createRetryListeners(serviceId);
		if (retryListeners != null && retryListeners.length != 0) {
			retryTemplate.setListeners(retryListeners);
		}

		retryTemplate.setRetryPolicy(retryPolicy == null ? new NeverRetryPolicy()
			: new InterceptorRetryPolicy(toHttpRequest(request), retryPolicy, loadBalancerClient, serviceId));
		return retryTemplate;
	}

	// Visible for Sleuth instrumentation
	public Client getDelegate() {
		return delegate;
	}

	private HttpRequest toHttpRequest(Request request) {
		return new HttpRequest() {
			@Override
			public HttpMethod getMethod() {
				return HttpMethod.resolve(request.httpMethod().name());
			}

			@Override
			public String getMethodValue() {
				return getMethod().name();
			}

			@Override
			public URI getURI() {
				return URI.create(request.url());
			}

			@Override
			public HttpHeaders getHeaders() {
				Map<String, List<String>> headers = new HashMap<>();
				Map<String, Collection<String>> feignHeaders = request.headers();
				for (String key : feignHeaders.keySet()) {
					headers.put(key, new ArrayList<>(feignHeaders.get(key)));
				}
				HttpHeaders httpHeaders = new HttpHeaders();
				httpHeaders.putAll(headers);
				return httpHeaders;
			}
		};
	}

	private String getHint(String serviceId) {
		String defaultHint = properties.getHint().getOrDefault("default", "default");
		String hintPropertyValue = properties.getHint().get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
