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
package org.apache.gravitino.catalog.oracle.operation;

import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for how the Oracle catalog enumerates schemas.
 *
 * <p>An Oracle schema is a user, so {@code listDatabases} has to decide which of the accounts the
 * connecting user can see are schemas a user owns and which belong to Oracle. {@link
 * java.sql.DatabaseMetaData#getSchemas} reports both with nothing telling them apart.
 */
public class TestOracleSchemaOperationsMetadata {

  /**
   * Enumeration filters on two signals. {@code ALL_USERS.ORACLE_MAINTAINED} removes the internal
   * accounts Oracle marks, which no hand kept blocklist stays complete against as releases add new
   * ones; the earlier blocklist leaked BAASSYS, GGSHAREDCAP, GSMCATUSER, GSMUSER and VECSYS as
   * Gravitino schemas. Oracle leaves a few of its own accounts unmarked, so the reserved names are
   * removed from the rows as well. {@code PDBADMIN} reports {@code ORACLE_MAINTAINED = 'N'} on
   * Oracle Database Free release 23.26 and no other {@code ALL_USERS} column separates it from a
   * schema a user owns.
   */
  @Test
  public void testListDatabasesReadsOracleMaintainedFlag() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(startsWith("SELECT USERNAME FROM ALL_USERS")))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, true, true, false);
    when(resultSet.getString(1)).thenReturn("APP", "GRAVITINO", "PDBADMIN");

    List<String> schemas = new TestableOracleSchemaOperations(dataSource).listDatabases();

    Assertions.assertEquals(Arrays.asList("APP", "GRAVITINO"), schemas);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    Assertions.assertTrue(
        sql.getValue().startsWith("SELECT USERNAME FROM ALL_USERS"), sql.getValue());
    Assertions.assertTrue(sql.getValue().contains("ORACLE_MAINTAINED = 'N'"), sql.getValue());
  }

  /**
   * Supplies the collaborators {@link OracleSchemaOperations} would otherwise get from a catalog.
   */
  private static class TestableOracleSchemaOperations extends OracleSchemaOperations {

    TestableOracleSchemaOperations(DataSource dataSource) {
      super.dataSource = dataSource;
      super.exceptionMapper = new OracleExceptionConverter();
    }
  }
}
