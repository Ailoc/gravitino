/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.jdbc.utils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestJdbcConnectorUtils {

  /**
   * A batch of statements separated by newlines is what the catalogs hand over for an {@code ALTER
   * TABLE} carrying more than one change, so every result it produces has to be consumed before
   * returning and the count of the first statement is the one the callers expect.
   */
  @Test
  public void testExecuteUpdateConsumesEveryResultOfABatch() throws SQLException {
    Connection connection = Mockito.mock(Connection.class);
    Statement statement = Mockito.mock(Statement.class);
    Mockito.when(connection.createStatement()).thenReturn(statement);
    Mockito.when(statement.execute("batch")).thenReturn(false);
    Mockito.when(statement.getMoreResults()).thenReturn(true, true, false);
    Mockito.when(statement.getUpdateCount()).thenReturn(2, 1, -1);

    Assertions.assertEquals(2, JdbcConnectorUtils.executeUpdate(connection, "batch"));

    // The walk reaches the end of the batch rather than stopping after the first result.
    Mockito.verify(statement, Mockito.times(3)).getMoreResults();
    Mockito.verify(statement).close();
  }

  /**
   * {@link Statement#executeUpdate} inspects only the first result of a batch, so a driver reports
   * success for a batch whose second statement failed and whose remaining statements the server
   * then skipped. Walking the results is what turns that into the error it is.
   */
  @Test
  public void testExecuteUpdatePropagatesAnErrorAfterTheFirstStatement() throws SQLException {
    Connection connection = Mockito.mock(Connection.class);
    Statement statement = Mockito.mock(Statement.class);
    Mockito.when(connection.createStatement()).thenReturn(statement);
    Mockito.when(statement.execute("batch")).thenReturn(false);
    Mockito.when(statement.getUpdateCount()).thenReturn(0);
    Mockito.when(statement.getMoreResults())
        .thenThrow(
            new SQLException("The object 'DF__x' is dependent on column 'a'", "S0001", 5074));

    SQLException thrown =
        Assertions.assertThrows(
            SQLException.class, () -> JdbcConnectorUtils.executeUpdate(connection, "batch"));

    Assertions.assertEquals(5074, thrown.getErrorCode());
    Mockito.verify(statement).close();
  }

  /** A batch opening with a query reports no update count, which JDBC spells as {@code -1}. */
  @Test
  public void testExecuteUpdateReturnsMinusOneWhenTheBatchOpensWithAQuery() throws SQLException {
    Connection connection = Mockito.mock(Connection.class);
    Statement statement = Mockito.mock(Statement.class);
    Mockito.when(connection.createStatement()).thenReturn(statement);
    Mockito.when(statement.execute("batch")).thenReturn(true);
    Mockito.when(statement.getMoreResults()).thenReturn(false);
    Mockito.when(statement.getUpdateCount()).thenReturn(-1);

    Assertions.assertEquals(-1, JdbcConnectorUtils.executeUpdate(connection, "batch"));
  }

  @Test
  public void testEscapeSqlLiteral() {
    String value = "owner\\'s \"comment\"; DROP TABLE marker; --";

    Assertions.assertEquals(
        "owner\\\\''s \"comment\"; DROP TABLE marker; --",
        JdbcConnectorUtils.escapeSqlLiteral(value, '\''));
    Assertions.assertEquals(
        "owner\\\\'s \"\"comment\"\"; DROP TABLE marker; --",
        JdbcConnectorUtils.escapeSqlLiteral(value, '"'));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> JdbcConnectorUtils.escapeSqlLiteral(value, '`'));
    Assertions.assertEquals("null", JdbcConnectorUtils.escapeSqlLiteral(null, '\''));
  }

  @Test
  public void testUnescapeSqlLiteral() {
    String value = "owner\\'s \"comment\"; DROP TABLE marker; --";

    // Round-trip: unescape(escape(x)) == x for both quote styles.
    Assertions.assertEquals(
        value,
        JdbcConnectorUtils.unescapeSqlLiteral(
            JdbcConnectorUtils.escapeSqlLiteral(value, '\''), '\''));
    Assertions.assertEquals(
        value,
        JdbcConnectorUtils.unescapeSqlLiteral(
            JdbcConnectorUtils.escapeSqlLiteral(value, '"'), '"'));

    // Backslash-style escaping is unescaped too.
    Assertions.assertEquals(
        "owner's \"comment\"",
        JdbcConnectorUtils.unescapeSqlLiteral("owner's \\\"comment\\\"", '"'));

    // Text that was never escaped passes through unchanged, including a lone backslash before an
    // ordinary character and a lone trailing backslash.
    Assertions.assertEquals(
        "D:\\data; --", JdbcConnectorUtils.unescapeSqlLiteral("D:\\data; --", '"'));
    Assertions.assertEquals("tail\\", JdbcConnectorUtils.unescapeSqlLiteral("tail\\", '"'));

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> JdbcConnectorUtils.unescapeSqlLiteral(value, '`'));
  }
}
