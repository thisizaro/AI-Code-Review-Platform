package com.repomind.api.web;

import com.repomind.api.RepomindProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final String origin;

	public CorsConfig(RepomindProperties properties) {
		this.origin = properties.corsOrigin();
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET", "POST", "OPTIONS").allowedHeaders("*");
	}
}
