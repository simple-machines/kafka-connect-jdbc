/*
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.connect.jdbc.sink;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.confluent.connect.jdbc.sink.dialect.DbDialect;
import io.confluent.connect.jdbc.sink.metadata.FieldsMetadata;

import static io.confluent.connect.jdbc.sink.JdbcSinkConfig.InsertMode.INSERT;
import static io.confluent.connect.jdbc.sink.JdbcSinkConfig.InsertMode.INSERT_OR_UPDATE;

public class BufferedRecords {
  private static final Logger log = LoggerFactory.getLogger(BufferedRecords.class);

  private final String tableName;
  private final JdbcSinkConfig config;
  private final DbDialect dbDialect;
  private final DbStructure dbStructure;
  private final Connection connection;

  private List<SinkRecord> records = new ArrayList<>();
  private Schema keySchema;
  private Schema valueSchema;
  private FieldsMetadata fieldsMetadata;
  private PreparedStatement updatePreparedStatement;
  private PreparedStatement altUpdatePreparedStatement;
  private PreparedStatement deletePreparedStatement;
  private PreparedStatementsBinder preparedStatementsBinder;

  public BufferedRecords(JdbcSinkConfig config, String tableName, DbDialect dbDialect, DbStructure dbStructure, Connection connection) {
    this.tableName = tableName;
    this.config = config;
    this.dbDialect = dbDialect;
    this.dbStructure = dbStructure;
    this.connection = connection;
    preparedStatementsBinder = new PreparedStatementsBinder();
  }

  public List<SinkRecord> add(SinkRecord record) throws SQLException {
    boolean schemaChanged = false;
    if (!Objects.equals(keySchema, record.keySchema())) {
      keySchema = record.keySchema();
      schemaChanged = true;
    }
    if (record.valueSchema() != null && !Objects.equals(valueSchema, record.valueSchema())) {
      // For deletes, both the value and value schema come in as null.
      // We don't want to treat this as a schema change if key schemas is the same
      // otherwise we flush unnecessarily.
      valueSchema = record.valueSchema();
      schemaChanged = true;
    }

    final List<SinkRecord> flushed = new ArrayList<>();
    if (schemaChanged) {
      // Each batch needs to have the same schemas, so get the buffered records out
      flushed.addAll(flush());

      // re-initialize everything that depends on the record schema
      fieldsMetadata = FieldsMetadata.extract(tableName, config.pkMode, config.pkFields, config.fieldsWhitelist, record.keySchema(), record.valueSchema());
      dbStructure.createOrAmendIfNecessary(config, connection, tableName, fieldsMetadata);
      close();
      configurePreparedStatements();
    }

    records.add(record);
    if (records.size() >= config.batchSize) {
      flushed.addAll(flush());
    }
    return flushed;
  }

  public List<SinkRecord> flush() throws SQLException {
    if (config.insertMode == INSERT_OR_UPDATE) {
      return insertOrUpdateFlush();
    }
    if (records.isEmpty()) {
      return new ArrayList<>();
    }
    for (SinkRecord record : records) {
      log.info("Writing {}", record);
      preparedStatementsBinder.bindRecord(config, updatePreparedStatement, deletePreparedStatement, fieldsMetadata, record);
    }
    int totalUpdateCount = 0;
    boolean successNoInfo = false;
    try {
      for (int updateCount : updatePreparedStatement.executeBatch()) {
        if (updateCount == Statement.SUCCESS_NO_INFO) {
          successNoInfo = true;
        } else {
          totalUpdateCount += updateCount;
        }
      }
    } catch (BatchUpdateException bue) {
      String updateCounts = Arrays.toString(bue.getUpdateCounts());
      log.error("Batch failed updateCounts:{} message: {}",
          updateCounts, bue.getMessage());
      throw bue;
    }
    int totalDeleteCount = 0;
    if (deletePreparedStatement != null) {
      for (int updateCount : deletePreparedStatement.executeBatch()) {
        if (updateCount != Statement.SUCCESS_NO_INFO) {
          totalDeleteCount += updateCount;
        }
      }
    }

    final int expectedCount = updateRecordCount();
    log.trace("{} records:{} resulting in totalUpdateCount:{} totalDeleteCount:{}",
        config.insertMode, records.size(), totalUpdateCount, totalDeleteCount);
    if (totalUpdateCount != expectedCount && !successNoInfo && config.insertMode == INSERT) {
      throw new ConnectException(String.format("Update count (%d) did not sum up to total number of records inserted (%d)",
                                               totalUpdateCount, expectedCount));
    }
    if (successNoInfo) {
      log.info("{} records:{} , but no count of the number of rows it affected is available",
          config.insertMode, records.size());
    }

    final List<SinkRecord> flushedRecords = records;
    records = new ArrayList<>();
    return flushedRecords;
  }

  private int updateRecordCount() {
    int count = 0;
    for (SinkRecord record : records) {
      // ignore deletes
      if (record.value() != null || !config.deleteEnabled) {
        count++;
      }
    }
    return count;
  }


  /**
   * For databases such as Greenplum that don't have native support for upserts, this provides an
   * alternative slightly less efficient and non-atomic method to support the concept.
   * This approach also assumes that the last update for any key is the only one that matters.
   * In other words updates contain the full current state not just the field(s) that changed.
   *
   * We're also only supporting non-composite primary keys for now too to simplify the logic.
   *
   * @return list of records applied to target DB
   * @throws SQLException
   */
  private List<SinkRecord> insertOrUpdateFlush() throws SQLException {
    if (records.isEmpty()) {
      return new ArrayList<>();
    }

    // we only care about the latest record for each key.
    Map<Object, SinkRecord> latestRecords = new HashMap<>();
    String keyField = fieldsMetadata.keyFieldNames.iterator().next();
    for (SinkRecord record : records) {
      latestRecords.put(primaryKey(record, keyField), record);
    }
    String idQuery = dbDialect.getSelectKeysQuery(tableName, keyField, latestRecords.size());
    PreparedStatement idStatement = connection.prepareStatement(idQuery);
    preparedStatementsBinder.bindIdCheck(idStatement, fieldsMetadata, latestRecords.values());
    Set<Object> existingKeys = existingKeys(idStatement);
    for (Map.Entry<Object, SinkRecord> entry : latestRecords.entrySet()) {
      if (entry.getValue().value() == null) {
        if (existingKeys.contains(entry.getKey())) {
          log.debug("Queueing {} for delete", entry.getKey());
          preparedStatementsBinder.bindDelete(config, deletePreparedStatement, fieldsMetadata, entry.getValue());
        } else {
          log.debug("Skipping delete of unknown record {}", entry.getKey());
        }
      } else {
        if (existingKeys.contains(entry.getKey())) {
          log.debug("Queueing {} for update", entry.getKey());
          preparedStatementsBinder.bindUpdate(config, altUpdatePreparedStatement, fieldsMetadata, entry.getValue());
        } else {
          log.debug("Queueing {} for insert", entry.getKey());
          preparedStatementsBinder.bindInsert(config, updatePreparedStatement, fieldsMetadata, entry.getValue());
        }
      }
    }

    updatePreparedStatement.executeBatch();
    altUpdatePreparedStatement.executeBatch();
    deletePreparedStatement.executeBatch();

    final List<SinkRecord> flushedRecords = records;
    records = new ArrayList<>();
    return flushedRecords;
  }

  private Object primaryKey(SinkRecord record, String keyField) {
    assert fieldsMetadata.keyFieldNames.size() == 1;
    if (record.keySchema().type().isPrimitive()) {
      return record.key();
    }
    final Field field = record.keySchema().field(keyField);
    return ((Struct) record.key()).get(field);
  }

  private Set<Object> existingKeys(PreparedStatement idStatement) throws SQLException {
    Set<Object> keys = new HashSet<>();
    ResultSet resultSet = idStatement.executeQuery();
    while (resultSet.next()) {
      keys.add(resultSet.getObject(1));
    }
    return keys;
  }

  public void close() throws SQLException {
    if (updatePreparedStatement != null) {
      updatePreparedStatement.close();
      updatePreparedStatement = null;
    }
    if (altUpdatePreparedStatement != null) {
      altUpdatePreparedStatement.close();
      altUpdatePreparedStatement = null;
    }
    if (deletePreparedStatement != null) {
      deletePreparedStatement.close();
      deletePreparedStatement = null;
    }
  }

  private void configurePreparedStatements() throws SQLException {
    final String updateSql = getUpdateSql();
    final String deleteSql = getDeleteSql();
    updatePreparedStatement = connection.prepareStatement(updateSql);
    if (config.deleteEnabled && deleteSql != null) {
      deletePreparedStatement = connection.prepareStatement(deleteSql);
    } else {
      deletePreparedStatement = null;
    }
    if (config.insertMode == INSERT_OR_UPDATE) {
      String altUpdate = dbDialect.getUpdate(tableName, fieldsMetadata.keyFieldNames, fieldsMetadata.nonKeyFieldNames);
      altUpdatePreparedStatement = connection.prepareStatement(altUpdate);
      log.debug("{} sql: {} altSql: {} deleteSql: {}", config.insertMode, updateSql, altUpdate, deleteSql);
    } else {
      altUpdatePreparedStatement = null;
      log.debug("{} sql: {} deleteSql: {}", config.insertMode, updateSql, deleteSql);
    }
  }

  private String getUpdateSql() {
    switch (config.insertMode) {
      case INSERT:
      case INSERT_OR_UPDATE:
        return dbDialect.getInsert(tableName, fieldsMetadata.keyFieldNames, fieldsMetadata.nonKeyFieldNames);
      case UPSERT:
        if (fieldsMetadata.keyFieldNames.isEmpty()) {
          throw new ConnectException(String.format(
              "Write to table '%s' in UPSERT mode requires key field names to be known, check the primary key configuration", tableName
          ));
        }
        return dbDialect.getUpsertQuery(tableName, fieldsMetadata.keyFieldNames, fieldsMetadata.nonKeyFieldNames);
      case UPDATE:
        return dbDialect.getUpdate(tableName, fieldsMetadata.keyFieldNames, fieldsMetadata.nonKeyFieldNames);
      default:
        throw new ConnectException("Invalid insert mode");
    }
  }

  private String getDeleteSql() {
    String sql = null;
    if (config.deleteEnabled) {
      switch (config.pkMode) {
        case NONE:
        case KAFKA:
        case RECORD_VALUE:
          throw new ConnectException("Deletes are only supported for pk.mode record_key");
        case RECORD_KEY:
          if (fieldsMetadata.keyFieldNames.isEmpty()) {
            throw new ConnectException("Require primary keys to support delete");
          }
          sql = dbDialect.getDelete(tableName, fieldsMetadata.keyFieldNames);
          break;
      }
    }
    return sql;
  }


}
