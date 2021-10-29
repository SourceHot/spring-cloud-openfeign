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

package org.springframework.cloud.openfeign.support;

import feign.*;
import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.cloud.openfeign.annotation.*;
import org.springframework.cloud.openfeign.encoding.HttpEncoding;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import static org.springframework.cloud.openfeign.support.FeignUtils.addTemplateParameter;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * @author Spencer Gibb
 * @author Abhijit Sarkar
 * @author Halvdan Hoem Grelland
 * @author Aram Peres
 * @author Olga Maciaszek-Sharma
 * @author Aaron Whiteside
 * @author Artyom Romanenko
 * @author Darren Foong
 * @author Ram Anaswara
 */
public class SpringMvcContract extends Contract.BaseContract implements ResourceLoaderAware {

	/**
	 *ACCEPT字符串常量
	 */
	private static final String ACCEPT = "Accept";

	/**
	 * 内容类型常量
	 */
	private static final String CONTENT_TYPE = "Content-Type";

	/**
	 * String类型描述符
	 */
	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);

	/**
	 *Iterable类型描述符
	 */
	private static final TypeDescriptor ITERABLE_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(Iterable.class);

	/**
	 * 参数名称发现器
	 */
	private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

	/**
	 * 注解和注解处理器映射关系表
	 */
	private final Map<Class<? extends Annotation>, AnnotatedParameterProcessor> annotatedArgumentProcessors;

	/**
	 * 方法名称和处理方法的映射关系表
	 */
	private final Map<String, Method> processedMethods = new HashMap<>();

	/**
	 * 转换服务
	 */
	private final ConversionService conversionService;

	/**
	 * 转换服务工厂,主要用于获取Param.Expander对象
	 */
	private final ConvertingExpanderFactory convertingExpanderFactory;

	/**
	 * 资源加载器
	 */
	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	/**
	 * 是否解码斜线
	 */
	private final boolean decodeSlash;

	public SpringMvcContract() {
		this(Collections.emptyList());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors) {
		this(annotatedParameterProcessors, new DefaultConversionService());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
							 ConversionService conversionService) {
		this(annotatedParameterProcessors, conversionService, true);
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
							 ConversionService conversionService, boolean decodeSlash) {
		Assert.notNull(annotatedParameterProcessors, "Parameter processors can not be null.");
		Assert.notNull(conversionService, "ConversionService can not be null.");

		List<AnnotatedParameterProcessor> processors = getDefaultAnnotatedArgumentsProcessors();
		processors.addAll(annotatedParameterProcessors);

		annotatedArgumentProcessors = toAnnotatedArgumentProcessorMap(processors);
		this.conversionService = conversionService;
		convertingExpanderFactory = new ConvertingExpanderFactory(conversionService);
		this.decodeSlash = decodeSlash;
	}

	private static TypeDescriptor createTypeDescriptor(Method method, int paramIndex) {
		Parameter parameter = method.getParameters()[paramIndex];
		MethodParameter methodParameter = MethodParameter.forParameter(parameter);
		TypeDescriptor typeDescriptor = new TypeDescriptor(methodParameter);

		// Feign applies the Param.Expander to each element of an Iterable, so in those
		// cases we need to provide a TypeDescriptor of the element.
		if (typeDescriptor.isAssignableTo(ITERABLE_TYPE_DESCRIPTOR)) {
			TypeDescriptor elementTypeDescriptor = getElementTypeDescriptor(typeDescriptor);

			checkState(elementTypeDescriptor != null,
				"Could not resolve element type of Iterable type %s. Not declared?", typeDescriptor);

			typeDescriptor = elementTypeDescriptor;
		}
		return typeDescriptor;
	}

	private static TypeDescriptor getElementTypeDescriptor(TypeDescriptor typeDescriptor) {
		TypeDescriptor elementTypeDescriptor = typeDescriptor.getElementTypeDescriptor();
		// that means it's not a collection but it is iterable, gh-135
		if (elementTypeDescriptor == null && Iterable.class.isAssignableFrom(typeDescriptor.getType())) {
			ResolvableType type = typeDescriptor.getResolvableType().as(Iterable.class).getGeneric(0);
			if (type.resolve() == null) {
				return null;
			}
			return new TypeDescriptor(type, null, typeDescriptor.getAnnotations());
		}
		return elementTypeDescriptor;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	protected void processAnnotationOnClass(MethodMetadata data, Class<?> clz) {
		// 类对象上存在0个接口
		if (clz.getInterfaces().length == 0) {
			// 寻找类对象上的RequestMapping注解对象
			RequestMapping classAnnotation = findMergedAnnotation(clz, RequestMapping.class);
			// 如果RequestMapping注解对象存在
			if (classAnnotation != null) {
				// Prepend path from class annotation if specified
				// 如果注解中的value属性存在并且数量大于0
				if (classAnnotation.value().length > 0) {
					// 提取value中的第一个元素
					String pathValue = emptyToNull(classAnnotation.value()[0]);
					// 解析第一个元素
					pathValue = resolve(pathValue);
					// 如果没有使用斜杠开头将补充斜杠
					if (!pathValue.startsWith("/")) {
						pathValue = "/" + pathValue;
					}
					// 为方法元数据中的RequestTemplate对象设置路由地址
					data.template().uri(pathValue);
					// 确认是否解码斜杠值是否和方法元数据中的数据一样，如果不一样则需要重新设置
					if (data.template().decodeSlash() != decodeSlash) {
						data.template().decodeSlash(decodeSlash);
					}
				}
			}
		}
	}

	@Override
	public MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
		// 向成员变量processedMethods加入数据
		processedMethods.put(Feign.configKey(targetType, method), method);
		// 父类进行解析和验证
		MethodMetadata md = super.parseAndValidateMetadata(targetType, method);

		// 寻找处理类上RequestMapping的注解
		RequestMapping classAnnotation = findMergedAnnotation(targetType, RequestMapping.class);
		// 如果类上纯在RequestMapping注解
		if (classAnnotation != null) {
			// produces - use from class annotation only if method has not specified this
			// 如果方法元数据中的头信息不存在ACCEPT信息
			if (!md.template().headers().containsKey(ACCEPT)) {
				// 解析生产者相关内容
				parseProduces(md, method, classAnnotation);
			}

			// consumes -- use from class annotation only if method has not specified this
			// 如果方法元数据中的头信息不存在CONTENT_TYPE信息
			if (!md.template().headers().containsKey(CONTENT_TYPE)) {
				// 解析消费者相关内容
				parseConsumes(md, method, classAnnotation);
			}

			// headers -- class annotation is inherited to methods, always write these if
			// present
			// 解析头信息
			parseHeaders(md, method, classAnnotation);
		}
		return md;
	}

	@Override
	protected void processAnnotationOnMethod(MethodMetadata data, Annotation methodAnnotation, Method method) {
		// 判断方法注解是否是CollectionFormat类型
		if (methodAnnotation instanceof CollectionFormat) {
			// 寻找CollectionFormat注解
			CollectionFormat collectionFormat = findMergedAnnotation(method, CollectionFormat.class);
			// 将CollectionFormat数据值设置到RequestTemplate对象的collectionFormat属性中
			data.template().collectionFormat(collectionFormat.value());
		}

		// 如果方法注解不是RequestMapping类型并且没有包含RequestMapping注解跳过处理
		if (!(methodAnnotation instanceof RequestMapping)
			&& !methodAnnotation.annotationType().isAnnotationPresent(RequestMapping.class)) {
			return;
		}

		// 在方法上寻找RequestMapping注解信息
		RequestMapping methodMapping = findMergedAnnotation(method, RequestMapping.class);
		// HTTP Method
		// 获取RequestMapping注解中的HTTP请求方式
		RequestMethod[] methods = methodMapping.method();
		// 如果没有数据则默认使用GET请求
		if (methods.length == 0) {
			methods = new RequestMethod[]{RequestMethod.GET};
		}
		// 检查method
		checkOne(method, methods, "method");
		// 为方法元数据设置RequestTemplate中的方法信息
		data.template().method(Request.HttpMethod.valueOf(methods[0].name()));

		// path
		// 检查value数据
		checkAtMostOne(method, methodMapping.value(), "value");
		// 如果value数据超过0个
		if (methodMapping.value().length > 0) {
			// 提取value属性值中的第一个元素
			String pathValue = emptyToNull(methodMapping.value()[0]);
			// 如果存在第一个元素
			if (pathValue != null) {
				// 将第一个元素进行解析
				pathValue = resolve(pathValue);
				// Append path from @RequestMapping if value is present on method
				// 如果第一个元素不是以斜杠开头并且不是以斜杠结尾补充斜杠
				if (!pathValue.startsWith("/") && !data.template().path().endsWith("/")) {
					pathValue = "/" + pathValue;
				}
				// 为方法元数据对象设置路由地址
				data.template().uri(pathValue, true);
				// 确认是否解码斜杠值是否和方法元数据中的数据一样，如果不一样则需要重新设置
				if (data.template().decodeSlash() != decodeSlash) {
					data.template().decodeSlash(decodeSlash);
				}
			}
		}

		// produces
		// 处理produces
		parseProduces(data, method, methodMapping);

		// consumes
		// 处理consumes
		parseConsumes(data, method, methodMapping);

		// headers
		// 处理headers
		parseHeaders(data, method, methodMapping);

		// 设置indexToExpander属性为空map对象
		data.indexToExpander(new LinkedHashMap<>());
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value) && resourceLoader instanceof ConfigurableApplicationContext) {
			return ((ConfigurableApplicationContext) resourceLoader).getEnvironment().resolvePlaceholders(value);
		}
		return value;
	}

	private void checkAtMostOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && (values.length == 0 || values.length == 1),
			"Method %s can only contain at most 1 %s field. Found: %s", method.getName(), fieldName,
			values == null ? null : Arrays.asList(values));
	}

	private void checkOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && values.length == 1, "Method %s can only contain 1 %s field. Found: %s",
			method.getName(), fieldName, values == null ? null : Arrays.asList(values));
	}

	@Override
	protected boolean processAnnotationsOnParameter(MethodMetadata data, Annotation[] annotations, int paramIndex) {
		boolean isHttpAnnotation = false;

		AnnotatedParameterProcessor.AnnotatedParameterContext context = new SimpleAnnotatedParameterContext(data,
			paramIndex);
		Method method = processedMethods.get(data.configKey());
		for (Annotation parameterAnnotation : annotations) {
			AnnotatedParameterProcessor processor = annotatedArgumentProcessors
				.get(parameterAnnotation.annotationType());
			if (processor != null) {
				Annotation processParameterAnnotation;
				// synthesize, handling @AliasFor, while falling back to parameter name on
				// missing String #value():
				processParameterAnnotation = synthesizeWithMethodParameterNameAsFallbackValue(parameterAnnotation,
					method, paramIndex);
				isHttpAnnotation |= processor.processArgument(context, processParameterAnnotation, method);
			}
		}

		if (!isMultipartFormData(data) && isHttpAnnotation && data.indexToExpander().get(paramIndex) == null) {
			TypeDescriptor typeDescriptor = createTypeDescriptor(method, paramIndex);
			if (conversionService.canConvert(typeDescriptor, STRING_TYPE_DESCRIPTOR)) {
				Param.Expander expander = convertingExpanderFactory.getExpander(typeDescriptor);
				if (expander != null) {
					data.indexToExpander().put(paramIndex, expander);
				}
			}
		}
		return isHttpAnnotation;
	}

	private void parseProduces(MethodMetadata md, Method method, RequestMapping annotation) {
		// 获取RequestMapping注解中的produces数据
		String[] serverProduces = annotation.produces();
		// 确认是否存在produces数据，如果存在将获取第一个元素的数据
		String clientAccepts = serverProduces.length == 0 ? null : emptyToNull(serverProduces[0]);
		if (clientAccepts != null) {
			// 为方法元数据中的RequestTemplate设置头信息
			md.template().header(ACCEPT, clientAccepts);
		}
	}

	private void parseConsumes(MethodMetadata md, Method method, RequestMapping annotation) {
		// 获取RequestMapping注解中的consumes信息
		String[] serverConsumes = annotation.consumes();
		// 确认是否存在consumes数据，如果存在将获取第一个元素的数据
		String clientProduces = serverConsumes.length == 0 ? null : emptyToNull(serverConsumes[0]);
		if (clientProduces != null) {
			// 为方法元数据中的RequestTemplate设置头信息
			md.template().header(CONTENT_TYPE, clientProduces);
		}
	}

	private void parseHeaders(MethodMetadata md, Method method, RequestMapping annotation) {
		// TODO: only supports one header value per key
		// 如果RequestMapping注解中的头信息存在并且数量大于1
		if (annotation.headers() != null && annotation.headers().length > 0) {
			// 循环RequestMapping注解的头信息将数据内容放入到方法元数据中
			for (String header : annotation.headers()) {
				int index = header.indexOf('=');
				// 切分等号前后的数据作为头信息置入
				if (!header.contains("!=") && index >= 0) {
					md.template().header(resolve(header.substring(0, index)),
						resolve(header.substring(index + 1).trim()));
				}
			}
		}
	}

	private Map<Class<? extends Annotation>, AnnotatedParameterProcessor> toAnnotatedArgumentProcessorMap(
		List<AnnotatedParameterProcessor> processors) {
		Map<Class<? extends Annotation>, AnnotatedParameterProcessor> result = new HashMap<>();
		for (AnnotatedParameterProcessor processor : processors) {
			result.put(processor.getAnnotationType(), processor);
		}
		return result;
	}

	private List<AnnotatedParameterProcessor> getDefaultAnnotatedArgumentsProcessors() {

		List<AnnotatedParameterProcessor> annotatedArgumentResolvers = new ArrayList<>();

		annotatedArgumentResolvers.add(new MatrixVariableParameterProcessor());
		annotatedArgumentResolvers.add(new PathVariableParameterProcessor());
		annotatedArgumentResolvers.add(new RequestParamParameterProcessor());
		annotatedArgumentResolvers.add(new RequestHeaderParameterProcessor());
		annotatedArgumentResolvers.add(new QueryMapParameterProcessor());
		annotatedArgumentResolvers.add(new RequestPartParameterProcessor());

		return annotatedArgumentResolvers;
	}

	private Annotation synthesizeWithMethodParameterNameAsFallbackValue(Annotation parameterAnnotation, Method method,
																		int parameterIndex) {
		Map<String, Object> annotationAttributes = AnnotationUtils.getAnnotationAttributes(parameterAnnotation);
		Object defaultValue = AnnotationUtils.getDefaultValue(parameterAnnotation);
		if (defaultValue instanceof String && defaultValue.equals(annotationAttributes.get(AnnotationUtils.VALUE))) {
			Type[] parameterTypes = method.getGenericParameterTypes();
			String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
			if (shouldAddParameterName(parameterIndex, parameterTypes, parameterNames)) {
				annotationAttributes.put(AnnotationUtils.VALUE, parameterNames[parameterIndex]);
			}
		}
		return AnnotationUtils.synthesizeAnnotation(annotationAttributes, parameterAnnotation.annotationType(), null);
	}

	private boolean shouldAddParameterName(int parameterIndex, Type[] parameterTypes, String[] parameterNames) {
		// has a parameter name
		return parameterNames != null && parameterNames.length > parameterIndex
			// has a type
			&& parameterTypes != null && parameterTypes.length > parameterIndex;
	}

	private boolean isMultipartFormData(MethodMetadata data) {
		Collection<String> contentTypes = data.template().headers().get(HttpEncoding.CONTENT_TYPE);

		if (contentTypes != null && !contentTypes.isEmpty()) {
			String type = contentTypes.iterator().next();
			try {
				return Objects.equals(MediaType.valueOf(type), MediaType.MULTIPART_FORM_DATA);
			} catch (InvalidMediaTypeException ignored) {
				return false;
			}
		}

		return false;
	}

	private static class ConvertingExpanderFactory {

		private final ConversionService conversionService;

		ConvertingExpanderFactory(ConversionService conversionService) {
			this.conversionService = conversionService;
		}

		Param.Expander getExpander(TypeDescriptor typeDescriptor) {
			return value -> {
				Object converted = conversionService.convert(value, typeDescriptor, STRING_TYPE_DESCRIPTOR);
				return (String) converted;
			};
		}

	}

	private class SimpleAnnotatedParameterContext implements AnnotatedParameterProcessor.AnnotatedParameterContext {

		private final MethodMetadata methodMetadata;

		private final int parameterIndex;

		SimpleAnnotatedParameterContext(MethodMetadata methodMetadata, int parameterIndex) {
			this.methodMetadata = methodMetadata;
			this.parameterIndex = parameterIndex;
		}

		@Override
		public MethodMetadata getMethodMetadata() {
			return methodMetadata;
		}

		@Override
		public int getParameterIndex() {
			return parameterIndex;
		}

		@Override
		public void setParameterName(String name) {
			nameParam(methodMetadata, name, parameterIndex);
		}

		@Override
		public Collection<String> setTemplateParameter(String name, Collection<String> rest) {
			return addTemplateParameter(rest, name);
		}

	}

}
