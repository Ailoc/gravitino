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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for how the SQL Server catalog reads driver metadata.
 *
 * <p>Every case here covers a way mssql-jdbc departs from what the shared JDBC catalog code
 * assumes, which is invisible against the in-process databases the rest of the test suite uses.
 */
public class TestSqlServerTableOperationsMetadata {

  private static final String SCHEMA = "dbo";
  private static final String TABLE = "test_table";

  private final SqlServerTableOperations operations = new SqlServerTableOperations();

  /**
   * mssql-jdbc answers {@code IS_AUTOINCREMENT} with the text {@code YES}, and {@link
   * ResultSet#getBoolean} on that text yields false, so the shared implementation reports every
   * identity column as not auto-incrementing.
   */
  @Test
  public void testGetAutoIncrementInfoReadsYesAsText() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("IS_AUTOINCREMENT")).thenReturn("YES");

    Assertions.assertTrue(operations.getAutoIncrementInfo(resultSet));
  }

  @Test
  public void testGetAutoIncrementInfoReadsNoAsText() throws SQLException {
    ResultSet identity = mock(ResultSet.class);
    when(identity.getString("IS_AUTOINCREMENT")).thenReturn("NO");
    Assertions.assertFalse(operations.getAutoIncrementInfo(identity));

    // The driver leaves the column null when the server did not report it at all.
    ResultSet absent = mock(ResultSet.class);
    Assertions.assertFalse(operations.getAutoIncrementInfo(absent));
  }

  /**
   * mssql-jdbc never surfaces {@code MS_Description} as {@code REMARKS}, however the connection is
   * configured, so the comments have to be read from {@code sys.extended_properties} instead.
   * Losing them also loses the Gravitino entity id, which a later comment update carries over from
   * the comment it reads back.
   */
  @Test
  public void testCorrectJdbcTableFieldsReadsExtendedProperties() throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    // A table level property has minor_id zero, which the left join reports as a null column name.
    ResultSet descriptions =
        descriptions(
            new String[] {null, "table comment"}, new String[] {"commented", "column comment"});

    when(connection.prepareStatement(startsWith("SELECT c.name, CAST(p.value AS NVARCHAR(MAX))")))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(descriptions);

    JdbcColumn commented = column("commented");
    JdbcColumn uncommented = column("uncommented");
    JdbcTable.Builder builder =
        JdbcTable.builder().withName(TABLE).withColumns(new Column[] {commented, uncommented});

    operations.correctJdbcTableFields(connection, SCHEMA, TABLE, builder);

    Assertions.assertEquals("table comment", builder.comment());
    Column[] columns = builder.columns();
    Assertions.assertEquals("column comment", columns[0].comment());
    Assertions.assertSame(uncommented, columns[1]);
  }

  /** An extended property holding an empty description is no comment at all. */
  @Test
  public void testCorrectJdbcTableFieldsSkipsEmptyDescriptions() throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet descriptions =
        descriptions(new String[] {null, ""}, new String[] {"commented", "column comment"});

    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(descriptions);

    JdbcColumn commented = column("commented");
    JdbcTable.Builder builder =
        JdbcTable.builder().withName(TABLE).withColumns(new Column[] {commented});

    operations.correctJdbcTableFields(connection, SCHEMA, TABLE, builder);

    Assertions.assertNull(builder.comment());
    Assertions.assertEquals("column comment", builder.columns()[0].comment());
  }

  /** The description lookup is parameterised, so a schema or table name cannot inject SQL. */
  @Test
  public void testCorrectJdbcTableFieldsBindsNamesAsParameters() throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet noRows = mock(ResultSet.class);

    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(noRows);

    operations.correctJdbcTableFields(
        connection, SCHEMA, TABLE, JdbcTable.builder().withName(TABLE));

    verify(statement).setString(1, SCHEMA);
    verify(statement).setString(2, TABLE);
  }

  /** A table with no extended property at all still loads, simply without a comment. */
  @Test
  public void testCorrectJdbcTableFieldsWithoutDescriptionsLeavesBuilderAlone()
      throws SQLException {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet noRows = mock(ResultSet.class);

    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(noRows);

    JdbcColumn column = column("col1");
    JdbcTable.Builder builder =
        JdbcTable.builder().withName(TABLE).withColumns(new Column[] {column});

    operations.correctJdbcTableFields(connection, SCHEMA, TABLE, builder);

    Assertions.assertNull(builder.comment());
    Assertions.assertSame(column, builder.columns()[0]);
  }

  private static JdbcColumn column(String name) {
    return JdbcColumn.builder()
        .withName(name)
        .withType(Types.IntegerType.get())
        .withNullable(true)
        .build();
  }

  /**
   * Stubs the description result set over rows of a column name and its description, addressed by
   * column index.
   *
   * <p>The getters answer from the row the cursor sits on rather than from a canned sequence of
   * return values. The code under test reads the description first and skips the column name
   * entirely when it is empty, so a fixed sequence would fall out of step on the very row that
   * makes the skipping worth testing.
   *
   * @param rows the rows, each holding the column name at index zero and the description at index
   *     one
   * @return the stubbed result set
   * @throws SQLException if stubbing the getters fails
   */
  private static ResultSet descriptions(String[]... rows) throws SQLException {
    List<String[]> result = Arrays.asList(rows);
    ResultSet resultSet = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(resultSet.next()).thenAnswer(call -> cursor.incrementAndGet() < result.size());
    when(resultSet.getString(anyInt()))
        .thenAnswer(call -> result.get(cursor.get())[call.getArgument(0, Integer.class) - 1]);
    return resultSet;
  }
}
