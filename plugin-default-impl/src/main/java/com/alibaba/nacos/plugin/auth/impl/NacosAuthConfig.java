/*
 * Copyright 1999-2021 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.plugin.auth.impl;

import com.alibaba.nacos.auth.config.AuthConfigs;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.core.code.ControllerMethodsCache;
import com.alibaba.nacos.plugin.auth.impl.configuration.ConditionOnLdapAuth;
import com.alibaba.nacos.plugin.auth.impl.constant.AuthConstants;
import com.alibaba.nacos.plugin.auth.impl.constant.AuthSystemTypes;
import com.alibaba.nacos.plugin.auth.impl.filter.JwtAuthenticationTokenFilter;
import com.alibaba.nacos.plugin.auth.impl.users.NacosUserDetailsServiceImpl;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.BeanIds;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Spring security config.
 *
 * @author Nacos
 */
@EnableGlobalMethodSecurity(prePostEnabled = true)
@EnableAutoConfiguration(exclude = LdapAutoConfiguration.class)
public class NacosAuthConfig extends WebSecurityConfigurerAdapter {
    
    private static final String SECURITY_IGNORE_URLS_SPILT_CHAR = ",";
    
    private static final String LOGIN_ENTRY_POINT = "/v1/auth/login";
    
    private static final String TOKEN_BASED_AUTH_ENTRY_POINT = "/v1/auth/**";
    
    private static final String DEFAULT_ALL_PATH_PATTERN = "/**";
    
    private static final String PROPERTY_IGNORE_URLS = "nacos.security.ignore.urls";
    
    @Value(("${nacos.core.auth.ldap.url:ldap://localhost:389}"))
    private String ldapUrl;
    
    @Value(("${nacos.core.auth.ldap.basedc:dc=example,dc=org}"))
    private String ldapBaseDc;
    
    @Value(("${nacos.core.auth.ldap.timeout:3000}"))
    private String ldapTimeOut;
    
    @Value(("${nacos.core.auth.ldap.userDn:cn=admin,dc=example,dc=org}"))
    private String userDn;
    
    @Value(("${nacos.core.auth.ldap.password:password}"))
    private String password;
    
    @Autowired
    private Environment env;
    
    @Autowired
    private JwtTokenManager tokenProvider;
    
    @Autowired
    private AuthConfigs authConfigs;
    
    @Autowired
    private NacosUserDetailsServiceImpl userDetailsService;
    
    @Autowired
    private LdapAuthenticationProvider ldapAuthenticationProvider;
    
    @Autowired
    private ControllerMethodsCache methodsCache;
    
    /**
     * secret key.
     */
    private String secretKey;
    
    /**
     * secret key byte array.
     */
    private byte[] secretKeyBytes;
    
    /**
     * Token validity time(seconds).
     */
    private long tokenValidityInSeconds;
    
    /**
     * Init.
     */
    @PostConstruct
    public void init() {
        methodsCache.initClassMethod("com.alibaba.nacos.plugin.auth.impl.controller");
        initProperties();
    }
    
    private void initProperties() {
        Properties properties = authConfigs.getAuthPluginProperties(AuthConstants.AUTH_PLUGIN_TYPE);
        String validitySeconds = properties
                .getProperty(AuthConstants.TOKEN_EXPIRE_SECONDS, AuthConstants.DEFAULT_TOKEN_EXPIRE_SECONDS);
        tokenValidityInSeconds = Long.parseLong(validitySeconds);
        secretKey = properties.getProperty(AuthConstants.TOKEN_SECRET_KEY, AuthConstants.DEFAULT_TOKEN_SECRET_KEY);
    }
    
    @Bean(name = BeanIds.AUTHENTICATION_MANAGER)
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
    
    @Override
    public void configure(WebSecurity web) {
        
        String ignoreUrls = null;
        if (AuthSystemTypes.NACOS.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
            ignoreUrls = DEFAULT_ALL_PATH_PATTERN;
        } else if (AuthSystemTypes.LDAP.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
            ignoreUrls = DEFAULT_ALL_PATH_PATTERN;
        }
        if (StringUtils.isBlank(authConfigs.getNacosAuthSystemType())) {
            ignoreUrls = env.getProperty(PROPERTY_IGNORE_URLS, DEFAULT_ALL_PATH_PATTERN);
        }
        if (StringUtils.isNotBlank(ignoreUrls)) {
            for (String each : ignoreUrls.trim().split(SECURITY_IGNORE_URLS_SPILT_CHAR)) {
                web.ignoring().antMatchers(each.trim());
            }
        }
    }
    
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        if (AuthSystemTypes.NACOS.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
            auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
        } else if (AuthSystemTypes.LDAP.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
            auth.authenticationProvider(ldapAuthenticationProvider);
        }
    }
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        
        if (StringUtils.isBlank(authConfigs.getNacosAuthSystemType())) {
            http.csrf().disable().cors()// We don't need CSRF for JWT based authentication
                    .and().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                    .authorizeRequests().requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                    .antMatchers(LOGIN_ENTRY_POINT).permitAll().and().authorizeRequests()
                    .antMatchers(TOKEN_BASED_AUTH_ENTRY_POINT).authenticated().and().exceptionHandling()
                    .authenticationEntryPoint(new JwtAuthenticationEntryPoint());
            // disable cache
            http.headers().cacheControl();
            
            http.addFilterBefore(new JwtAuthenticationTokenFilter(tokenProvider),
                    UsernamePasswordAuthenticationFilter.class);
        }
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    @Conditional(ConditionOnLdapAuth.class)
    public LdapTemplate ldapTemplate() {
        LdapContextSource contextSource = new LdapContextSource();
        final Map<String, Object> config = new HashMap(16);
        contextSource.setUrl(ldapUrl);
        contextSource.setBase(ldapBaseDc);
        contextSource.setUserDn(userDn);
        contextSource.setPassword(password);
        config.put("java.naming.ldap.attributes.binary", "objectGUID");
        config.put("com.sun.jndi.ldap.connect.timeout", ldapTimeOut);
        contextSource.setPooled(true);
        contextSource.setBaseEnvironmentProperties(config);
        contextSource.afterPropertiesSet();
        return new LdapTemplate(contextSource);
        
    }
    
    public byte[] getSecretKeyBytes() {
        if (secretKeyBytes == null) {
            try {
                secretKeyBytes = Decoders.BASE64.decode(secretKey);
            } catch (DecodingException e) {
                secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            }
            
        }
        return secretKeyBytes;
    }
    
    public long getTokenValidityInSeconds() {
        return tokenValidityInSeconds;
    }
}
