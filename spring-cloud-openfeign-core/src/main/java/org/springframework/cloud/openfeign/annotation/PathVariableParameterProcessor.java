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
import org.springframework.web.bind.annotation.PathVariable;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link PathVariable} parameter processor.
 *
 * @author Jakub Narloch
 * @author Abhijit Sarkar
 * @see AnnotatedParameterProcessor
 */
public class PathVariableParameterProcessor implements AnnotatedParameterProcessor {

	// PathVariable注解类
	private static final Class<PathVariable> ANNOTATION = PathVariable.class;

	/**
	 * 获取注解类型
	 */
	@Override
	public Class<? extends Annotation> getAnnotationType() {
		return ANNOTATION;
	}

	@Override
	public boolean processArgument(AnnotatedParameterContext context, Annotation annotation, Method method) {
		// 提取注解的value值
		String name = ANNOTATION.cast(annotation).value();
		// 检查value是否为空，如果是则抛出异常
		checkState(emptyToNull(name) != null, "PathVariable annotation was empty on param %s.",
			context.getParameterIndex());
		// 上下文中设置参数名称
		context.setParameterName(name);

		// 从上下文中获取方法元数据
		MethodMetadata data = context.getMethodMetadata();
		// 将花括号和value值组合
		String varName = '{' + name + '}';
		// 满足下面三个条件
		// 1. 如果方法元数据中的url不包含组合后的值
		// 2. 方法元数据中的queryMap没有组合后的值
		// 3. 方法元数据中的headerMap没有组合后的值
		if (!data.template().url().contains(varName) && !searchMapValues(data.template().queries(), varName)
			&& !searchMapValues(data.template().headers(), varName)) {
			// 向方法元数据中加入formParams数据
			data.formParams().add(name);
		}
		return true;
	}

	private <K, V> boolean searchMapValues(Map<K, Collection<V>> map, V search) {
		Collection<Collection<V>> values = map.values();
		if (values == null) {
			return false;
		}
		for (Collection<V> entry : values) {
			if (entry.contains(search)) {
				return true;
			}
		}
		return false;
	}

}
