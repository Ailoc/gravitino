/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.sqlserver.operation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for how the SQL Server catalog enumerates schemas.
 *
 * <p>mssql-jdbc answers {@code getSchemas} with zero rows when a non-null catalog is combined with
 * a null schema pattern, which is exactly the combination {@code listDatabases} needs. Naming the
 * catalog would therefore silently enumerate nothing, and the assertion here stubs {@link
 * Connection#getCatalog()} to a real database name so that it distinguishes the two.
 */
public class TestSqlServerSchemaOperationsMetadata {

  private static final String DATABASE = "master";

  /**
   * The catalog argument has to stay null. Leaving it unfiltered widens nothing, because the
   * connection is already bound to the single database named by {@code jdbc-database}.
   */
  @Test
  public void testListDatabasesLeavesCatalogUnfiltered() throws SQLException {
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet resultSet = mock(ResultSet.class);
    TestableSqlServerSchemaOperations operations = operationsReturning(metaData, resultSet);

    when(resultSet.next()).thenReturn(true, true, true, false);
    // The driver reports the fixed database roles as schemas too, so they are filtered out here.
    when(resultSet.getString(1)).thenReturn("dbo", "sys", "gravitino");

    List<String> schemas = operations.listDatabases();

    Assertions.assertEquals(Arrays.asList("dbo", "gravitino"), schemas);
    verify(metaData).getSchemas(null, null);
  }

  @Test
  public void testListDatabasesWithoutSchemasIsEmpty() throws SQLException {
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet resultSet = mock(ResultSet.class);
    TestableSqlServerSchemaOperations operations = operationsReturning(metaData, resultSet);

    when(resultSet.next()).thenReturn(false);

    Assertions.assertTrue(operations.listDatabases().isEmpty());
    verify(metaData).getSchemas(null, null);
  }

  /**
   * The driver treats the schema argument as a {@code LIKE} pattern, so {@code exist} still has to
   * compare names exactly; without that a schema named {@code a_c} would match {@code abc}.
   */
  @Test
  public void testSchemaExistsComparesNamesExactly() throws SQLException {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet resultSet = mock(ResultSet.class);

    when(connection.getCatalog()).thenReturn(DATABASE);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getSchemas(null, "a_c")).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("abc");

    TestableSqlServerSchemaOperations operations = new TestableSqlServerSchemaOperations(null);

    Assertions.assertFalse(operations.schemaExists(connection, "a_c"));
    verify(metaData).getSchemas(null, "a_c");
  }

  @Test
  public void testSchemaExistsFindsExactMatch() throws SQLException {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet resultSet = mock(ResultSet.class);

    when(connection.getCatalog()).thenReturn(DATABASE);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getSchemas(null, "dbo")).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, true, false);
    when(resultSet.getString(1)).thenReturn("db_accessadmin", "dbo");

    TestableSqlServerSchemaOperations operations = new TestableSqlServerSchemaOperations(null);

    Assertions.assertTrue(operations.schemaExists(connection, "dbo"));
  }

  /**
   * {@code exist} goes through the same metadata read, so it has to leave the catalog unfiltered as
   * well; the shared implementation queries {@code information_schema.SCHEMATA}, which SQL Server
   * spells differently.
   */
  @Test
  public void testExistUsesSchemaMetadata() throws SQLException {
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet resultSet = mock(ResultSet.class);
    TestableSqlServerSchemaOperations operations = operationsReturning(metaData, resultSet);

    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("dbo");

    Assertions.assertTrue(operations.exist("dbo"));
    verify(metaData).getSchemas(null, "dbo");
  }

  private static TestableSqlServerSchemaOperations operationsReturning(
      DatabaseMetaData metaData, ResultSet resultSet) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    // Stubbed to a real database name so that verify(getSchemas(null, ...)) is meaningful.
    when(connection.getCatalog()).thenReturn(DATABASE);
    when(connection.getMetaData()).thenReturn(metaData);
    when(dataSource.getConnection()).thenReturn(connection);
    when(metaData.getSchemas(null, null)).thenReturn(resultSet);
    when(metaData.getSchemas(null, "dbo")).thenReturn(resultSet);
    return new TestableSqlServerSchemaOperations(dataSource);
  }

  /** Supplies the collaborators {@link SqlServerSchemaOperations} would get from a catalog. */
  private static class TestableSqlServerSchemaOperations extends SqlServerSchemaOperations {

    TestableSqlServerSchemaOperations(DataSource dataSource) {
      super.dataSource = dataSource;
      super.exceptionMapper = new SqlServerExceptionConverter();
    }
  }
}
