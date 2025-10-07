package dev.mars.peegeeq.examples.springboot2bitemporal.config;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the reactive bi-temporal example application.
 * 
 * <p>Maps properties from the 'reactive-bitemporal' prefix in application.yml.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Component
@ConfigurationProperties(prefix = "reactive-bitemporal")
public class ReactiveBiTemporalProperties {
    
    private String profile = "development";
    private boolean enableRealTimeSubscriptions = true;
    private int queryPageSize = 100;
    
    public String getProfile() {
        return profile;
    }
    
    public void setProfile(String profile) {
        this.profile = profile;
    }
    
    public boolean isEnableRealTimeSubscriptions() {
        return enableRealTimeSubscriptions;
    }
    
    public void setEnableRealTimeSubscriptions(boolean enableRealTimeSubscriptions) {
        this.enableRealTimeSubscriptions = enableRealTimeSubscriptions;
    }
    
    public int getQueryPageSize() {
        return queryPageSize;
    }
    
    public void setQueryPageSize(int queryPageSize) {
        this.queryPageSize = queryPageSize;
    }
}

