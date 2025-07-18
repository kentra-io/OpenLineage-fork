/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent;

import static io.openlineage.spark.agent.MockServerUtils.verifyEvents;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.from_json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.JsonBody.json;

import com.google.common.collect.ImmutableList;
import io.openlineage.client.OpenLineage.InputDataset;
import io.openlineage.client.OpenLineage.OutputDataset;
import io.openlineage.client.OpenLineage.RunEvent;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.LongType$;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StringType$;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RegexBody;

@Tag("integration-test")
@Tag("delta")
@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SparkDeltaIntegrationTest {
  @SuppressWarnings("PMD")
  private static final String LOCAL_IP = "127.0.0.1";

  private static final int MOCK_SERVER_PORT = 1082;
  private static SparkSession spark;
  private static ClientAndServer mockServer;

  @BeforeAll
  @SneakyThrows
  public static void beforeAll() {
    Spark4CompatUtils.cleanupAnyExistingSession();
    mockServer = MockServerUtils.createAndConfigureMockServer(MOCK_SERVER_PORT);
    FileUtils.deleteDirectory(new File("/tmp/delta/"));
  }

  @AfterAll
  @SneakyThrows
  public static void afterAll() {
    Spark4CompatUtils.cleanupAnyExistingSession();
    MockServerUtils.stopMockServer(mockServer);
  }

  @BeforeEach
  @SneakyThrows
  public void beforeEach() {
    MockServerUtils.clearRequests(mockServer);
    System.setProperty("derby.system.home", "/tmp/delta/derby");

    spark =
        SparkSession.builder()
            .master("local[*]")
            .appName("DeltaIntegrationTest")
            .config("spark.driver.host", LOCAL_IP)
            .config("spark.driver.bindAddress", LOCAL_IP)
            .config("spark.ui.enabled", false)
            .config("spark.sql.shuffle.partitions", 1)
            .config("spark.sql.warehouse.dir", "file:/tmp/delta/")
            .config("spark.openlineage.transport.type", "http")
            .config(
                "spark.openlineage.transport.url",
                "http://localhost:" + mockServer.getPort() + "/api/v1/namespaces/delta-namespace")
            .config(
                "spark.openlineage.facets.custom_environment_variables",
                "[" + getAvailableEnvVariable() + ";]")
            .config("spark.extraListeners", OpenLineageSparkListener.class.getName())
            .config("spark.jars.ivy", "/tmp/.ivy2/")
            .config(
                "spark.sql.catalog.spark_catalog",
                "org.apache.spark.sql.delta.catalog.DeltaCatalog")
            .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
            .getOrCreate();

    FileSystem.get(spark.sparkContext().hadoopConfiguration())
        .delete(new Path("/tmp/delta/"), true);
  }

  @Test
  void testCTASDelta() throws InterruptedException {
    clearTables("temp", "tbl");

    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(RowFactory.create(1L, 2L), RowFactory.create(3L, 4L)),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty()),
                      new StructField("b", LongType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    dataset.createOrReplaceTempView("temp");
    spark.sql("CREATE TABLE tbl USING delta LOCATION '/tmp/delta/tbl' AS SELECT * FROM temp");

    verifyEvents(mockServer, "pysparkDeltaCTASStart.json");
    verifyEvents(mockServer, "pysparkDeltaCTASComplete.json");
  }

  @Test
  void testFilteringDeltaEvents() throws IOException {
    FileUtils.deleteDirectory(new File("/tmp/delta/delta_filter_temp"));
    FileUtils.deleteDirectory(new File("/tmp/delta/delta_filter_t1"));
    FileUtils.deleteDirectory(new File("/tmp/delta/delta_filter_t2"));
    FileUtils.deleteDirectory(new File("/tmp/delta/delta_filter_tbl"));

    // 2 OL events expected
    spark.sql(
        "CREATE TABLE delta_filter_t1 (a long, b long) USING delta LOCATION '/tmp/delta/delta_filter_t1'");
    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(RowFactory.create(1L), RowFactory.create(2L)),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    // 2 OL events expected
    dataset.write().saveAsTable("delta_filter_temp");

    // 2 OL events expected
    spark.sql(
        "CREATE TABLE delta_filter_t2 USING delta LOCATION '/tmp/delta/delta_filter_t2' AS "
            + "SELECT t1.* FROM delta_filter_temp t1 "
            + "JOIN delta_filter_temp t2 ON t1.a = t2.a "
            + "WHERE t1.a > 1");

    // 2 OL events expected
    spark.sql("INSERT INTO delta_filter_t1 VALUES (3,4)");
    verifyEvents(mockServer, "pysparkDeltaFilterStart.json");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertTrue(
                    mockServer.retrieveRecordedRequests(
                                request()
                                    .withPath("/api/v1/lineage")
                                    .withBody(new RegexBody(".*delta_filter.*")))
                            .length
                        <= 8));
  }

  @Test
  void testDeltaSaveAsTable() {
    clearTables("movies");
    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(
                    RowFactory.create(
                        "{\"title\":\"Feeding Sea Lions\",\"year\":1900,\"cast\":[\"Paul Boyton\"],\"genres\":[]}"),
                    RowFactory.create(
                        "{\"title\":\"The Wonder, Ching Ling Foo\",\"year\":1900,\"cast\":[\"Ching Ling Foo\"],\"genres\":[\"Short\"]}")),
                new StructType(
                    new StructField[] {
                      new StructField("value", StringType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    StructType schema =
        new StructType(
            new StructField[] {
              new StructField("title", StringType$.MODULE$, false, Metadata.empty()),
              new StructField("year", IntegerType$.MODULE$, false, Metadata.empty()),
              new StructField(
                  "cast", new ArrayType(StringType$.MODULE$, false), false, Metadata.empty()),
              new StructField(
                  "genres", new ArrayType(StringType$.MODULE$, false), false, Metadata.empty())
            });

    dataset
        .select(from_json(col("value"), schema).alias("parsed"))
        .select(col("parsed.*"))
        .write()
        .mode("overwrite")
        .format("parquet")
        .saveAsTable("movies");

    verifyEvents(mockServer, "pysparkDeltaSaveAsTableComplete.json");

    assertThat(
            MockServerUtils.getEventsEmitted(mockServer).stream()
                .map(e -> e.getJob().getName())
                .filter(
                    j ->
                        j.startsWith(
                            "delta_integration_test.execute_create_data_source_table_as_select_command"))
                .filter(j -> j.endsWith("movies"))) // job name ends with dataset name
        .hasSize(2);
  }

  @Test
  void testReplaceTable() {
    clearTables("tbl");
    spark.sql(
        "CREATE TABLE tbl (a string, b string) USING delta LOCATION '/tmp/delta/v2_replace_table'");
    spark.sql(
        "REPLACE TABLE tbl (c string, d string) USING delta LOCATION '/tmp/delta/v2_replace_table'");

    verifyEvents(
        mockServer,
        "pysparkV2ReplaceTableStartEvent.json",
        "pysparkV2ReplaceTableCompleteEvent.json");
  }

  @Test
  void testDeltaVersion() {
    clearTables("versioned_table", "versioned_input_table");

    // VERSION 1 of versioned_table
    spark.sql(
        "CREATE TABLE versioned_table (a long, b long) USING delta "
            + "LOCATION '/tmp/delta/versioned_table'");

    // VERSION 2 of versioned_table
    spark.sql("ALTER TABLE versioned_table ADD COLUMNS (c long)");

    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(RowFactory.create(1L), RowFactory.create(2L)),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);
    dataset.createOrReplaceTempView("temp");

    // VERSION 1 of versioned_input_table
    spark.sql(
        "CREATE TABLE versioned_input_table USING delta LOCATION "
            + "'/tmp/delta/versioned_input_table' AS SELECT * FROM temp");

    // VERSION 2 of versioned_input_table
    spark.sql("ALTER TABLE versioned_input_table ADD COLUMNS (b long)");
    spark.sql("INSERT INTO versioned_input_table VALUES (3,4)");

    // VERSION 3 of versioned_input_table
    spark.sql("ALTER TABLE versioned_input_table ADD COLUMNS (c long)");
    spark.sql("INSERT INTO versioned_table SELECT * FROM versioned_input_table");

    verifyEvents(
        mockServer,
        "pysparkWriteDeltaTableVersionStart.json",
        "pysparkWriteDeltaTableVersionEnd.json");
  }

  @Test
  void testSaveIntoDataSourceCommand() throws InterruptedException {
    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(
                    RowFactory.create(1L, "bat"),
                    RowFactory.create(3L, "mouse"),
                    RowFactory.create(3L, "horse")),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty()),
                      new StructField("b", StringType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    dataset
        .write()
        .mode("overwrite")
        .format("delta")
        .save("/tmp/delta/save_into_data_source_target/");

    verifyEvents(mockServer, "pysparkSaveIntoDatasourceCompleteEvent.json");
  }

  @Test
  void testDeltaMergeInto() {
    clearTables("t1", "t2");

    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(
                    RowFactory.create(1L, "bat"),
                    RowFactory.create(2L, "mouse"),
                    RowFactory.create(3L, "horse")),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty()),
                      new StructField("b", StringType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);
    dataset.createOrReplaceTempView("temp");

    spark.sql("CREATE TABLE t1 USING delta LOCATION '/tmp/delta/t1' AS SELECT * FROM temp");
    spark.sql("CREATE TABLE t2 USING delta LOCATION '/tmp/delta/t2' AS SELECT * FROM temp");
    spark.sql(
        "MERGE INTO t1 USING t2 ON t1.a = t2.a"
            + " WHEN MATCHED THEN UPDATE SET t1.b = t2.b"
            + " WHEN NOT MATCHED THEN INSERT *");

    verifyEvents(
        mockServer,
        "pysparkDeltaMergeIntoStartEvent.json",
        "pysparkDeltaMergeIntoCompleteEvent.json");
  }

  @Test
  @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
  void testCustomEnvVar() {
    spark.sql("DROP TABLE IF EXISTS test");
    spark.sql("CREATE TABLE test (key INT, value STRING) using delta");

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              HttpRequest[] requests =
                  mockServer.retrieveRecordedRequests(request().withPath("/api/v1/lineage"));
              assertThat(requests).isNotEmpty();

              String body = requests[requests.length - 1].getBodyAsString();

              assertThat(body).contains("COMPLETE");
              assertThat(body).contains(getAvailableEnvVariable());
            });
  }

  @Test
  @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
  void testNoDuplicateEventsForDelta() {
    clearTables("t1", "t2", "t3", "t4");

    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(RowFactory.create(1L, "bat"), RowFactory.create(3L, "horse")),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty()),
                      new StructField("b", StringType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    dataset.write().format("delta").saveAsTable("t1");
    dataset.write().format("delta").saveAsTable("t2");
    dataset.write().format("delta").saveAsTable("t3");

    // wait until t3 complete event is sent
    await()
        .pollInterval(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                mockServer.verify(
                    request()
                        .withPath("/api/v1/lineage")
                        .withBody(
                            json(
                                "{\"outputs\":[{\"name\": \"/tmp/delta/t3\"}]}",
                                MatchType.ONLY_MATCHING_FIELDS))));

    mockServer.reset();
    mockServer
        .when(request("/api/v1/lineage"))
        .respond(org.mockserver.model.HttpResponse.response().withStatusCode(201));

    // this operation should contain only START AND STOP JOB
    spark.sql(
        "CREATE TABLE t4 USING DELTA AS "
            + "SELECT t1.a as a1, t2.a as a2, t3.b as b1 FROM t1 "
            + "JOIN t2 on t1.a = t2.a JOIN t3 on t2.b=t3.b");

    await()
        .pollInterval(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              HttpRequest[] requests =
                  mockServer.retrieveRecordedRequests(request().withPath("/api/v1/lineage"));

              String lastRequestBody = requests[requests.length - 1].getBody().toString();

              return lastRequestBody.contains("/tmp/delta/t4")
                  && lastRequestBody.contains("create_table_as_select")
                  && lastRequestBody.contains("COMPLETE")
                  && requests.length == 2;
            });
  }

  @Test
  @SneakyThrows
  void testMergeInto() {
    clearTables("events", "updates");
    spark.sql("CREATE TABLE events (event_id long, last_updated_at long) USING delta");
    spark.sql("CREATE TABLE updates (event_id long, updated_at long) USING delta");

    spark.sql("INSERT INTO events VALUES (1, 1641290276);");
    spark.sql("INSERT INTO updates VALUES (1, 1641290277);");
    spark.sql("INSERT INTO updates VALUES (2, 1641290277);");

    spark.read().table("events").write().format("delta").save("/tmp/delta/new-events");

    spark.sql(
        "MERGE INTO delta.`/tmp/delta/new-events` target USING updates "
            + " ON target.event_id = updates.event_id"
            + " WHEN MATCHED THEN UPDATE SET target.last_updated_at = updates.updated_at"
            + " WHEN NOT MATCHED THEN INSERT (event_id, last_updated_at) "
            + "VALUES (event_id, updated_at)");

    verifyEvents(
        mockServer,
        "pysparkV2MergeIntoDeltaTableStartEvent.json",
        "pysparkV2MergeIntoDeltaTableCompleteEvent.json");

    assertThat(
            MockServerUtils.getEventsEmitted(mockServer).stream()
                .map(e -> e.getInputs().size())
                .collect(Collectors.toList()))
        .describedAs("Number of inputs in each event")
        .containsOnly(0, 1, 2);
  }

  @Test
  @SneakyThrows
  void testGroupByQuery() {
    clearTables("t1", "t2");

    Dataset<Row> dataset =
        spark
            .createDataFrame(
                ImmutableList.of(RowFactory.create(1L, "bat"), RowFactory.create(3L, "horse")),
                new StructType(
                    new StructField[] {
                      new StructField("a", LongType$.MODULE$, false, Metadata.empty()),
                      new StructField("b", StringType$.MODULE$, false, Metadata.empty())
                    }))
            .repartition(1);

    dataset.write().format("delta").saveAsTable("t1");
    spark.sql("CREATE TABLE t2 USING delta AS SELECT sum(a) AS c, b AS d FROM t1 group by b");

    verifyEvents(mockServer, "pysparkDeltaGroupByCompleteEvent.json");
  }

  @Test
  @SneakyThrows
  void testMergeCommandColumnLineage() {
    spark.sql("DROP TABLE if exists events");
    spark.sql("CREATE TABLE events (event_id long, last_updated_at long) USING delta");
    spark.sql("DROP TABLE if exists updates");
    spark.sql("CREATE TABLE updates (event_id long, updated_at long) USING delta");

    spark.sql("INSERT INTO events VALUES (1, 1641290276);");
    spark.sql("INSERT INTO updates VALUES (1, 1641290277);");
    spark.sql("INSERT INTO updates VALUES (2, 1641290277);");

    spark
        .sql("select event_id, coalesce(updated_at,0) as updated_at from updates")
        .createOrReplaceTempView("updates2");

    spark.sql(
        "MERGE INTO events target USING updates2 updates "
            + " ON target.event_id = updates.event_id"
            + " WHEN MATCHED THEN UPDATE SET target.last_updated_at = updates.updated_at"
            + " WHEN NOT MATCHED THEN INSERT (event_id, last_updated_at) "
            + "VALUES (event_id, updated_at)");

    verifyEvents(
        mockServer,
        "deltaMergeCommandVerificationStart.json",
        "deltaMergeCommandVerificationComplete.json");
  }

  @Test
  @SneakyThrows
  void testWithColumnOnMerge() {
    StructType schema =
        new StructType(
            new StructField[] {
              new StructField("id", IntegerType$.MODULE$, false, Metadata.empty()),
              new StructField("value", StringType$.MODULE$, false, Metadata.empty()),
            });

    Dataset<Row> source =
        spark
            .createDataFrame(
                ImmutableList.of(
                    RowFactory.create(1, "A"),
                    RowFactory.create(2, "B"),
                    RowFactory.create(3, "C")),
                schema)
            .withColumn("__load_ts", functions.lit("2022-01-01 00:02:00").cast("timestamp"))
            .repartition(1);

    Dataset<Row> target =
        spark
            .createDataFrame(
                ImmutableList.of(
                    RowFactory.create(1, "A_old"),
                    RowFactory.create(2, "B_old"),
                    RowFactory.create(3, "C_old")),
                schema)
            .withColumn("__load_ts", functions.lit("2022-01-01 00:02:00").cast("timestamp"))
            .repartition(1);

    source.write().format("delta").saveAsTable("source_table");
    target.write().format("delta").saveAsTable("target_table");

    spark.sql(
        "WITH source as (SELECT *, __load_ts as load_ts FROM source_table) "
            + " MERGE INTO target_table target USING source "
            + " ON target.id = source.id"
            + " WHEN MATCHED THEN UPDATE SET *"
            + " WHEN NOT MATCHED THEN INSERT *");

    await()
        .pollInterval(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              // wait for the merge command to complete
              List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);
              RunEvent lastEvent = events.get(events.size() - 1);
              return "COMPLETE".equals(lastEvent.getEventType().toString())
                  && lastEvent.getJob().getName().contains("merge_into_command");
            });

    List<RunEvent> events = MockServerUtils.getEventsEmitted(mockServer);
    Optional<RunEvent> mergeEvent =
        events.stream()
            .filter(f -> "COMPLETE".equals(f.getEventType().name()))
            .filter(f -> f.getJob().getName().contains("merge_into_command"))
            .findFirst();

    assertThat(mergeEvent).isPresent();
    assertThat(mergeEvent)
        .get()
        .extracting(RunEvent::getOutputs)
        .asInstanceOf(InstanceOfAssertFactories.list(OutputDataset.class))
        .extracting(OutputDataset::getName)
        .containsExactlyInAnyOrder("/tmp/delta/target_table");
    assertThat(mergeEvent)
        .get()
        .extracting(RunEvent::getInputs)
        .asInstanceOf(InstanceOfAssertFactories.list(InputDataset.class))
        .extracting(InputDataset::getName)
        .containsExactlyInAnyOrder("/tmp/delta/target_table", "/tmp/delta/source_table");
  }

  /**
   * Environment variables differ on local environment and CI. This method returns any environment
   * variable being set for testing.
   *
   * @return
   */
  String getAvailableEnvVariable() {
    return (String) System.getenv().keySet().toArray()[0];
  }

  private void clearTables(String... tables) {
    try {
      Arrays.stream(tables).forEach(t -> spark.sql("DROP TABLE IF EXISTS " + t));
    } catch (Exception e) {
      // Ignore exceptions during table drop, as they may not exist
    }
  }
}
