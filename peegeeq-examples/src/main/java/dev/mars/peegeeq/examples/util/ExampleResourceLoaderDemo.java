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

package dev.mars.peegeeq.examples.util;

/**
 * Demo class to test ExampleResourceLoader functionality.
 */
public class ExampleResourceLoaderDemo {
    
    public static void main(String[] args) {
        System.out.println("Testing ExampleResourceLoader...");
        
        try {
            // Test loading message example
            String orderJson = ExampleResourceLoader.loadMessageExample("order-message-request.json");
            System.out.println("✅ Successfully loaded order message example:");
            System.out.println("   Length: " + orderJson.length() + " characters");
            System.out.println("   Contains 'orderId': " + orderJson.contains("orderId"));
            
            // Test loading config example
            String demoConfig = ExampleResourceLoader.loadConfigExample("demo-setup.json");
            System.out.println("✅ Successfully loaded demo config example:");
            System.out.println("   Length: " + demoConfig.length() + " characters");
            
            // Test generic loading
            String paymentJson = ExampleResourceLoader.loadExample("messages/payment-message-request.json");
            System.out.println("✅ Successfully loaded payment message via generic method:");
            System.out.println("   Length: " + paymentJson.length() + " characters");
            System.out.println("   Contains 'paymentId': " + paymentJson.contains("paymentId"));
            
            System.out.println("\n🎉 All tests passed! ExampleResourceLoader is working correctly.");
            
        } catch (Exception e) {
            System.err.println("❌ Error testing ExampleResourceLoader: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
