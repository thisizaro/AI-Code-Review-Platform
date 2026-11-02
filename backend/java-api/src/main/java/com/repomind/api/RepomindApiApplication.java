package com.repomind.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RepomindProperties.class)
public class RepomindApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(RepomindApiApplication.class, args);
	}
}
