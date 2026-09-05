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
package org.apache.gravitino.catalog.jdbc.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import java.time.LocalDateTime;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestJdbcColumnDefaultValueConverter {

  private final JdbcColumnDefaultValueConverter converter = new JdbcColumnDefaultValueConverter();

  @Test
  public void testFromGravitino() {
    Expression expression = DEFAULT_VALUE_NOT_SET;
    String result = converter.fromGravitino(expression);
    Assertions.assertNull(result);

    Expression empty = null;
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> converter.fromGravitino(empty));
    Assertions.assertEquals("Not a supported column default value: null", exception.getMessage());

    expression = Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;
    result = converter.fromGravitino(expression);
    Assertions.assertEquals("CURRENT_TIMESTAMP", result);

    expression = Literals.stringLiteral("test");
    result = converter.fromGravitino(expression);
    Assertions.assertEquals("'test'", result);

    expression = Literals.NULL;
    result = converter.fromGravitino(expression);
    Assertions.assertEquals("NULL", result);

    expression = Literals.integerLiteral(1234);
    result = converter.fromGravitino(expression);
    Assertions.assertEquals("1234", result);
  }

  /**
   * A default value reaches Gravitino through the REST API, so its text is untrusted. A quote has
   * to be doubled, or it closes the literal early and leaves the remainder to be parsed as SQL.
   */
  @Test
  public void testFromGravitinoEscapesQuoteInStringLiteral() {
    Assertions.assertEquals(
        "'owner''s value; DROP TABLE marker; --'",
        converter.fromGravitino(Literals.stringLiteral("owner's value; DROP TABLE marker; --")));
  }

  /**
   * None of the dialects this base converter serves treats the backslash as an escape character
   * inside a string literal, so doubling it here would corrupt every Windows path stored as a
   * default.
   */
  @Test
  public void testFromGravitinoKeepsBackslashVerbatim() {
    Assertions.assertEquals(
        "'C:\\data\\notes'", converter.fromGravitino(Literals.stringLiteral("C:\\data\\notes")));
  }

  /** The blank numeric path shares the helper, so it escapes through the same code. */
  @Test
  public void testFromGravitinoQuotesBlankNumericLiteral() {
    Assertions.assertEquals(
        "''", converter.fromGravitino(Literals.of("", Types.IntegerType.get())));
  }

  /** A timestamp literal is rendered with a space where {@code LocalDateTime} puts a T. */
  @Test
  public void testFromGravitinoRendersTimestampLiteral() {
    Assertions.assertEquals(
        "'2020-01-01 12:30:15'",
        converter.fromGravitino(
            Literals.timestampLiteral(LocalDateTime.of(2020, 1, 1, 12, 30, 15))));
    // The rendering comes from LocalDateTime#toString, which drops a zero seconds field.
    Assertions.assertEquals(
        "'2020-01-01 12:30'",
        converter.fromGravitino(Literals.timestampLiteral(LocalDateTime.of(2020, 1, 1, 12, 30))));
  }
}
