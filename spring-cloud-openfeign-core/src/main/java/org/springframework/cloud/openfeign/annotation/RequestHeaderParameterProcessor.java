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
import org.springframework.web.bind.annotation.RequestHeader;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link RequestHeader} parameter processor.
 *
 * @author Jakub Narloch
 * @author Abhijit Sarkar
 * @see AnnotatedParameterProcessor
 */
public class RequestHeaderParameterProcessor implements AnnotatedParameterProcessor {

	private static final Class<RequestHeader> ANNOTATION = RequestHeader.class;

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

		// 如果参数类型是map
		if (Map.class.isAssignableFrom(parameterType)) {
			// 如果方法元数据中的headerMapIndex数据不为空抛出异常
			checkState(data.headerMapIndex() == null, "Header map can only be present once.");
			// 设置headerMapIndex数据
			data.headerMapIndex(parameterIndex);
			return true;
		}

		// 提取RequestHeader注解的value数据
		String name = ANNOTATION.cast(annotation).value();
		// 如果value数据为空抛出异常
		checkState(emptyToNull(name) != null, "RequestHeader.value() was empty on parameter %s", parameterIndex);
		// 设置参数名称
		context.setParameterName(name);

		// 设置头信息
		Collection<String> header = context.setTemplateParameter(name, data.template().headers().get(name));
		data.template().header(name, header);
		return true;
	}

}
