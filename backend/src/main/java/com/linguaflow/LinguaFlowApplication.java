package com.linguaflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LinguaFlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(LinguaFlowApplication.class, args);
	}

}
