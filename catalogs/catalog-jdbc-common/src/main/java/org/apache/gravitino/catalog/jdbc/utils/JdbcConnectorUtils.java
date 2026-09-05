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
package org.apache.gravitino.catalog.jdbc.utils;

import com.google.common.collect.ImmutableList;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.annotation.Nullable;

public final class JdbcConnectorUtils {
  public static final ImmutableList<String> TABLE_TYPES = ImmutableList.of("TABLE");

  private JdbcConnectorUtils() {}

  /**
   * Execute a SQL update statement against the given datasource.
   *
   * <p>The statement may be a batch of several statements separated by newlines, which is how the
   * catalogs emit an {@code ALTER TABLE} carrying more than one change. Every result the batch
   * produces is consumed before returning: {@link Statement#executeUpdate} inspects only the first
   * one, so a driver would report success for a batch whose second statement failed and whose
   * remaining statements the server then skipped.
   *
   * <p>The walk is the loop the JDBC specification documents for {@link Statement#execute}, and it
   * ends when {@link Statement#getUpdateCount} reports {@code -1}. A {@code Statement} standing in
   * for a driver has to report that, or the loop does not end.
   *
   * @param connection The connection to attempt to execute an update against
   * @param sql The sql to execute
   * @return The number of rows updated by the first statement, or -1 if it returned a result set
   * @throws SQLException on error during execution of the update to the underlying SQL data store
   */
  public static int executeUpdate(final Connection connection, final String sql)
      throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(sql);
      int updateCount = hasResultSet ? -1 : statement.getUpdateCount();
      int firstUpdateCount = updateCount;
      while (hasResultSet || updateCount != -1) {
        hasResultSet = statement.getMoreResults();
        updateCount = statement.getUpdateCount();
      }
      return firstUpdateCount;
    }
  }

  /**
   * Escapes a value for inclusion in a quoted SQL string literal.
   *
   * <p>This method escapes both backslashes and the selected quote character. Callers are
   * responsible for adding the surrounding quote characters and any dialect-specific literal
   * prefix.
   *
   * @param value the literal value to escape; {@code null} is represented as the text {@code null}
   * @param quote the quote character used by the SQL dialect
   * @return the escaped literal value
   * @throws IllegalArgumentException if {@code quote} is neither a single nor double quote
   */
  public static String escapeSqlLiteral(@Nullable String value, char quote) {
    if (quote != '\'' && quote != '"') {
      throw new IllegalArgumentException("SQL literal quote must be a single or double quote");
    }

    String literalValue = String.valueOf(value);
    String quoteString = String.valueOf(quote);
    return literalValue.replace("\\", "\\\\").replace(quoteString, quoteString + quoteString);
  }

  /**
   * Reverses {@link #escapeSqlLiteral(String, char)} for text captured from a quoted SQL string
   * literal, e.g. when parsing {@code SHOW CREATE} output.
   *
   * <p>Both doubled-quote ({@code ""} or {@code ''}) and backslash ({@code \"}, {@code \\}) escape
   * styles are unescaped, since dialects differ in which form they emit. Any other character is
   * kept as is, so text that was never escaped passes through unchanged.
   *
   * @param value the captured literal text without the surrounding quote characters
   * @param quote the quote character used by the SQL dialect
   * @return the unescaped literal value
   * @throws IllegalArgumentException if {@code quote} is neither a single nor double quote
   */
  public static String unescapeSqlLiteral(String value, char quote) {
    if (quote != '\'' && quote != '"') {
      throw new IllegalArgumentException("SQL literal quote must be a single or double quote");
    }

    StringBuilder result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == '\\' && i + 1 < value.length()) {
        char next = value.charAt(i + 1);
        if (next == '\\' || next == quote) {
          result.append(next);
          i++;
          continue;
        }
      } else if (current == quote && i + 1 < value.length() && value.charAt(i + 1) == quote) {
        result.append(quote);
        i++;
        continue;
      }
      result.append(current);
    }
    return result.toString();
  }

  public static String[] getTableTypes() {
    return TABLE_TYPES.toArray(new String[0]);
  }
}
