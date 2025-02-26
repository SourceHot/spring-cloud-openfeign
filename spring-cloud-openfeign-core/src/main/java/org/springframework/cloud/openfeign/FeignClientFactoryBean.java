/*
 * Copyright 2013-2021 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import feign.Capability;
import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 用于创建FeignClient的工厂bean对象
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 * @author Marcin Grzejszczak
 * @author Jonatan Ivanov
 * @author Sam Kruglov
 * @author Jasbir Singh
 */
public class FeignClientFactoryBean
		implements FactoryBean<Object>, InitializingBean, ApplicationContextAware, BeanFactoryAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	private static Log LOG = LogFactory.getLog(FeignClientFactoryBean.class);
	/**
	 * FeignClient标记的类对象
	 */
	private Class<?> type;
	/**
	 * 名称
	 */
	private String name;
	/**
	 * 请求域名
	 */
	private String url;
	/**
	 * 上下文id
	 */
	private String contextId;
	/**
	 * 请求地址
	 */
	private String path;
	/**
	 * 是否解码404信息
	 */
	private boolean decode404;
	/**
	 * 是否继承父上下文
	 */
	private boolean inheritParentContext = true;
	/**
	 * 应用上下文
	 */
	private ApplicationContext applicationContext;
	/**
	 * bean工厂
	 */
	private BeanFactory beanFactory;
	/**
	 * 回调对象
	 */
	private Class<?> fallback = void.class;
	/**
	 * 回到对象生成器
	 */
	private Class<?> fallbackFactory = void.class;
	/**
	 * 读取超时时间
	 */
	private int readTimeoutMillis = new Request.Options().readTimeoutMillis();
	/**
	 * 连接超时时间
	 */
	private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();
	/**
	 * 是否重定向
	 */
	private boolean followRedirects = new Request.Options().isFollowRedirects();
	/**
	 * 是否允许刷新客户端
	 */
	private boolean refreshableClient = false;
	/**
	 * FeignBuilder自定义集合
	 */
	private final List<FeignBuilderCustomizer> additionalCustomizers = new ArrayList<>();

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(contextId, "Context id must be set");
		Assert.hasText(name, "Name must be set");
	}

	protected Feign.Builder feign(FeignContext context) {
		// 获取feign日志工厂
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		// 创建日志对象
		Logger logger = loggerFactory.create(type);

		// @formatter:off
		// 获取FeignBuilder对象
		Feign.Builder builder = get(context, Feign.Builder.class)
			// required values
			.logger(logger)
			.encoder(get(context, Encoder.class))
			.decoder(get(context, Decoder.class))
			.contract(get(context, Contract.class));
		// @formatter:on

		// 配置FeignBuilder对象
		configureFeign(context, builder);
		// 处理FeignBuilderCustomizer接口为FeignBuilder对象进行自定义处理
		applyBuildCustomizers(context, builder);

		return builder;
	}

	private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
		// 从Feign上下文中获取FeignBuilderCustomizer接口实现类集合
		Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId,
				FeignBuilderCustomizer.class);
		//  如果FeignBuilderCustomizer接口实现类集合不为空则先进行排序，在处理所有自定义Feign.Builder对象操作
		if (customizerMap != null) {
			customizerMap.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
					.forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
		}
		// 循环成员变量FeignBuilderCustomizer，调用customize方法将Feign.Builder对象进行自定义出
		additionalCustomizers.forEach(customizer -> customizer.customize(builder));
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		// 如果bean工厂不为空的情况下从bean工厂中获取Feign客户端配置参数，如果为空的情况下从应用上下文中获取Feign客户端配置参数
		FeignClientProperties properties = beanFactory != null ? beanFactory.getBean(FeignClientProperties.class)
			: applicationContext.getBean(FeignClientProperties.class);

		// 获取Feign客户端配置
		FeignClientConfigurer feignClientConfigurer = getOptional(context, FeignClientConfigurer.class);

		// 设置inheritParentContext属性
		setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		// 如果Feign客户端配置参数不为空并且inheritParentContext值为真
		if (properties != null && inheritParentContext) {
			// 如果Feign客户端参数是默认属性
			if (properties.isDefaultToProperties()) {
				// 从Feign上下文中获取参数对Feign.Builder对象进行数据设置
				configureUsingConfiguration(context, builder);
				// 从Feign客户端配置参数中获取默认配置参数对Feign.Builder对象进行数据设置
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				// 从Feign客户端配置表中根据上下文id获取配置对Feign.Builder对象进行数据设置
				configureUsingProperties(properties.getConfig().get(contextId), builder);
			}
			// 如果不是默认属性
			else {
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				configureUsingProperties(properties.getConfig().get(contextId), builder);
				configureUsingConfiguration(context, builder);
			}
		} else {
			configureUsingConfiguration(context, builder);
		}
	}

	/**
	 * 配置需要使用的配置对象
	 */
	protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
		Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		}
		else {
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}
		Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
		if (options == null) {
			options = getOptionsByName(context, contextId);
		}

		if (options != null) {
			builder.options(options);
			readTimeoutMillis = options.readTimeoutMillis();
			connectTimeoutMillis = options.connectTimeoutMillis();
			followRedirects = options.isFollowRedirects();
		}
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(context,
				RequestInterceptor.class);
		if (requestInterceptors != null) {
			List<RequestInterceptor> interceptors = new ArrayList<>(requestInterceptors.values());
			AnnotationAwareOrderComparator.sort(interceptors);
			builder.requestInterceptors(interceptors);
		}
		QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
		if (decode404) {
			builder.decode404();
		}
		ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(context,
				ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}

		Map<String, Capability> capabilities = getInheritedAwareInstances(context, Capability.class);
		if (capabilities != null) {
			capabilities.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
					.forEach(builder::addCapability);
		}
	}

	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config,
			Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		if (!refreshableClient) {
			connectTimeoutMillis = config.getConnectTimeout() != null ? config.getConnectTimeout()
					: connectTimeoutMillis;
			readTimeoutMillis = config.getReadTimeout() != null ? config.getReadTimeout() : readTimeoutMillis;
			followRedirects = config.isFollowRedirects() != null ? config.isFollowRedirects() : followRedirects;

			builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS, readTimeoutMillis,
					TimeUnit.MILLISECONDS, followRedirects));
		}

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		if (Objects.nonNull(config.getDefaultRequestHeaders())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate.headers(config.getDefaultRequestHeaders()));
		}

		if (Objects.nonNull(config.getDefaultQueryParameters())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate.queries(config.getDefaultQueryParameters()));
		}

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}

		if (config.getCapabilities() != null) {
			config.getCapabilities().stream().map(this::getOrInstantiate).forEach(builder::addCapability);
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return beanFactory != null ? beanFactory.getBean(tClass) : applicationContext.getBean(tClass);
		}
		catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		// 从上下文对象中获取实例对象
		T instance = context.getInstance(contextId, type);
		if (instance == null) {
			throw new IllegalStateException("No bean found of type " + type + " for " + contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return getOptional(context, type);
		}
		else {
			return context.getInstanceWithoutAncestors(contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(contextId, type);
		}
		else {
			return context.getInstancesWithoutAncestors(contextId, type);
		}
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context, HardCodedTarget<T> target) {
		// 获取客户端对象
		Client client = getOptional(context, Client.class);
		// 客户端对象不为空的情况下
		if (client != null) {
			// 将客户端对象设置到FeignBuilder
			builder.client(client);
			// 获取Targeter对象
			Targeter targeter = get(context, Targeter.class);
			// 生成
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
			"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?");
	}

	/**
	 * Meant to get Options bean from context with bean name.
	 * @param context context of Feign client
	 * @param contextId name of feign client
	 * @return returns Options found in context
	 */
	protected Request.Options getOptionsByName(FeignContext context, String contextId) {
		if (refreshableClient) {
			return context.getInstance(contextId, Request.Options.class.getCanonicalName() + "-" + contextId,
					Request.Options.class);
		}
		return null;
	}

	@Override
	public Object getObject() {
		return getTarget();
	}

	/**
	 * @param <T> the target type of the Feign client
	 * @return a {@link Feign} client created with the specified data and the context
	 * information
	 */
	<T> T getTarget() {
		// 提取Feign上下文
		FeignContext context = beanFactory != null ? beanFactory.getBean(FeignContext.class)
			: applicationContext.getBean(FeignContext.class);
		// 创建FeignBuilder对象
		Feign.Builder builder = feign(context);

		// 如果url数据为空
		if (!StringUtils.hasText(url)) {
			// 警告日志输出
			if (url != null && LOG.isWarnEnabled()) {
				LOG.warn("The provided URL is empty. Will try picking an instance via load-balancing.");
			} else if (LOG.isDebugEnabled()) {
				LOG.debug("URL not provided. Will use LoadBalancer.");
			}
			// 如果name属性不是以http开头的将会补充http://将其设置为url变量
			if (!name.startsWith("http")) {
				url = "http://" + name;
			} else {
				//  url设置为name值
				url = name;
			}
			// 清理空字符串和多余的斜杠
			url += cleanPath();
			// 创建FeignClient接口实现类
			return (T) loadBalance(builder, context, new HardCodedTarget<>(type, name, url));
		}
		// 如果存在url数据值并且没有以http
		if (StringUtils.hasText(url) && !url.startsWith("http")) {
			url = "http://" + url;
		}
		// 清理空字符串和多余的斜杠得到最终的路由地址
		String url = this.url + cleanPath();
		// 获取客户端接口实例
		Client client = getOptional(context, Client.class);
		// 如果客户端接口实例不为空
		if (client != null) {
			// 不同类型的情况下将包装源对象进行提取
			if (client instanceof FeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
			}
			if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((RetryableFeignBlockingLoadBalancerClient) client).getDelegate();
			}
			// 对FeignBuilder设置client对象
			builder.client(client);
		}
		// 获取Targeter对象
		Targeter targeter = get(context, Targeter.class);
		// 将FeignBuilder对象传入Targeter对象中进行包装生成最终对象
		return (T) targeter.target(this, builder, context, new HardCodedTarget<>(type, name, url));
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public void addCustomizer(FeignBuilderCustomizer customizer) {
		additionalCustomizers.add(customizer);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		applicationContext = context;
		beanFactory = context;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	public void setRefreshableClient(boolean refreshableClient) {
		this.refreshableClient = refreshableClient;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext)
				&& Objects.equals(beanFactory, that.beanFactory) && decode404 == that.decode404
				&& inheritParentContext == that.inheritParentContext && Objects.equals(fallback, that.fallback)
				&& Objects.equals(fallbackFactory, that.fallbackFactory) && Objects.equals(name, that.name)
				&& Objects.equals(path, that.path) && Objects.equals(type, that.type) && Objects.equals(url, that.url)
				&& Objects.equals(connectTimeoutMillis, that.connectTimeoutMillis)
				&& Objects.equals(readTimeoutMillis, that.readTimeoutMillis)
				&& Objects.equals(followRedirects, that.followRedirects)
				&& Objects.equals(refreshableClient, that.refreshableClient);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, beanFactory, decode404, inheritParentContext, fallback, fallbackFactory,
				name, path, type, url, readTimeoutMillis, connectTimeoutMillis, followRedirects, refreshableClient);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type).append(", ").append("name='")
				.append(name).append("', ").append("url='").append(url).append("', ").append("path='").append(path)
				.append("', ").append("decode404=").append(decode404).append(", ").append("inheritParentContext=")
				.append(inheritParentContext).append(", ").append("applicationContext=").append(applicationContext)
				.append(", ").append("beanFactory=").append(beanFactory).append(", ").append("fallback=")
				.append(fallback).append(", ").append("fallbackFactory=").append(fallbackFactory).append("}")
				.append("connectTimeoutMillis=").append(connectTimeoutMillis).append("}").append("readTimeoutMillis=")
				.append(readTimeoutMillis).append("}").append("followRedirects=").append(followRedirects)
				.append("refreshableClient=").append(refreshableClient).append("}").toString();
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

}
