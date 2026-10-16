package com.ofss.risk.http.security;

import com.ofss.risk.http.AssessmentReadController;
import com.ofss.risk.http.error.ErrorResponseWriter;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean UserDetailsService localAccountsDisabled() { return username->{throw new UsernameNotFoundException("Local accounts are not supported");}; }
    @Bean @org.springframework.core.annotation.Order(2)
    SecurityFilterChain riskSecurity(HttpSecurity http,JwtVerifier verifier,ErrorResponseWriter errors) throws Exception {
        return http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable())
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,AssessmentReadController.PATH+"/*",AssessmentReadController.PATH+"/*/score-breakdown",
                AssessmentReadController.PATH+"/*/factors",AssessmentReadController.PATH+"/*/configuration-snapshot").hasRole("FINANCE_MANAGER").anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((request,response,failure)->errors.unauthorized(request,response,false))
                .accessDeniedHandler((request,response,failure)->errors.write(request,response,403,"FORBIDDEN","Access is denied")))
            .addFilterBefore(new JwtAuthenticationFilter(verifier,errors),UsernamePasswordAuthenticationFilter.class).build();
    }
}
