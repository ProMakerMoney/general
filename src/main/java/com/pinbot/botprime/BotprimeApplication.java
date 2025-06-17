package com.pinbot.botprime;

import com.pinbot.botprime.config.BybitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BybitProperties.class)
public class BotprimeApplication {

	public static void main(String[] args) {
		SpringApplication.run(BotprimeApplication.class, args);
	}

}
