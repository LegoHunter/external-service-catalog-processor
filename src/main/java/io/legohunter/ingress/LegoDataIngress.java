package io.legohunter.ingress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class
})
public class LegoDataIngress {

	public static void main(String[] args) {
		SpringApplication.run(LegoDataIngress.class, args);
	}

}
