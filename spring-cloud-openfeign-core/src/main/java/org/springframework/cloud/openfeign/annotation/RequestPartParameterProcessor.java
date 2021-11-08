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

import feign.MethodMetadata;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.web.bind.annotation.RequestPart;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link RequestPart} parameter processor.
 *
 * @author Aaron Whiteside
 * @see AnnotatedParameterProcessor
 */
public class RequestPartParameterProcessor implements AnnotatedParameterProcessor {

	private static final Class<RequestPart> ANNOTATION = RequestPart.class;

	@Override
	public Class<? extends Annotation> getAnnotationType() {
		return ANNOTATION;
	}

	@Override
	public boolean processArgument(AnnotatedParameterContext context, Annotation annotation, Method method) {
		// 获取参数索引
		int parameterIndex = context.getParameterIndex();
		// 获取方法元数据
		MethodMetadata data = context.getMethodMetadata();

		// 提取RequestPart注解的value值
		String name = ANNOTATION.cast(annotation).value();
		checkState(emptyToNull(name) != null, "RequestPart.value() was empty on parameter %s", parameterIndex);
		// 设置参数名称
		context.setParameterName(name);

		// form表单参数设置
		data.formParams().add(name);
		// 设置模板参数
		Collection<String> names = context.setTemplateParameter(name, data.indexToName().get(parameterIndex));
		// 向方法元数据设置索引和名称
		data.indexToName().put(parameterIndex, names);
		return true;
	}

}
