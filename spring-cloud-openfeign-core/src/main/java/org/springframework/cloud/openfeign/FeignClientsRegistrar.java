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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import feign.Request;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 * @author Michal Domagala
 * @author Marcin Grzejszczak
 * @author Olga Maciaszek-Sharma
 * @author Jasbir Singh
 */
class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar
	/**
	 * 资源加载器
	 */
	private ResourceLoader resourceLoader;
	/**
	 * 环境对象
	 */
	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
				+ "of fallback classes that implement the interface annotated by @FeignClient");
	}

	static String getName(String name) {
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		if (StringUtils.hasText(path)) {
			path = path.trim();
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
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * 注册bean对象
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 注册默认配置
		registerDefaultConfiguration(metadata, registry);
		// 注册feignClient对象
		registerFeignClients(metadata, registry);
	}

	private void registerDefaultConfiguration(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 提取EnableFeignClients注解的数据信息
		Map<String, Object> defaultAttrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		// 如果EnableFeignClients注解的数据信息存在并且有defaultConfiguration键
		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			// 计算名称
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			} else {
				name = "default." + metadata.getClassName();
			}
			// 注册客户端配置
			registerClientConfiguration(registry, name, defaultAttrs.get("defaultConfiguration"));
		}
	}

	public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {

		// 候选bean定义对象集合
		LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
		// 获取EnableFeignClients注解属性表
		Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName());
		// 获取EnableFeignClients注解中的clients数据信息
		final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");
		// 如果clients数据信息为空
		if (clients == null || clients.length == 0) {
			// 类路径扫描候选组件
			ClassPathScanningCandidateComponentProvider scanner = getScanner();
			// 设置资源加载器
			scanner.setResourceLoader(this.resourceLoader);
			// 添加过滤器，包含FeignClient注解的
			scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
			// 从EnableFeignClients属性表中获取基础扫描路径
			Set<String> basePackages = getBasePackages(metadata);
			// 包路径扫描
			for (String basePackage : basePackages) {
				// 将扫描到的bean定义加入到候选集合周
				candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
			}
		}
		//
		else {
			// 循环clients数据将类对象通过注解bean定义对象创建候选bean定义对象，加入到候选集合中
			for (Class<?> clazz : clients) {
				candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz));
			}
		}

		// 循环候选bean定义对象
		for (BeanDefinition candidateComponent : candidateComponents) {
			// 如果bean定义对象类型是AnnotatedBeanDefinition则进行处理
			if (candidateComponent instanceof AnnotatedBeanDefinition) {
				// verify annotated class is an interface
				AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
				// 获取bean的元信息
				AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
				Assert.isTrue(annotationMetadata.isInterface(), "@FeignClient can only be specified on an interface");

				// 获取FeignClient注解元信息
				Map<String, Object> attributes = annotationMetadata
					.getAnnotationAttributes(FeignClient.class.getCanonicalName());

				// 提取feignClient的名称
				String name = getClientName(attributes);
				// 注册feignClient注解中的配置类对象
				registerClientConfiguration(registry, name, attributes.get("configuration"));
				// 注册feignClient
				registerFeignClient(registry, annotationMetadata, attributes);
			}
		}
	}

	private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata,
									 Map<String, Object> attributes) {
		// 获取类名
		String className = annotationMetadata.getClassName();
		// 将类名转换为类对象
		Class clazz = ClassUtils.resolveClassName(className, null);
		// 获取bean工厂，如果传入的bean定义注册器是ConfigurableBeanFactory类型则直接进行转换，否则bean工厂将会是null
		ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory
			? (ConfigurableBeanFactory) registry : null;
		// 获取上下文ID
		String contextId = getContextId(beanFactory, attributes);
		// 获取名称
		String name = getName(attributes);
		// 获取用于创建FeignClient的工厂bean对象
		FeignClientFactoryBean factoryBean = new FeignClientFactoryBean();
		// 为工厂bean对象设置bean工厂
		factoryBean.setBeanFactory(beanFactory);
		// 为工厂bean对象设置名称
		factoryBean.setName(name);
		// 为工厂bean对象设置上下文ID
		factoryBean.setContextId(contextId);
		// 为工厂bean对象设置需要实例化的类型
		factoryBean.setType(clazz);
		// 为工厂bean设置是否可以刷新客户端
		factoryBean.setRefreshableClient(isClientRefreshEnabled());
		// 创建bean定义构造器
		BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(clazz, () -> {
			// 设置url
			factoryBean.setUrl(getUrl(beanFactory, attributes));
			// 设置path
			factoryBean.setPath(getPath(beanFactory, attributes));
			// 设置是否对404进行解码
			factoryBean.setDecode404(Boolean.parseBoolean(String.valueOf(attributes.get("decode404"))));
			// 获取fallback数据值
			Object fallback = attributes.get("fallback");
			// fallback 不为空的情况下将其设置到工厂bean中
			if (fallback != null) {
				factoryBean.setFallback(fallback instanceof Class ? (Class<?>) fallback
					: ClassUtils.resolveClassName(fallback.toString(), null));
			}
			// 获取fallbackFactory数据值
			Object fallbackFactory = attributes.get("fallbackFactory");
			// 在fallbackFactory数据值不为空的情况下设置给工厂bean
			if (fallbackFactory != null) {
				factoryBean.setFallbackFactory(fallbackFactory instanceof Class ? (Class<?>) fallbackFactory
					: ClassUtils.resolveClassName(fallbackFactory.toString(), null));
			}
			return factoryBean.getObject();
		});

		// 设置自动注入类型
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
		// 设置是懒加载
		definition.setLazyInit(true);
		// 验证属性表
		validate(attributes);

		// 获取bean定义对象
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
		// 设置factoryBeanObjectType属性为类名
		beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className);
		// 设置feignClientsRegistrarFactoryBean属性为工厂bean
		beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean);

		// has a default, won't be null
		// 获取primary属性
		boolean primary = (Boolean) attributes.get("primary");
		// 设置bean定义中的primary数据
		beanDefinition.setPrimary(primary);

		// 获取qualifiers数据集合
		String[] qualifiers = getQualifiers(attributes);
		// 如果qualifiers为空的情况下创建默认数据
		if (ObjectUtils.isEmpty(qualifiers)) {
			qualifiers = new String[]{contextId + "FeignClient"};
		}

		// 创建bean定义持有器
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, qualifiers);
		// 注册bean定义持有器
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
		// 注册Request.Options类型的bean定义
		registerOptionsBeanDefinition(registry, contextId);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		validateFallback(annotation.getClass("fallback"));
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		return getName(null, attributes);
	}

	String getName(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(beanFactory, name);
		return getName(name);
	}

	private String getContextId(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		contextId = resolve(beanFactory, contextId);
		return getName(contextId);
	}

	private String resolve(ConfigurableBeanFactory beanFactory, String value) {
		if (StringUtils.hasText(value)) {
			if (beanFactory == null) {
				return this.environment.resolvePlaceholders(value);
			}
			BeanExpressionResolver resolver = beanFactory.getBeanExpressionResolver();
			String resolved = beanFactory.resolveEmbeddedValue(value);
			if (resolver == null) {
				return resolved;
			}
			return String.valueOf(resolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null)));
		}
		return value;
	}

	private String getUrl(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String url = resolve(beanFactory, (String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String path = resolve(beanFactory, (String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String[] getQualifiers(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		List<String> qualifierList = new ArrayList<>(Arrays.asList((String[]) client.get("qualifiers")));
		qualifierList.removeIf(qualifier -> !StringUtils.hasText(qualifier));
		if (qualifierList.isEmpty() && getQualifier(client) != null) {
			qualifierList = Collections.singletonList(getQualifier(client));
		}
		return !qualifierList.isEmpty() ? qualifierList.toArray(new String[0]) : null;
	}

	private String getClientName(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String value = (String) client.get("contextId");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException(
				"Either 'name' or 'value' must be provided in @" + FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name, Object configuration) {
		// 创建FeignClientSpecification类型的bean定义对象
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(FeignClientSpecification.class);
		// 设置构造参数
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		// 注册bean定义
		registry.registerBeanDefinition(name + "." + FeignClientSpecification.class.getSimpleName(),
			builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * This method is meant to create {@link Request.Options} beans definition with
	 * refreshScope.
	 *
	 * @param registry  spring bean definition registry
	 * @param contextId name of feign client
	 */
	private void registerOptionsBeanDefinition(BeanDefinitionRegistry registry, String contextId) {
		// 确认是否允许刷新客户端，允许的情况下处理
		if (isClientRefreshEnabled()) {
			// 计算bean名称
			String beanName = Request.Options.class.getCanonicalName() + "-" + contextId;
			// 创建bean定义构造器
			BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder
				.genericBeanDefinition(OptionsFactoryBean.class);
			// 设置作用域为refresh，表示刷新作用域
			definitionBuilder.setScope("refresh");
			// 向bean定义构造器中加入contextId属性
			definitionBuilder.addPropertyValue("contextId", contextId);
			// 创建bean定义持有器
			BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(definitionBuilder.getBeanDefinition(),
				beanName);
			// 通过作用域代理器进行创建代理后的bean定义持有器
			definitionHolder = ScopedProxyUtils.createScopedProxy(definitionHolder, registry, true);
			// 进行bean定义注册
			BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
		}
	}

	private boolean isClientRefreshEnabled() {
		return environment.getProperty("feign.client.refresh-enabled", Boolean.class, false);
	}

}
