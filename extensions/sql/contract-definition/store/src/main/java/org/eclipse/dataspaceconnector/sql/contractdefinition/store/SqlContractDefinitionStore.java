/*
 *  Copyright (c) 2022 Daimler TSS GmbH
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Daimler TSS GmbH - Initial API and Implementation
 *
 */

package org.eclipse.dataspaceconnector.sql.contractdefinition.store;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dataspaceconnector.policy.model.Policy;
import org.eclipse.dataspaceconnector.spi.asset.AssetSelectorExpression;
import org.eclipse.dataspaceconnector.spi.contract.offer.store.ContractDefinitionStore;
import org.eclipse.dataspaceconnector.spi.persistence.EdcPersistenceException;
import org.eclipse.dataspaceconnector.spi.query.QuerySpec;
import org.eclipse.dataspaceconnector.spi.transaction.TransactionContext;
import org.eclipse.dataspaceconnector.spi.transaction.datasource.DataSourceRegistry;
import org.eclipse.dataspaceconnector.spi.types.domain.contract.offer.ContractDefinition;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Stream;
import javax.sql.DataSource;

import static org.eclipse.dataspaceconnector.sql.SqlQueryExecutor.executeQuery;

public class SqlContractDefinitionStore implements ContractDefinitionStore {
    private static final String SQL_SAVE_CLAUSE_TEMPLATE = String.format("INSERT INTO %s (%s, %s, %s, %s) VALUES (?, ?, ?, ?)",
            SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ACCESS_POLICY,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_CONTRACT_POLICY,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_SELECTOR);
    private static final String SQL_COUNT_CLAUSE_TEMPLATE = String.format("SELECT COUNT (%s) FROM %s WHERE %s = ?",
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID);
    private static final String SQL_UPDATE_CLAUSE_TEMPLATE = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ? WHERE %s = ?",
            SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ACCESS_POLICY,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_CONTRACT_POLICY,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_SELECTOR,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID);
    private static final String SQL_DELETE_CLAUSE_TEMPLATE = String.format("DELETE FROM %s WHERE %s = ?",
            SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE,
            SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID);
    private static final String SQL_FIND_CLAUSE_TEMPLATE = String.format("SELECT * from %s",
            SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE);
    private final ObjectMapper objectMapper;
    private final DataSourceRegistry dataSourceRegistry;
    private final String dataSourceName;
    private final TransactionContext transactionContext;

    public SqlContractDefinitionStore(DataSourceRegistry dataSourceRegistry, String dataSourceName, TransactionContext transactionContext, ObjectMapper objectMapper) {
        this.dataSourceRegistry = Objects.requireNonNull(dataSourceRegistry);
        this.dataSourceName = Objects.requireNonNull(dataSourceName);
        this.transactionContext = Objects.requireNonNull(transactionContext);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    ContractDefinition mapResultSet(ResultSet resultSet) throws Exception {
        return ContractDefinition.Builder.newInstance()
                .id(resultSet.getString(SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ID))
                .accessPolicy(objectMapper.readValue(resultSet.getString(SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_ACCESS_POLICY), Policy.class))
                .contractPolicy(objectMapper.readValue(resultSet.getString(SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_CONTRACT_POLICY), Policy.class))
                .selectorExpression(objectMapper.readValue(resultSet.getString(SqlContractDefinitionTables.CONTRACT_DEFINITION_COLUMN_SELECTOR), AssetSelectorExpression.class))
                .build();
    }

    @Override
    public @NotNull Collection<ContractDefinition> findAll() {
        try (var connection = getConnection()) {
            return executeQuery(
                    connection,
                    this::mapResultSet,
                    String.format(SQL_FIND_CLAUSE_TEMPLATE, SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE));
        } catch (Exception exception) {
            throw new EdcPersistenceException(exception);
        }
    }

    @Override
    public @NotNull Stream<ContractDefinition> findAll(QuerySpec spec) {
        Objects.requireNonNull(spec);

        var limit = Limit.Builder.newInstance()
                .limit(spec.getLimit())
                .offset(spec.getOffset())
                .build();

        var query = SQL_FIND_CLAUSE_TEMPLATE + " " + limit.getStatement();

        try (var connection = getConnection()) {
            var definitions = executeQuery(
                    connection,
                    this::mapResultSet,
                    String.format(query, SqlContractDefinitionTables.CONTRACT_DEFINITION_TABLE));
            return definitions.stream();
        } catch (Exception exception) {
            throw new EdcPersistenceException(exception);
        }
    }

    @Override
    public void save(Collection<ContractDefinition> definitions) {
        Objects.requireNonNull(definitions);

        transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                for (var definition : definitions) {
                    executeQuery(connection, SQL_SAVE_CLAUSE_TEMPLATE,
                            definition.getId(),
                            objectMapper.writeValueAsString(definition.getAccessPolicy()),
                            objectMapper.writeValueAsString(definition.getContractPolicy()),
                            objectMapper.writeValueAsString(definition.getSelectorExpression()));
                }
            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });
    }

    @Override
    public void save(ContractDefinition definition) {
        save(Collections.singletonList(definition));
    }

    @Override
    public void update(ContractDefinition definition) {
        Objects.requireNonNull(definition);

        try (var connection = getConnection()) {
            if (executeQuery(connection, (resultSet) -> resultSet.getLong(1), SQL_COUNT_CLAUSE_TEMPLATE, definition.getId()).iterator().next() <= 0) {
                throw new EdcPersistenceException(String.format("Cannot update. Contract Definition with ID '%s' does not exist.", definition.getId()));
            }
            transactionContext.execute(() -> {
                try {
                    executeQuery(connection, SQL_UPDATE_CLAUSE_TEMPLATE,
                            definition.getId(),
                            objectMapper.writeValueAsString(definition.getAccessPolicy()),
                            objectMapper.writeValueAsString(definition.getContractPolicy()),
                            objectMapper.writeValueAsString(definition.getSelectorExpression()),
                            definition.getId());
                } catch (JsonProcessingException e) {
                    throw new EdcPersistenceException(e);
                }
            });
        } catch (Exception e) {
            if (e instanceof EdcPersistenceException) {
                throw (EdcPersistenceException) e;
            }
            throw new EdcPersistenceException(e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        Objects.requireNonNull(id);

        transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                executeQuery(connection, SQL_DELETE_CLAUSE_TEMPLATE,
                        id);
            } catch (Exception e) {
                throw new EdcPersistenceException(e.getMessage(), e);
            }
        });
    }

    private DataSource getDataSource() {
        return Objects.requireNonNull(dataSourceRegistry.resolve(dataSourceName), String.format("DataSource %s could not be resolved", dataSourceName));
    }

    private Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }
}