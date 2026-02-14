package io.legohunter.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class
})
public class ExternalServiceCatalogProcessorApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExternalServiceCatalogProcessorApplication.class, args);
	}

}
