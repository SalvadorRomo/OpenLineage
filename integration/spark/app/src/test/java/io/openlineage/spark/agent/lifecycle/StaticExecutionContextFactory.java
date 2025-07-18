/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.InputDataset;
import io.openlineage.client.OpenLineage.OutputDataset;
import io.openlineage.spark.agent.EventEmitter;
import io.openlineage.spark.agent.OpenLineageSparkListener;
import io.openlineage.spark.agent.Spark4CompatUtils;
import io.openlineage.spark.agent.Versions;
import io.openlineage.spark.agent.util.TestOpenLineageEventHandlerFactory;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark.api.OpenLineageRunStatus;
import io.openlineage.spark.api.SparkOpenLineageConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.SneakyThrows;
import org.apache.spark.SparkContext;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.QueryExecution;
import org.apache.spark.sql.execution.SQLExecution;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart;
import org.slf4j.LoggerFactory;
import scala.PartialFunction;

/** Returns deterministic fields for contexts */
public class StaticExecutionContextFactory extends ContextFactory {

  // Create a semaphore with multiple permits so that jobs that launch multiple Spark SQL jobs,
  // (i.e., Delta jobs that need to work with Delta logs as well as the target dataset) and fire
  // multiple SparkListenerSQLExecutionStart events don't block waiting for the first SQL job to
  // finish. The #waitForExecutionEnd method will wait for <i>all</i> acquired permits to be
  // released before continuing.
  public static final int NUM_PERMITS = 5;
  public static final Semaphore semaphore = new Semaphore(NUM_PERMITS);

  public StaticExecutionContextFactory(
      EventEmitter eventEmitter, MeterRegistry meterRegistry, SparkOpenLineageConfig config) {
    super(eventEmitter, meterRegistry, config);
    try {
      semaphore.acquire(NUM_PERMITS);
    } catch (Exception e) {
      throw new RuntimeException("Unable to acquire permits to start context factory", e);
    } finally {
      semaphore.release(NUM_PERMITS);
    }
  }

  /**
   * The {@link OpenLineageSparkListener} is invoked by a {@link org.apache.spark.util.ListenerBus}
   * on a separate thread. In order for the tests to know that the listener events have been
   * processed, they can invoke this method, which will wait up to one second for the processing to
   * complete. If multiple SQL jobs have been executed, this method will wait for <i>all</i> jobs to
   * trigger the SparkListenerSQLExecutionEnd event and release the acquired permit.
   *
   * @throws InterruptedException
   */
  public static void waitForExecutionEnd() throws InterruptedException, TimeoutException {
    boolean acquired = semaphore.tryAcquire(NUM_PERMITS, 10, TimeUnit.SECONDS);
    if (!acquired) {
      throw new TimeoutException(
          "Unable to acquire permit within expected timeout- "
              + "OpenLineageSparkListener processing may not have completed correctly");
    }
    semaphore.release(NUM_PERMITS);
  }

  @Override
  public ExecutionContext createRddExecutionContext(int jobId) {
    OpenLineageContext olContext = mock(OpenLineageContext.class);
    when(olContext.getOpenLineage())
        .thenReturn(new OpenLineage(Versions.OPEN_LINEAGE_PRODUCER_URI));
    SparkOpenLineageConfig olConfig = new SparkOpenLineageConfig();
    olConfig.setOverriddenAppName("test_rdd");
    when(olContext.getOpenLineageConfig()).thenReturn(olConfig);
    when(olContext.getLineageRunStatus()).thenReturn(new OpenLineageRunStatus());
    OpenLineageRunEventBuilder runEventBuilder =
        new OpenLineageRunEventBuilder(olContext, new TestOpenLineageEventHandlerFactory());

    return new RddExecutionContext(olContext, openLineageEventEmitter, runEventBuilder) {
      @Override
      public void start(SparkListenerJobStart jobStart) {
        try {
          boolean acquired = semaphore.tryAcquire(1, TimeUnit.SECONDS);
          if (!acquired) {
            throw new RuntimeException("Timeout acquiring permit");
          }
        } catch (InterruptedException e) {
          throw new RuntimeException("Unable to acquire semaphore", e);
        }
        super.start(jobStart);
      }

      @Override
      public void end(SparkListenerJobEnd jobEnd) {
        super.end(jobEnd);
        semaphore.release();
      }
    };
  }

  @Override
  @SneakyThrows
  public Optional<ExecutionContext> createSparkSQLExecutionContext(long executionId) {
    return Optional.ofNullable(SQLExecution.getQueryExecution(executionId))
        .map(
            qe -> {
              SparkSession session = Spark4CompatUtils.getSparkSession(qe);
              SparkContext sparkContext = qe.sparkPlan().sparkContext();
              SparkOpenLineageConfig config = new SparkOpenLineageConfig();
              OpenLineageContext olContext =
                  OpenLineageContext.builder()
                      .sparkSession(session)
                      .sparkContext(sparkContext)
                      .openLineage(new OpenLineage(Versions.OPEN_LINEAGE_PRODUCER_URI))
                      .customEnvironmentVariables(Arrays.asList("TEST_VAR"))
                      .queryExecution(qe)
                      .meterRegistry(getMeterRegistry())
                      .openLineageConfig(config)
                      .sparkExtensionVisitorWrapper(
                          new SparkOpenLineageExtensionVisitorWrapper(config))
                      .build();
              OpenLineageRunEventBuilder runEventBuilder =
                  new OpenLineageRunEventBuilder(olContext, new InternalEventHandlerFactory());

              VisitorFactory visitorFactory = VisitorFactoryProvider.getInstance();

              List<PartialFunction<LogicalPlan, List<InputDataset>>> inputDatasets =
                  visitorFactory.getInputVisitors(olContext);
              olContext.getInputDatasetQueryPlanVisitors().addAll(inputDatasets);
              List<PartialFunction<LogicalPlan, List<OutputDataset>>> outputDatasets =
                  visitorFactory.getOutputVisitors(olContext);
              olContext.getOutputDatasetQueryPlanVisitors().addAll(outputDatasets);
              return new SparkSQLExecutionContext(
                  executionId, openLineageEventEmitter, olContext, runEventBuilder) {
                @Override
                public void start(SparkListenerSQLExecutionStart startEvent) {
                  try {
                    boolean acquired = semaphore.tryAcquire(1, TimeUnit.SECONDS);
                    if (!acquired) {
                      throw new RuntimeException("Timeout acquiring permit");
                    }
                  } catch (InterruptedException e) {
                    throw new RuntimeException("Unable to acquire semaphore", e);
                  }
                  super.start(startEvent);
                }

                @Override
                public void end(SparkListenerSQLExecutionEnd endEvent) {
                  try {
                    super.end(endEvent);
                  } finally {
                    // ALWAYS release the permits
                    LoggerFactory.getLogger(getClass()).info("Released permit");
                    semaphore.release();
                  }
                }
              };
            });
  }

  public ExecutionContext createSparkSQLExecutionContext(
      Long executionId, EventEmitter emitter, QueryExecution qe, OpenLineageContext olContext) {
    return new SparkSQLExecutionContext(
        executionId,
        emitter,
        olContext,
        new OpenLineageRunEventBuilder(olContext, new InternalEventHandlerFactory()));
  }
}
