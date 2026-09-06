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
package org.apache.gravitino.integration.test.container;

import static java.lang.String.format;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.rnorth.ducttape.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

/**
 * Runs a SQL Server instance for the JDBC catalog integration tests.
 *
 * <p>Readiness is probed over JDBC rather than through {@code sqlcmd}, whose path moved between
 * releases ({@code /opt/mssql-tools} up to 2017, {@code /opt/mssql-tools18} from 2019 on) and which
 * needs {@code -C} to accept the container's self-signed certificate from 2022 on. A login attempt
 * is the same on every version.
 */
public class SqlServerContainer extends BaseContainer {
  public static final Logger LOG = LoggerFactory.getLogger(SqlServerContainer.class);
  public static final SqlServerImageName DEFAULT_IMAGE = SqlServerImageName.VERSION_2022;
  public static final String HOST_NAME = "gravitino-ci-sqlserver";
  public static final int SQLSERVER_PORT = 1433;
  public static final String USER_NAME = "sa";
  // SQL Server rejects a password that is shorter than eight characters or misses one of the
  // uppercase, lowercase and digit classes.
  public static final String PASSWORD = "Sqlserver123";

  public static Builder builder() {
    return new Builder();
  }

  protected SqlServerContainer(
      String image,
      String hostName,
      Set<Integer> ports,
      Map<String, String> extraHosts,
      Map<String, String> filesToMount,
      Map<String, String> envVars,
      Optional<Network> network) {
    super(image, hostName, ports, extraHosts, filesToMount, envVars, network);
  }

  @Override
  protected void setupContainer() {
    super.setupContainer();
    withLogConsumer(new PrintingContainerLog(format("%-14s| ", "SqlServerContainer")));
  }

  @Override
  public void start() {
    super.start();
    Preconditions.check("SQL Server container startup failed!", checkContainerStatus(6));
  }

  @Override
  protected boolean checkContainerStatus(int retryLimit) {
    // SQL Server starts listening on 1433 well before it accepts a login, so the port-based wait
    // strategy in BaseContainer says nothing about whether the engine is usable yet.
    String jdbcUrl = getJdbcUrl();
    await()
        .atMost(5, TimeUnit.MINUTES)
        .pollInterval(30 / retryLimit, TimeUnit.SECONDS)
        .until(
            () -> {
              try (Connection connection =
                      DriverManager.getConnection(jdbcUrl, USER_NAME, PASSWORD);
                  Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                LOG.info("SQL Server container startup success!");
                return true;
              } catch (SQLException e) {
                LOG.info("SQL Server container is not ready yet: {}", e.getMessage());
                return false;
              }
            });
    return true;
  }

  /** Creates the database the catalog under test binds to, unless it already exists. */
  public void createDatabase(TestDatabaseName testDatabaseName) {
    String databaseName = testDatabaseName.toString();
    try (Connection connection = DriverManager.getConnection(getJdbcUrl(), USER_NAME, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(
          format("IF DB_ID('%s') IS NULL CREATE DATABASE [%s]", databaseName, databaseName));
      LOG.info(format("SQL Server container database %s has been created", databaseName));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public String getUsername() {
    return USER_NAME;
  }

  public String getPassword() {
    return PASSWORD;
  }

  /** getJdbcUrl without a database name, for server-wide statements such as CREATE DATABASE. */
  public String getJdbcUrl() {
    return format("jdbc:sqlserver://%s:%d;encrypt=false", getContainerIpAddress(), SQLSERVER_PORT);
  }

  /**
   * getJdbcUrl bound to one database. {@code encrypt=false} is required: mssql-jdbc encrypts by
   * default and the handshake fails against the container's self-signed certificate.
   */
  public String getJdbcUrl(TestDatabaseName testDatabaseName) {
    return format(
        "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false",
        getContainerIpAddress(), SQLSERVER_PORT, testDatabaseName);
  }

  public String getDriverClassName(TestDatabaseName testDatabaseName) throws SQLException {
    return DriverManager.getDriver(getJdbcUrl(testDatabaseName)).getClass().getName();
  }

  public static class Builder
      extends BaseContainer.Builder<SqlServerContainer.Builder, SqlServerContainer> {

    private Builder() {
      this.image = DEFAULT_IMAGE.toString();
      this.hostName = HOST_NAME;
      this.exposePorts = ImmutableSet.of(SQLSERVER_PORT);
    }

    @Override
    public SqlServerContainer build() {
      return new SqlServerContainer(
          image, hostName, exposePorts, extraHosts, filesToMount, envVars, network);
    }
  }
}
