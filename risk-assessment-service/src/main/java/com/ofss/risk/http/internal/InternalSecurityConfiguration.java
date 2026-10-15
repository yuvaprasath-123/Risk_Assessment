package com.ofss.risk.http.internal;

import com.ofss.risk.http.error.ErrorResponseWriter;
import com.ofss.internal.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods=false)
public class InternalSecurityConfiguration {
    @Bean InternalServiceVerifier riskServiceVerifier(Environment env) { return InternalServiceVerifier.from(env,ServicePermissions.RISK); }
    @Bean @Order(1)
    SecurityFilterChain riskInternalSecurity(HttpSecurity http,InternalServiceVerifier verifier,ErrorResponseWriter errors) throws Exception {
        String path="/internal/v1/risk-assessments";
        return http.securityMatcher("/internal/**").csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,path+"/*").hasAuthority(ServicePermissions.authority(ServicePermissions.RISK_ASSESSMENT))
                .requestMatchers(HttpMethod.GET,path+"/*/factors").hasAuthority(ServicePermissions.authority(ServicePermissions.RISK_FACTORS))
                .requestMatchers(HttpMethod.GET,path+"/*/configuration-snapshot").hasAuthority(ServicePermissions.authority(ServicePermissions.RISK_CONFIGURATION)).anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((request,response,failure)->errors.unauthorized(request,response,false))
                .accessDeniedHandler((request,response,failure)->errors.write(request,response,403,"FORBIDDEN","Access is denied")))
            .addFilterBefore(new InternalAuthenticationFilter(verifier,errors),UsernamePasswordAuthenticationFilter.class).build();
    }
}
