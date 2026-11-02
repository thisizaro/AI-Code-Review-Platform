package com.repomind.api.web;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {

	/** Numbers, booleans and arrays are not silently turned into strings: {"question": 5} is a client error. */
	@Bean
	Jackson2ObjectMapperBuilderCustomizer strictStrings() {
		return builder -> builder.postConfigurer(mapper -> {
			var textual = mapper.coercionConfigFor(LogicalType.Textual);
			textual.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
			textual.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
			textual.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		});
	}
}
