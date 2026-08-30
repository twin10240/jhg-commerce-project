package com.jhg.hgpage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HgpageApplication {

	public static void main(String[] args) {
		SpringApplication.run(HgpageApplication.class, args);
	}

}
