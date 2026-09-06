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
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.rnorth.ducttape.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

/**
 * Runs Oracle Database Free for the JDBC catalog integration tests.
 *
 * <p>Oracle maps a schema onto a user, so there is no database to create here: the tests create and
 * drop users through the catalog and the account below only has to be able to do that.
 *
 * <p>The image opens its listener within seconds but then spends several minutes copying and
 * opening the pluggable database, so readiness is polled by connecting to the service rather than
 * to the port.
 */
public class OracleContainer extends BaseContainer {
  public static final Logger LOG = LoggerFactory.getLogger(OracleContainer.class);
  public static final String DEFAULT_IMAGE = "container-registry.oracle.com/database/free:latest";
  public static final String HOST_NAME = "gravitino-ci-oracle";
  public static final int ORACLE_PORT = 1521;
  public static final String SERVICE_NAME = "FREEPDB1";
  public static final String USER_NAME = "system";
  // Oracle rejects a password shorter than eight characters for an administrative account.
  public static final String PASSWORD = "oracle123";
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(15);

  public static Builder builder() {
    return new Builder();
  }

  protected OracleContainer(
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
    withStartupTimeout(STARTUP_TIMEOUT);
    withLogConsumer(new PrintingContainerLog(format("%-14s| ", "OracleContainer")));
  }

  @Override
  public void start() {
    super.start();
    Preconditions.check("Oracle container startup failed!", checkContainerStatus(10));
  }

  @Override
  protected boolean checkContainerStatus(int retryLimit) {
    String jdbcUrl = getJdbcUrl();
    await()
        .atMost(STARTUP_TIMEOUT)
        .pollInterval(30 / retryLimit, TimeUnit.SECONDS)
        .until(
            () -> {
              try (Connection connection =
                      DriverManager.getConnection(jdbcUrl, USER_NAME, PASSWORD);
                  Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1 FROM DUAL");
                LOG.info("Oracle container startup success!");
                return true;
              } catch (SQLException e) {
                LOG.info("Oracle container is not ready yet: {}", e.getMessage());
                return false;
              }
            });
    return true;
  }

  public String getUsername() {
    return USER_NAME;
  }

  public String getPassword() {
    return PASSWORD;
  }

  /** getJdbcUrl of the pluggable database the tests create their schemas in. */
  public String getJdbcUrl() {
    return format(
        "jdbc:oracle:thin:@//%s:%d/%s", getContainerIpAddress(), ORACLE_PORT, SERVICE_NAME);
  }

  public String getDriverClassName() throws SQLException {
    return DriverManager.getDriver(getJdbcUrl()).getClass().getName();
  }

  public static class Builder
      extends BaseContainer.Builder<OracleContainer.Builder, OracleContainer> {

    private Builder() {
      this.image = DEFAULT_IMAGE;
      this.hostName = HOST_NAME;
      this.exposePorts = ImmutableSet.of(ORACLE_PORT);
    }

    @Override
    public OracleContainer build() {
      return new OracleContainer(
          image, hostName, exposePorts, extraHosts, filesToMount, envVars, network);
    }
  }
}
