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
package org.apache.gravitino.catalog.jdbc.operation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for how {@link JdbcTableOperations} reads driver metadata.
 *
 * <p>The behaviour pinned down here differs from one driver to the next and no in-process database
 * reproduces it, so the result sets are stubbed rather than read from the SQLite harness the rest
 * of this module's tests use.
 */
public class TestJdbcTableOperationsMetadata {

  private static final String DATABASE = "test_db";
  private static final String TABLE = "test_table";

  /**
   * {@code getIndexInfo} leads with a statistics pseudo-row that describes the table itself rather
   * than an index. Oracle always emits one and it carries no index name, so collecting it used to
   * produce a null-named unique key and make the grouping by index name throw a null pointer
   * exception, which failed every {@code loadTable} and every {@code alterTable} that had to read
   * the table back.
   */
  @Test
  public void testGetIndexesSkipsStatisticsRow() throws SQLException {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet noPrimaryKeys = resultSet(Collections.emptyList());
    ResultSet indexRows =
        resultSet(
            Arrays.asList(
                row(
                    "TYPE",
                    (int) DatabaseMetaData.tableIndexStatistic,
                    "TABLE_NAME",
                    TABLE,
                    "INDEX_NAME",
                    null),
                row(
                    "TYPE",
                    (int) DatabaseMetaData.tableIndexOther,
                    "TABLE_NAME",
                    TABLE,
                    "INDEX_NAME",
                    "uk_a",
                    "NON_UNIQUE",
                    false,
                    "COLUMN_NAME",
                    "a",
                    "ORDINAL_POSITION",
                    1)));

    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getPrimaryKeys(DATABASE, null, TABLE)).thenReturn(noPrimaryKeys);
    when(metaData.getIndexInfo(DATABASE, null, TABLE, false, false)).thenReturn(indexRows);

    List<Index> indexes = new SqliteTableOperations().getIndexes(connection, DATABASE, TABLE);

    Assertions.assertEquals(1, indexes.size());
    Assertions.assertEquals(Index.IndexType.UNIQUE_KEY, indexes.get(0).type());
    Assertions.assertEquals("uk_a", indexes.get(0).name());
    Assertions.assertArrayEquals(new String[][] {{"a"}}, indexes.get(0).fieldNames());
  }

  /** A table carrying no index at all still yields the statistics row, so it reads back empty. */
  @Test
  public void testGetIndexesOnTableWithoutIndexes() throws SQLException {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet noPrimaryKeys = resultSet(Collections.emptyList());
    ResultSet statisticsOnly =
        resultSet(
            Collections.singletonList(
                row(
                    "TYPE",
                    (int) DatabaseMetaData.tableIndexStatistic,
                    "TABLE_NAME",
                    TABLE,
                    "INDEX_NAME",
                    null)));

    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getPrimaryKeys(DATABASE, null, TABLE)).thenReturn(noPrimaryKeys);
    when(metaData.getIndexInfo(DATABASE, null, TABLE, false, false)).thenReturn(statisticsOnly);

    Assertions.assertTrue(
        new SqliteTableOperations().getIndexes(connection, DATABASE, TABLE).isEmpty());
  }

  @Test
  public void testApplyColumnCommentsReplacesOnlyTheCommentedColumns() {
    JdbcColumn commented = column("a", "old comment");
    JdbcColumn plain = column("b", null);
    JdbcColumn unmentioned = column("c", null);
    JdbcTable.Builder builder = builderOf(commented, plain, unmentioned);

    JdbcTableOperations.applyColumnComments(builder, Collections.singletonMap("a", "new comment"));

    Column[] columns = builder.columns();
    Assertions.assertEquals("new comment", columns[0].comment());
    // A column the dictionary says nothing about is left as the driver reported it.
    Assertions.assertSame(plain, columns[1]);
    Assertions.assertSame(unmentioned, columns[2]);
  }

  /**
   * Correcting a comment copies the column, so every other attribute has to be carried over or a
   * comment read would silently drop the type, the nullability, the default or the auto-increment
   * flag.
   */
  @Test
  public void testApplyColumnCommentsPreservesColumnAttributes() {
    JdbcColumn original =
        JdbcColumn.builder()
            .withName("a")
            .withType(Types.IntegerType.get())
            .withComment("old comment")
            .withNullable(false)
            .withDefaultValue(Literals.integerLiteral(7))
            .withAutoIncrement(true)
            .build();
    JdbcTable.Builder builder = builderOf(original);

    JdbcTableOperations.applyColumnComments(builder, Collections.singletonMap("a", "new comment"));

    JdbcColumn corrected = (JdbcColumn) builder.columns()[0];
    Assertions.assertEquals("new comment", corrected.comment());
    Assertions.assertEquals("a", corrected.name());
    Assertions.assertEquals(Types.IntegerType.get(), corrected.dataType());
    Assertions.assertFalse(corrected.nullable());
    Assertions.assertEquals(Literals.integerLiteral(7), corrected.defaultValue());
    Assertions.assertTrue(corrected.autoIncrement());
  }

  @Test
  public void testApplyColumnCommentsWithoutCommentsLeavesBuilderUntouched() {
    JdbcColumn original = column("a", null);
    JdbcTable.Builder builder = builderOf(original);

    JdbcTableOperations.applyColumnComments(builder, Collections.emptyMap());

    Assertions.assertSame(original, builder.columns()[0]);
  }

  private static JdbcTable.Builder builderOf(Column... columns) {
    return JdbcTable.builder().withName(TABLE).withColumns(columns);
  }

  private static JdbcColumn column(String name, String comment) {
    return JdbcColumn.builder()
        .withName(name)
        .withType(Types.IntegerType.get())
        .withComment(comment)
        .withNullable(true)
        .build();
  }

  private static Map<String, Object> row(Object... labelsAndValues) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < labelsAndValues.length; i += 2) {
      result.put((String) labelsAndValues[i], labelsAndValues[i + 1]);
    }
    return result;
  }

  /**
   * Stubs a result set over fixed rows, addressed by column label.
   *
   * <p>The getters answer from the row the cursor sits on rather than from a canned sequence of
   * return values, so a row the code under test skips part way through cannot desynchronise the
   * stubbing.
   */
  private static ResultSet resultSet(List<Map<String, Object>> rows) throws SQLException {
    ResultSet result = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(result.next()).thenAnswer(call -> cursor.incrementAndGet() < rows.size());
    when(result.getString(anyString()))
        .thenAnswer(call -> (String) rows.get(cursor.get()).get(call.getArgument(0)));
    when(result.getShort(anyString()))
        .thenAnswer(call -> (short) (int) rows.get(cursor.get()).get(call.getArgument(0)));
    when(result.getInt(anyString()))
        .thenAnswer(call -> (int) rows.get(cursor.get()).get(call.getArgument(0)));
    when(result.getBoolean(anyString()))
        .thenAnswer(call -> (boolean) rows.get(cursor.get()).get(call.getArgument(0)));
    return result;
  }
}
