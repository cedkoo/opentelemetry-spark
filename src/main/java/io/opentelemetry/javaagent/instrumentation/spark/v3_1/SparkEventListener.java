package io.opentelemetry.javaagent.instrumentation.spark.v3_1;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.instrumentation.spark.ApacheSparkSingletons;
import io.opentelemetry.javaagent.instrumentation.spark.SparkEventLogger;
import java.util.concurrent.TimeUnit;
import org.apache.spark.scheduler.*;
import scala.Some;
import scala.collection.JavaConverters;

public class SparkEventListener {

  private static final AttributeKey<Long> SPARK_JOB_ID_ATTR_KEY =
      AttributeKey.longKey("spark.job_id");

  private static final AttributeKey<Long> SPARK_STAGE_ID_ATTR_KEY =
      AttributeKey.longKey("spark.stage_id");

  private static final AttributeKey<Long> SPARK_STAGE_ATTEMPT_NUMBER_ATTR_KEY =
      AttributeKey.longKey("spark.stage_attempt_number");

  public static void onApplicationStart(SparkListenerApplicationStart event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  public static void onApplicationEnd(SparkListenerApplicationEnd event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  public static void onJobStart(SparkListenerJobStart event) {

    int jobId = event.jobId();
    ActiveJob job = ApacheSparkSingletons.findJob(jobId);
    Context parentContext = Context.current();

    Span jobSpan =
        ApacheSparkSingletons.TRACER
            .spanBuilder("spark_job")
            .setAttribute(SPARK_JOB_ID_ATTR_KEY, Long.valueOf(jobId))
            .setParent(parentContext)
            .setStartTimestamp(event.time(), TimeUnit.MILLISECONDS)
            .startSpan();
    Context jobContext = jobSpan.storeInContext(parentContext);

    ApacheSparkSingletons.setJobContext(job, jobContext);

    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  public static void onJobEnd(SparkListenerJobEnd event) {

    int jobId = event.jobId();
    ActiveJob job = ApacheSparkSingletons.findJob(jobId);

    Context jobContext = ApacheSparkSingletons.getJobContext(job);

    Span jobSpan = Span.fromContext(jobContext);

    JobResult jobResult = event.jobResult();

    if (jobResult instanceof JobSucceeded$) {
      jobSpan.setStatus(StatusCode.OK);
    } else if (jobResult instanceof JobFailed) {
      JobFailed errorResult = (JobFailed) jobResult;
      jobSpan.recordException(errorResult.exception());
      jobSpan.setStatus(StatusCode.ERROR);
    }

    jobSpan.end(event.time(), TimeUnit.MILLISECONDS);
    ApacheSparkSingletons.unregisterJob(job);

    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  public static void onStageSubmitted(SparkListenerStageSubmitted event) {

    StageInfo stageInfo = event.stageInfo();
    int stageId = stageInfo.stageId();

    Stage stage = ApacheSparkSingletons.findStage(stageId);

    int jobId = stage.firstJobId();
    ActiveJob firstJob = ApacheSparkSingletons.findJob(jobId);
    Context firstJobContext = ApacheSparkSingletons.getJobContext(firstJob);

    int attemptId = stageInfo.attemptNumber();

    Long submissionTime = (Long) stageInfo.submissionTime().get();

    SpanBuilder builder =
        ApacheSparkSingletons.TRACER
            .spanBuilder("spark_stage")
            .setParent(firstJobContext)
            .setAttribute(SPARK_STAGE_ID_ATTR_KEY, Long.valueOf(stageId))
            .setAttribute(SPARK_STAGE_ATTEMPT_NUMBER_ATTR_KEY, Long.valueOf(attemptId))
            .setStartTimestamp((Long) stageInfo.submissionTime().get(), TimeUnit.MILLISECONDS);

    for (Object id : JavaConverters.asJavaCollection(stage.jobIds())) {
      Integer jid = (Integer) id;
      if (jid != firstJob.jobId()) {
        ActiveJob job = ApacheSparkSingletons.findJob(jid);
        Context jcontext = ApacheSparkSingletons.getJobContext(job);
        Span s = Span.fromContext(jcontext);
        builder.addLink(s.getSpanContext());
      }
    }
    Span stageSpan = builder.startSpan();
    Context stageContext = stageSpan.storeInContext(firstJobContext);
    ApacheSparkSingletons.setStageContext(stage, stageContext);

    SparkEventLogger.emitSparkEvent(event, submissionTime);
  }

  private static void onStageCompleted(SparkListenerStageCompleted event) {
    StageInfo stageInfo = event.stageInfo();
    int stageId = stageInfo.stageId();
    stageInfo.completionTime();
    Stage stage = ApacheSparkSingletons.findStage(stageId);
    Context stageContext = ApacheSparkSingletons.getStageContext(stage);

    Long completionTime = (Long) stageInfo.completionTime().get();

    if (stageContext != null) {
      Span span = Span.fromContext(stageContext);
      if (stageInfo.failureReason() instanceof Some) {
        span.setStatus(StatusCode.ERROR, stageInfo.failureReason().get());
      } else {
        span.setStatus(StatusCode.OK);
      }
      span.end(completionTime, TimeUnit.MILLISECONDS);
    }
    ApacheSparkSingletons.unregisterStage(stage);

    SparkEventLogger.emitSparkEvent(event, completionTime);
  }

  private static void onTaskStart(SparkListenerTaskStart event) {
    SparkEventLogger.emitSparkEvent(event, event.taskInfo().launchTime());
  }

  private static void onTaskEnd(SparkListenerTaskEnd event) {
    SparkEventLogger.emitSparkEvent(event, event.taskInfo().finishTime());
  }

  private static void onOtherEvent(SparkListenerEvent event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onEnvironmentUpdate(SparkListenerEnvironmentUpdate event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onTaskGettingResult(SparkListenerTaskGettingResult event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onSpeculativeTaskSubmitted(SparkListenerSpeculativeTaskSubmitted event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onBlockManagerAdded(SparkListenerBlockManagerAdded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onBlockManagerRemoved(SparkListenerBlockManagerRemoved event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onUnpersistRDD(SparkListenerUnpersistRDD event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onExecutorAdded(SparkListenerExecutorAdded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onExecutorRemoved(SparkListenerExecutorRemoved event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onExecutorExcluded(SparkListenerExecutorExcluded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onExecutorExcludedForStage(SparkListenerExecutorExcludedForStage event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onNodeExcludedForStage(SparkListenerNodeExcludedForStage event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onExecutorUnexcluded(SparkListenerExecutorUnexcluded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onNodeExcluded(SparkListenerNodeExcluded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onNodeUnexcluded(SparkListenerNodeUnexcluded event) {
    SparkEventLogger.emitSparkEvent(event, event.time());
  }

  private static void onBlockUpdated(SparkListenerBlockUpdated event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onExecutorMetricsUpdate(SparkListenerExecutorMetricsUpdate event) {
    // TODO: Better if emit this as metrics?
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onLogStart(SparkListenerLogStart event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  private static void onResourceProfileAdded(SparkListenerResourceProfileAdded event) {
    SparkEventLogger.emitSparkEvent(event);
  }

  public static void handleSparkListenerEvent(SparkListenerEvent event) {
    if (event instanceof SparkListenerApplicationStart) {
      SparkEventListener.onApplicationStart((SparkListenerApplicationStart) event);
    } else if (event instanceof SparkListenerApplicationEnd) {
      SparkEventListener.onApplicationEnd((SparkListenerApplicationEnd) event);
    } else if (event instanceof SparkListenerJobStart) {
      SparkEventListener.onJobStart((SparkListenerJobStart) event);
    } else if (event instanceof SparkListenerJobEnd) {
      SparkEventListener.onJobEnd((SparkListenerJobEnd) event);
    } else if (event instanceof SparkListenerStageSubmitted) {
      SparkEventListener.onStageSubmitted((SparkListenerStageSubmitted) event);
    } else if (event instanceof SparkListenerStageCompleted) {
      SparkEventListener.onStageCompleted((SparkListenerStageCompleted) event);
    } else if (event instanceof SparkListenerTaskStart) {
      SparkEventListener.onTaskStart((SparkListenerTaskStart) event);
    } else if (event instanceof SparkListenerTaskEnd) {
      SparkEventListener.onTaskEnd((SparkListenerTaskEnd) event);
    } else if (event instanceof SparkListenerEnvironmentUpdate) {
      SparkEventListener.onEnvironmentUpdate((SparkListenerEnvironmentUpdate) event);
    } else if (event instanceof SparkListenerTaskGettingResult) {
      SparkEventListener.onTaskGettingResult((SparkListenerTaskGettingResult) event);
    } else if (event instanceof SparkListenerSpeculativeTaskSubmitted) {
      SparkEventListener.onSpeculativeTaskSubmitted((SparkListenerSpeculativeTaskSubmitted) event);
    } else if (event instanceof SparkListenerBlockManagerAdded) {
      SparkEventListener.onBlockManagerAdded((SparkListenerBlockManagerAdded) event);
    } else if (event instanceof SparkListenerBlockManagerRemoved) {
      SparkEventListener.onBlockManagerRemoved((SparkListenerBlockManagerRemoved) event);
    } else if (event instanceof SparkListenerUnpersistRDD) {
      SparkEventListener.onUnpersistRDD((SparkListenerUnpersistRDD) event);
    } else if (event instanceof SparkListenerExecutorAdded) {
      SparkEventListener.onExecutorAdded((SparkListenerExecutorAdded) event);
    } else if (event instanceof SparkListenerExecutorRemoved) {
      SparkEventListener.onExecutorRemoved((SparkListenerExecutorRemoved) event);
    } else if (event instanceof SparkListenerExecutorExcluded) {
      SparkEventListener.onExecutorExcluded((SparkListenerExecutorExcluded) event);
    } else if (event instanceof SparkListenerExecutorExcludedForStage) {
      SparkEventListener.onExecutorExcludedForStage((SparkListenerExecutorExcludedForStage) event);
    } else if (event instanceof SparkListenerNodeExcludedForStage) {
      SparkEventListener.onNodeExcludedForStage((SparkListenerNodeExcludedForStage) event);
    } else if (event instanceof SparkListenerExecutorUnexcluded) {
      SparkEventListener.onExecutorUnexcluded((SparkListenerExecutorUnexcluded) event);
    } else if (event instanceof SparkListenerNodeExcluded) {
      SparkEventListener.onNodeExcluded((SparkListenerNodeExcluded) event);
    } else if (event instanceof SparkListenerNodeUnexcluded) {
      SparkEventListener.onNodeUnexcluded((SparkListenerNodeUnexcluded) event);
    } else if (event instanceof SparkListenerBlockUpdated) {
      SparkEventListener.onBlockUpdated((SparkListenerBlockUpdated) event);
    } else if (event instanceof SparkListenerExecutorMetricsUpdate) {
      SparkEventListener.onExecutorMetricsUpdate((SparkListenerExecutorMetricsUpdate) event);
    } else if (event instanceof SparkListenerLogStart) {
      SparkEventListener.onLogStart((SparkListenerLogStart) event);
    } else if (event instanceof SparkListenerResourceProfileAdded) {
      SparkEventListener.onResourceProfileAdded((SparkListenerResourceProfileAdded) event);
    } else {
      SparkEventListener.onOtherEvent(event);
    }
  }
}
