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
import java.util.Map;
import java.util.stream.Collectors;

import feign.MethodMetadata;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.web.bind.annotation.MatrixVariable;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link MatrixVariable} annotation processor.
 *
 * Can expand maps or single objects. Values are assigned from the objects
 * {@code toString()} method.
 *
 * @author Matt King
 * @see AnnotatedParameterProcessor
 */
public class MatrixVariableParameterProcessor implements AnnotatedParameterProcessor {

	private static final Class<MatrixVariable> ANNOTATION = MatrixVariable.class;

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
		// 提取MatrixVariable注解的value数据
		String name = ANNOTATION.cast(annotation).value();

		// 如果value数据为空抛出异常
		checkState(emptyToNull(name) != null, "MatrixVariable annotation was empty on param %s.",
			context.getParameterIndex());

		// 设置参数名称
		context.setParameterName(name);
		// 如果参数类型是Map
		if (Map.class.isAssignableFrom(parameterType)) {
			data.indexToExpander().put(parameterIndex, this::expandMap);
		} else {
			data.indexToExpander().put(parameterIndex, object -> ";" + name + "=" + object.toString());
		}

		return true;
	}

	private String expandMap(Object object) {
		Map<String, Object> paramMap = (Map) object;

		return paramMap.keySet().stream().filter(key -> paramMap.get(key) != null)
				.map(key -> ";" + key + "=" + paramMap.get(key).toString()).collect(Collectors.joining());
	}

}
