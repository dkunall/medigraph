package com.medigraph.platform.web;

import com.medigraph.platform.security.CurrentActorArgumentResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Correlation ids, {@code @CurrentActor}, RFC 7807 errors and the OpenAPI document (bearer-JWT scheme). */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(ProblemDetailsAdvice.class)
public class PlatformWebAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> r = new FilterRegistrationBean<>(new CorrelationIdFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // before security, so 401s carry the id too
        return r;
    }

    @Bean
    public WebMvcConfigurer currentActorResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentActorArgumentResolver());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI medigraphOpenApi(@Value("${spring.application.name:medigraph}") String name) {
        return new OpenAPI()
                .info(new Info().title(name).version("v1")
                        .description("MediGraph " + name + " API. All endpoints except probes need a bearer JWT (RS256)."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Only services with spring-jdbc on the classpath get the DuplicateKey mapping (the gateway has no database). */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "org.springframework.dao.DuplicateKeyException")
    @Import(DataAccessProblemAdvice.class)
    static class DataAccessAdviceConfiguration {
    }
}
