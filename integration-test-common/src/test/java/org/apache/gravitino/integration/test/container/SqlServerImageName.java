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

/** The SQL Server releases the JDBC catalog is verified against. */
public enum SqlServerImageName {
  VERSION_2017("mcr.microsoft.com/mssql/server:2017-latest"),
  VERSION_2019("mcr.microsoft.com/mssql/server:2019-latest"),
  VERSION_2022("mcr.microsoft.com/mssql/server:2022-latest"),
  VERSION_2025("mcr.microsoft.com/mssql/server:2025-latest");

  private final String imageName;

  SqlServerImageName(String imageName) {
    this.imageName = imageName;
  }

  @Override
  public String toString() {
    return this.imageName;
  }
}
