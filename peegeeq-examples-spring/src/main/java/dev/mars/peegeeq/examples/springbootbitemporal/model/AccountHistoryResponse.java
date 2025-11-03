package dev.mars.peegeeq.examples.springbootbitemporal.model;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent;

import java.util.List;

/**
 * Response model for account history queries.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
public class AccountHistoryResponse {
    
    private String accountId;
    private List<BiTemporalEvent<TransactionEvent>> transactions;
    private int totalCount;
    
    public AccountHistoryResponse(String accountId, List<BiTemporalEvent<TransactionEvent>> transactions) {
        this.accountId = accountId;
        this.transactions = transactions;
        this.totalCount = transactions.size();
    }
    
    public String getAccountId() {
        return accountId;
    }
    
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
    
    public List<BiTemporalEvent<TransactionEvent>> getTransactions() {
        return transactions;
    }
    
    public void setTransactions(List<BiTemporalEvent<TransactionEvent>> transactions) {
        this.transactions = transactions;
        this.totalCount = transactions.size();
    }
    
    public int getTotalCount() {
        return totalCount;
    }
}

