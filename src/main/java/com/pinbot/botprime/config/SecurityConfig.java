package com.pinbot.botprime.config;


import com.pinbot.botprime.security.JwtAuthenticationFilter;
import com.pinbot.botprime.security.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1) Password encoder
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2) In-memory пользователи (для примера)
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder pw) {
        UserDetails bot = User.builder()
                .username("bot")
                .password(pw.encode("secret"))
                .roles("BOT")
                .build();
        return new InMemoryUserDetailsManager(bot);
    }

    // 3) AuthenticationProvider, связывает UserDetailsService + PasswordEncoder
    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService uds,
            PasswordEncoder pw
    ) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(pw);
        return p;
    }

    // 4) Чтобы AuthController мог инжектить AuthenticationManager
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

    // 5) JwtUtils из проперти, два параметра в application.properties:
    //    jwt.secret=… и jwt.expiration-ms=…
    @Bean
    public JwtUtils jwtUtils(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expMs
    ) {
        return new JwtUtils(secret, expMs);
    }

    // 6) Фильтр, зависит только от JwtUtils и UserDetailsService
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtUtils jwtUtils,
            UserDetailsService uds
    ) {
        return new JwtAuthenticationFilter(jwtUtils, uds);
    }

    // 7) Основная цепочка безопасности
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationProvider authProvider,
            JwtAuthenticationFilter jwtFilter
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**").permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authProvider)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}