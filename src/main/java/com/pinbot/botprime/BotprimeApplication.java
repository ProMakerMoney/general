package com.pinbot.botprime;

import com.pinbot.botprime.config.BybitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
// ↓ этот импорт
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling                                  // ← включаем @Scheduled
@EnableConfigurationProperties(BybitProperties.class)
public class BotprimeApplication {

	public static void main(String[] args) {
		SpringApplication.run(BotprimeApplication.class, args);
	}

}

