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
package org.apache.fineract.portfolio.savings.service;

import static org.apache.fineract.portfolio.savings.DepositsApiConstants.RECURRING_DEPOSIT_PRODUCT_RESOURCE_NAME;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.accountingRuleParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.chargesParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.taxGroupIdParamName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.PersistenceException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.data.DepositProductDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositProductAssembler;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositProduct;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositProductRepository;
import org.apache.fineract.portfolio.savings.exception.RecurringDepositProductNotFoundException;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringDepositProductWritePlatformServiceJpaRepositoryImpl implements RecurringDepositProductWritePlatformService {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringDepositProductWritePlatformServiceJpaRepositoryImpl.class);
    private final PlatformSecurityContext context;
    private final RecurringDepositProductRepository recurringDepositProductRepository;
    private final DepositProductDataValidator fromApiJsonDataValidator;
    private final DepositProductAssembler depositProductAssembler;
    private final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService;
    private final InterestRateChartAssembler chartAssembler;

    private final CodeValueRepositoryWrapper codeValueRepository;

    @Autowired
    public RecurringDepositProductWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context,
            final RecurringDepositProductRepository recurringDepositProductRepository,
            final DepositProductDataValidator fromApiJsonDataValidator, final DepositProductAssembler depositProductAssembler,
            final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService,
            final InterestRateChartAssembler chartAssembler, CodeValueRepositoryWrapper codeValueRepository) {
        this.context = context;
        this.recurringDepositProductRepository = recurringDepositProductRepository;
        this.fromApiJsonDataValidator = fromApiJsonDataValidator;
        this.depositProductAssembler = depositProductAssembler;

        this.accountMappingWritePlatformService = accountMappingWritePlatformService;
        this.chartAssembler = chartAssembler;
        this.codeValueRepository = codeValueRepository;
    }

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {

        try {
            this.fromApiJsonDataValidator.validateForRecurringDepositCreate(command.json());

            final RecurringDepositProduct product = this.depositProductAssembler.assembleRecurringDepositProduct(command);

            CodeValue productCategory = getLoanProductCategory(command);
            if (productCategory != null) {
                product.setProductCategory(productCategory);
            }

            CodeValue productType = getLoanProductType(command);
            if (productType != null) {
                product.setProductType(productType);
            }

            this.recurringDepositProductRepository.saveAndFlush(product);

            // save accounting mappings
            this.accountMappingWritePlatformService.createSavingProductToGLAccountMapping(product.getId(), command,
                    DepositAccountType.RECURRING_DEPOSIT);

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(command, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult update(final Long productId, final JsonCommand command) {

        try {
            this.context.authenticatedUser();
            this.fromApiJsonDataValidator.validateForRecurringDepositUpdate(command.json());

            final RecurringDepositProduct product = this.recurringDepositProductRepository.findById(productId)
                    .orElseThrow(() -> new RecurringDepositProductNotFoundException(productId));
            product.setHelpers(this.chartAssembler);

            CodeValue productCategory = getLoanProductCategory(command);
            if (productCategory != null) {
                product.setProductCategory(productCategory);
            }

            CodeValue productType = getLoanProductType(command);
            if (productType != null) {
                product.setProductType(productType);
            }

            if (productCategory != null || productType != null) {
                this.recurringDepositProductRepository.saveAndFlush(product);
            }

            final Map<String, Object> changes = product.update(command);

            if (changes.containsKey(chargesParamName)) {
                final Set<Charge> savingsProductCharges = this.depositProductAssembler.assembleListOfSavingsProductCharges(command,
                        product.currency().getCode());

                validateSpecifiedDueDateChargeIsAppliedWhenaddPenaltyOnMissedTargetSavingsIsTrue(command, savingsProductCharges);

                final boolean updated = product.update(savingsProductCharges);
                if (!updated) {
                    changes.remove(chargesParamName);
                }
            }

            if (changes.containsKey(taxGroupIdParamName)) {
                final TaxGroup taxGroup = this.depositProductAssembler.assembleTaxGroup(command);
                product.setTaxGroup(taxGroup);
                if (product.withHoldTax() && product.getTaxGroup() == null) {
                    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
                    final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                            .resource(RECURRING_DEPOSIT_PRODUCT_RESOURCE_NAME);
                    final Long taxGroupId = null;
                    baseDataValidator.reset().parameter(taxGroupIdParamName).value(taxGroupId).notBlank();
                    throw new PlatformApiDataValidationException(dataValidationErrors);
                }
            }

            // accounting related changes
            final boolean accountingTypeChanged = changes.containsKey(accountingRuleParamName);
            final Map<String, Object> accountingMappingChanges = this.accountMappingWritePlatformService
                    .updateSavingsProductToGLAccountMapping(product.getId(), command, accountingTypeChanged, product.getAccountingType(),
                            DepositAccountType.RECURRING_DEPOSIT);
            changes.putAll(accountingMappingChanges);

            if (!changes.isEmpty()) {
                this.recurringDepositProductRepository.saveAndFlush(product);
            }

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .with(changes).build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(command, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    private static void validateSpecifiedDueDateChargeIsAppliedWhenaddPenaltyOnMissedTargetSavingsIsTrue(JsonCommand command,
            Set<Charge> savingsProductCharges) {
        final Boolean addPenaltyOnMissedTargetSavings = command
                .booleanPrimitiveValueOfParameterNamed(SavingsApiConstants.ADD_PENALTY_ON_MISSED_TARGET_SAVINGS);
        if (addPenaltyOnMissedTargetSavings) {
            if (CollectionUtils.isEmpty(savingsProductCharges)) {
                throw new GeneralPlatformDomainRuleException(
                        "addPenaltyOnMissedTargetSavings.requires.a.specified.due.charge.of.type.flat.on.this.product",
                        "addPenaltyOnMissedTargetSavings requires a charge of ChargeTimeType [specified due date ] and ChargeCalculationType [ flat ] on this product");
            }
            List<Charge> chargeList = new ArrayList<>();

            for (Charge charge : savingsProductCharges) {
                if (ChargeCalculationType.fromInt(charge.getChargeCalculation()).equals(ChargeCalculationType.FLAT)
                        && ChargeTimeType.fromInt(charge.getChargeTimeType()).equals(ChargeTimeType.SPECIFIED_DUE_DATE)) {
                    chargeList.add(charge);
                }
            }
            if (chargeList.size() == 0) {
                throw new GeneralPlatformDomainRuleException(
                        "addPenaltyOnMissedTargetSavings.requires.a.specified.due.charge.of.type.flat.on.this.product.but.it's not.supplied",
                        "addPenaltyOnMissedTargetSavings requires a charge of ChargeTimeType [specified due date ] and ChargeCalculationType [ flat ]  on this product but it's not supplied");

            }
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult delete(final Long productId) {

        this.context.authenticatedUser();
        final RecurringDepositProduct product = this.recurringDepositProductRepository.findById(productId)
                .orElseThrow(() -> new RecurringDepositProductNotFoundException(productId));

        this.recurringDepositProductRepository.delete(product);

        return new CommandProcessingResultBuilder() //
                .withEntityId(product.getId()) //
                .build();
    }

    /*
     * Guaranteed to throw an exception no matter what the data integrity issue is.
     */
    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dae) {

        if (realCause.getMessage().contains("sp_unq_name")) {

            final String name = command.stringValueOfParameterNamed("name");
            throw new PlatformDataIntegrityException("error.msg.product.savings.duplicate.name",
                    "Recurring Deposit product with name `" + name + "` already exists", "name", name);
        } else if (realCause.getMessage().contains("sp_unq_short_name")) {

            final String shortName = command.stringValueOfParameterNamed("shortName");
            throw new PlatformDataIntegrityException("error.msg.product.savings.duplicate.short.name",
                    "Recurring Deposit product with short name `" + shortName + "` already exists", "shortName", shortName);
        }

        logAsErrorUnexpectedDataIntegrityException(dae);
        throw new PlatformDataIntegrityException("error.msg.savingsproduct.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource.");
    }

    private void logAsErrorUnexpectedDataIntegrityException(final Exception dae) {
        LOG.error("Error occured.", dae);
    }

    @Nullable
    private CodeValue getLoanProductType(JsonCommand command) {
        CodeValue productType = null;
        final Long productTypeId = command.longValueOfParameterNamed(SavingsApiConstants.savingsProductTypeIdParamName);
        if (productTypeId != null) {
            productType = this.codeValueRepository.findOneByCodeNameAndIdWithNotFoundDetection(SavingsApiConstants.SAVINGS_PRODUCT_TYPE,
                    productTypeId);
        }
        return productType;
    }

    @Nullable
    private CodeValue getLoanProductCategory(JsonCommand command) {
        CodeValue productCategory = null;
        final Long productCategoryId = command.longValueOfParameterNamed(SavingsApiConstants.savingsProductCategoryIdParamName);
        if (productCategoryId != null) {
            productCategory = this.codeValueRepository
                    .findOneByCodeNameAndIdWithNotFoundDetection(SavingsApiConstants.SAVINGS_PRODUCT_CATEGORY, productCategoryId);
        }
        return productCategory;
    }
}
