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

package org.springframework.cloud.openfeign.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

import feign.MethodMetadata;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.web.bind.annotation.RequestParam;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link RequestParam} parameter processor.
 *
 * @author Jakub Narloch
 * @author Abhijit Sarkar
 * @see AnnotatedParameterProcessor
 */
public class RequestParamParameterProcessor implements AnnotatedParameterProcessor {

	private static final Class<RequestParam> ANNOTATION = RequestParam.class;

	@Override
	public Class<? extends Annotation> getAnnotationType() {
		return ANNOTATION;
	}

	@Override
	public boolean processArgument(AnnotatedParameterContext context, Annotation annotation, Method method) {
		// 从上下文中获取参数索引
		int parameterIndex = context.getParameterIndex();
		// 获取参数索引对应的参数类型
		Class<?> parameterType = method.getParameterTypes()[parameterIndex];
		// 获取方法元数据
		MethodMetadata data = context.getMethodMetadata();

		// 如果参数类型是Map
		if (Map.class.isAssignableFrom(parameterType)) {
			checkState(data.queryMapIndex() == null, "Query map can only be present once.");
			data.queryMapIndex(parameterIndex);

			return true;
		}

		// 提取注解
		RequestParam requestParam = ANNOTATION.cast(annotation);
		// 提取RequestParam注解的value值
		String name = requestParam.value();
		// 如果value值为空则抛出异常
		checkState(emptyToNull(name) != null, "RequestParam.value() was empty on parameter %s", parameterIndex);
		// 设置参数名称
		context.setParameterName(name);

		// 设置requestTemplate参数
		Collection<String> query = context.setTemplateParameter(name, data.template().queries().get(name));
		// 设置query参数
		data.template().query(name, query);
		return true;
	}

}
