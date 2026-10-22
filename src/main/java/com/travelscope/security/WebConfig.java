package com.travelscope.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册认证拦截器
 * <p>
 * 生效范围 {@code /api/**}（context-path /api），放行登录与注册端点
 * {@code /api/auth/login}、{@code /api/auth/register}（认证本身的入口）。
 * {@code /api/chat/images/**}（私人讲解员图片能力端点）同样放行——<img> 标签不带
 * Bearer，端点自身以 nonce（objectKey 的 uuid 段）作 capability token 校验。
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/auth/register", "/chat/images/**");
    }
}
