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

package org.springframework.cloud.openfeign;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;

import feign.MethodMetadata;

/**
 * Feign contract method parameter processor.
 *
 * @author Jakub Narloch
 * @author Abhijit Sarkar
 */
public interface AnnotatedParameterProcessor {

	/**
	 * Retrieves the processor supported annotation type.
	 * 获取注解类型
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType();

	/**
	 * Process the annotated parameter.
	 * 处理注解
	 * @param context the parameter context
	 * @param annotation the annotation instance
	 * @param method the method that contains the annotation
	 * @return whether the parameter is http
	 */
	boolean processArgument(AnnotatedParameterContext context, Annotation annotation, Method method);

	/**
	 * Specifies the parameter context.
	 *
	 * @author Jakub Narloch
	 */
	interface AnnotatedParameterContext {

		/**
		 * Retrieves the method metadata.
		 * 获取方法元数据
		 * @return the method metadata
		 */
		MethodMetadata getMethodMetadata();

		/**
		 * Retrieves the index of the parameter.
		 * 获取参数索引
		 * @return the parameter index
		 */
		int getParameterIndex();

		/**
		 * Sets the parameter name.
		 * 设置参数名称
		 * @param name the name of the parameter
		 */
		void setParameterName(String name);

		/**
		 * Sets the template parameter.
		 * 设置模板参数
		 * @param name the template parameter
		 * @param rest the existing parameter values
		 * @return parameters
		 */
		Collection<String> setTemplateParameter(String name, Collection<String> rest);

	}

}
