/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountTransferRepository
        extends JpaRepository<AccountTransferTransaction, Long>, JpaSpecificationExecutor<AccountTransferTransaction> {

    @Query("select att from AccountTransferTransaction att where att.accountTransferDetails.fromLoanAccount.id= :accountNumber and att.reversed=false")
    List<AccountTransferTransaction> findByFromLoanId(@Param("accountNumber") Long accountNumber);

    @Query("select att from AccountTransferTransaction att where (att.accountTransferDetails.fromLoanAccount.id= :accountNumber or att.accountTransferDetails.toLoanAccount.id=:accountNumber) and att.reversed=false order by att.id desc")
    List<AccountTransferTransaction> findAllByLoanId(@Param("accountNumber") Long accountNumber);

    @Query("select att from AccountTransferTransaction att where (att.accountTransferDetails.fromSavingsAccount.id= :savingAccountNumber and att.accountTransferDetails.toSavingsAccount.id=:vendorSavingsAccountNumber) and att.reversed=false and att.date = :disbursementDate and att.description = :transactionDescription order by att.id desc")
    List<AccountTransferTransaction> findAllFromSavingsAccountIdToVendorSavingsAccountIdForDateAndDescription(
            @Param("savingAccountNumber") Long savingAccountNumber, @Param("vendorSavingsAccountNumber") Long vendorSavingsAccountNumber,
            @Param("disbursementDate") LocalDate disbursementDate, @Param("transactionDescription") String transactionDescription);

    @Query("select att from AccountTransferTransaction att where att.toLoanTransaction.id= :loanTransactionId and att.reversed=false")
    AccountTransferTransaction findByToLoanTransactionId(@Param("loanTransactionId") Long loanTransactionId);

    @Query("select att from AccountTransferTransaction att where att.fromLoanTransaction.id IN :loanTransactions and att.reversed=false")
    List<AccountTransferTransaction> findByFromLoanTransactions(@Param("loanTransactions") Collection<Long> loanTransactions);
}
