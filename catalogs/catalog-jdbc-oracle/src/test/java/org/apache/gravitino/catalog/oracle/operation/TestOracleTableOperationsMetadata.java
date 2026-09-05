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
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for how the Oracle catalog reads table metadata back from the driver.
 *
 * <p>Every case here covers a way the Oracle driver departs from what the shared JDBC catalog code
 * assumes, which is invisible against the in-process databases the rest of the test suite uses.
 */
public class TestOracleTableOperationsMetadata {

  private static final String SCHEMA = "APP";
  private static final String TABLE = "test_table";

  private final OracleTableOperations operations = new OracleTableOperations();

  /**
   * Asking the driver for exact index statistics makes it run {@code DBMS_STATS.GATHER_TABLE_STATS}
   * before answering. On a quoted lower case table name the gather call fails with ORA-20000, and
   * even when it succeeds it costs an order of magnitude more than the read it precedes. Only the
   * unused {@code CARDINALITY} and {@code PAGES} columns depend on statistics, so the approximate
   * read returns the same index names, columns and uniqueness.
   */
  @Test
  public void testGetIndexInfoAsksForApproximateRead() throws SQLException {
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);

    operations.getIndexInfo(SCHEMA, TABLE, metaData);

    // Oracle has no catalog level, so the catalog argument stays null.
    verify(metaData).getIndexInfo(null, SCHEMA, TABLE, false, true);
  }

  /**
   * ojdbc populates {@code REMARKS} only when its {@code remarksReporting} connection property is
   * on, so the comments have to be read from the dictionary instead. Losing them also loses the
   * Gravitino entity id, which a later comment update carries over from the comment it reads back.
   */
  @Test
  public void testCorrectJdbcTableFieldsReadsCommentsFromDictionary() throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement tableStatement = mock(PreparedStatement.class);
    PreparedStatement columnStatement = mock(PreparedStatement.class);
    ResultSet tableComments = mock(ResultSet.class);
    ResultSet columnComments = mock(ResultSet.class);

    when(connection.prepareStatement(startsWith("SELECT COMMENTS FROM ALL_TAB_COMMENTS")))
        .thenReturn(tableStatement);
    when(connection.prepareStatement(
            startsWith("SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS")))
        .thenReturn(columnStatement);
    when(tableStatement.executeQuery()).thenReturn(tableComments);
    when(columnStatement.executeQuery()).thenReturn(columnComments);
    when(tableComments.next()).thenReturn(true, false);
    when(tableComments.getString(1)).thenReturn("table comment");
    when(columnComments.next()).thenReturn(true, true, false);
    when(columnComments.getString(1)).thenReturn("commented", "uncommented");
    // ALL_COL_COMMENTS has a row for every column, with a null comment for the ones never
    // commented.
    when(columnComments.getString(2)).thenReturn("column comment", (String) null);

    JdbcColumn commented = column("commented");
    JdbcColumn uncommented = column("uncommented");
    JdbcColumn absent = column("absent");
    JdbcTable.Builder builder =
        JdbcTable.builder()
            .withName(TABLE)
            .withColumns(new Column[] {commented, uncommented, absent});

    operations.correctJdbcTableFields(connection, SCHEMA, TABLE, builder);

    Assertions.assertEquals("table comment", builder.comment());
    Column[] columns = builder.columns();
    Assertions.assertEquals("column comment", columns[0].comment());
    Assertions.assertSame(uncommented, columns[1]);
    Assertions.assertSame(absent, columns[2]);
  }

  /** The dictionary lookups are parameterised, so a schema or table name cannot inject SQL. */
  @Test
  public void testCorrectJdbcTableFieldsBindsNamesAsParameters() throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement tableStatement = mock(PreparedStatement.class);
    PreparedStatement columnStatement = mock(PreparedStatement.class);
    ResultSet noRows = mock(ResultSet.class);

    when(connection.prepareStatement(startsWith("SELECT COMMENTS FROM ALL_TAB_COMMENTS")))
        .thenReturn(tableStatement);
    when(connection.prepareStatement(
            startsWith("SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS")))
        .thenReturn(columnStatement);
    when(tableStatement.executeQuery()).thenReturn(noRows);
    when(columnStatement.executeQuery()).thenReturn(noRows);

    operations.correctJdbcTableFields(
        connection, SCHEMA, TABLE, JdbcTable.builder().withName(TABLE));

    verify(tableStatement).setString(1, SCHEMA);
    verify(tableStatement).setString(2, TABLE);
    verify(columnStatement).setString(1, SCHEMA);
    verify(columnStatement).setString(2, TABLE);
  }

  /** Builds a nullable integer column carrying no comment, as the driver would report one. */
  private static JdbcColumn column(String name) {
    return JdbcColumn.builder()
        .withName(name)
        .withType(Types.IntegerType.get())
        .withNullable(true)
        .build();
  }
}
