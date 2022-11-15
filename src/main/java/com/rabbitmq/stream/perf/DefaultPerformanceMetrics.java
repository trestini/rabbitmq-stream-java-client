// Copyright (c) 2020-2022 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Stream Java client library, is dual-licensed under the
// Mozilla Public License 2.0 ("MPL"), and the Apache License version 2 ("ASL").
// For the MPL, please see LICENSE-MPL-RabbitMQ. For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.
package com.rabbitmq.stream.perf;

import com.codahale.metrics.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardConfig;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.CharacterIterator;
import java.text.SimpleDateFormat;
import java.text.StringCharacterIterator;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultPerformanceMetrics implements PerformanceMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPerformanceMetrics.class);

  private final MetricRegistry metricRegistry;
  private final Timer latency, confirmLatency;
  private final boolean summaryFile;
  private final PrintWriter out;
  private final boolean includeByteRates;
  private final Supplier<String> memoryReportSupplier;
  private volatile Closeable closingSequence = () -> {};
  private volatile long lastPublishedCount = 0;
  private volatile long lastConsumedCount = 0;
  private volatile long offset;
  private final String metricsSuffix;

  DefaultPerformanceMetrics(
      CompositeMeterRegistry meterRegistry,
      String metricsPrefix,
      boolean summaryFile,
      boolean includeByteRates,
      boolean confirmLatency,
      Supplier<String> memoryReportSupplier,
      PrintWriter out) {
    this.summaryFile = summaryFile;
    this.includeByteRates = includeByteRates;
    this.memoryReportSupplier = memoryReportSupplier;
    this.out = out;
    DropwizardConfig dropwizardConfig =
        new DropwizardConfig() {
          @Override
          public String prefix() {
            return "";
          }

          @Override
          public String get(String key) {
            return null;
          }
        };
    this.metricRegistry = new MetricRegistry();
    DropwizardMeterRegistry dropwizardMeterRegistry =
        new DropwizardMeterRegistry(
            dropwizardConfig,
            this.metricRegistry,
            HierarchicalNameMapper.DEFAULT,
            io.micrometer.core.instrument.Clock.SYSTEM) {
          @Override
          protected Double nullGaugeValue() {
            return null;
          }
        };
    meterRegistry.add(dropwizardMeterRegistry);
    this.latency =
        Timer.builder(metricsPrefix + ".latency")
            .description("message latency")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .distributionStatisticExpiry(Duration.ofSeconds(1))
            .serviceLevelObjectives()
            .register(meterRegistry);
    if (confirmLatency) {
      this.confirmLatency =
          Timer.builder(metricsPrefix + ".confirm_latency")
              .description("publish confirm latency")
              .publishPercentiles(0.5, 0.75, 0.95, 0.99)
              .distributionStatisticExpiry(Duration.ofSeconds(1))
              .serviceLevelObjectives()
              .register(meterRegistry);
    } else {
      this.confirmLatency = null;
    }
    // the metrics name contains the tags, if any,
    // so we extract the suffix to use it later when looking up the metrics
    String key = metricRegistry.getMeters().keySet().iterator().next();
    int index = key.indexOf(".");
    this.metricsSuffix = index == -1 ? "" : key.substring(index);
  }

  private long getPublishedCount() {
    return this.metricRegistry
        .getMeters()
        .get("rabbitmqStreamPublished" + metricsSuffix)
        .getCount();
  }

  private long getConsumedCount() {
    return this.metricRegistry.getMeters().get("rabbitmqStreamConsumed" + metricsSuffix).getCount();
  }

  @Override
  public void start(String description) throws Exception {
    long startTime = System.nanoTime();

    String metricPublished = "rabbitmqStreamPublished" + metricsSuffix;
    String metricProducerConfirmed = "rabbitmqStreamProducer_confirmed" + metricsSuffix;
    String metricConsumed = "rabbitmqStreamConsumed" + metricsSuffix;
    String metricChunkSize = "rabbitmqStreamChunk_size" + metricsSuffix;
    String metricLatency = "rabbitmqStreamLatency" + metricsSuffix;
    String metricConfirmLatency = "rabbitmqStreamConfirm_latency" + metricsSuffix;
    String metricWrittenBytes = "rabbitmqStreamWritten_bytes" + metricsSuffix;
    String metricReadBytes = "rabbitmqStreamRead_bytes" + metricsSuffix;

    Set<String> allMetrics =
        new HashSet<>(
            Arrays.asList(
                metricPublished,
                metricProducerConfirmed,
                metricConsumed,
                metricChunkSize,
                metricLatency));

    if (confirmLatency()) {
      allMetrics.add(metricConfirmLatency);
    }

    Map<String, String> metersNamesAndLabels = new LinkedHashMap<>();
    metersNamesAndLabels.put(metricPublished, "published");
    metersNamesAndLabels.put(metricProducerConfirmed, "confirmed");
    metersNamesAndLabels.put(metricConsumed, "consumed");

    if (this.includeByteRates) {
      allMetrics.add(metricWrittenBytes);
      allMetrics.add(metricReadBytes);
      metersNamesAndLabels.put(metricWrittenBytes, "written bytes");
      metersNamesAndLabels.put(metricReadBytes, "read bytes");
    }

    ScheduledExecutorService scheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor();

    Closeable summaryFileClosingSequence =
        maybeSetSummaryFile(description, allMetrics, scheduledExecutorService);

    SortedMap<String, Meter> registryMeters = metricRegistry.getMeters();

    Map<String, Meter> meters = new LinkedHashMap<>(metersNamesAndLabels.size());
    metersNamesAndLabels
        .entrySet()
        .forEach(entry -> meters.put(entry.getValue(), registryMeters.get(entry.getKey())));

    Map<String, FormatCallback> formatMeter = new HashMap<>();
    metersNamesAndLabels.entrySet().stream()
        .filter(entry -> !entry.getKey().contains("bytes"))
        .forEach(
            entry -> {
              formatMeter.put(
                  entry.getValue(),
                  (lastValue, currentValue, duration) -> {
                    long rate = 1000 * (currentValue - lastValue) / duration.toMillis();
                    return String.format("%s %d msg/s, ", entry.getValue(), rate);
                  });
            });

    metersNamesAndLabels.entrySet().stream()
        .filter(entry -> entry.getKey().contains("bytes"))
        .forEach(
            entry -> {
              formatMeter.put(
                  entry.getValue(),
                  (lastValue, currentValue, duration) -> {
                    long rate = 1000 * (currentValue - lastValue) / duration.toMillis();
                    return formatByteRate(entry.getValue(), rate) + ", ";
                  });
            });

    Histogram chunkSize = metricRegistry.getHistograms().get(metricChunkSize);
    Function<Histogram, String> formatChunkSize =
        histogram -> String.format("chunk size %.0f", histogram.getSnapshot().getMean());

    com.codahale.metrics.Timer latency = metricRegistry.getTimers().get(metricLatency);
    com.codahale.metrics.Timer confirmLatency =
        confirmLatency() ? metricRegistry.getTimers().get(metricConfirmLatency) : null;

    Function<Number, Number> convertDuration =
        in -> in instanceof Long ? in.longValue() / 1_000_000 : in.doubleValue() / 1_000_000;
    BiFunction<String, com.codahale.metrics.Timer, String> formatLatency =
        (name, timer) -> {
          Snapshot snapshot = timer.getSnapshot();
          return String.format(
              name + " min/median/75th/95th/99th %.0f/%.0f/%.0f/%.0f/%.0f ms",
              convertDuration.apply(snapshot.getMin()),
              convertDuration.apply(snapshot.getMedian()),
              convertDuration.apply(snapshot.get75thPercentile()),
              convertDuration.apply(snapshot.get95thPercentile()),
              convertDuration.apply(snapshot.get99thPercentile()));
        };

    AtomicInteger reportCount = new AtomicInteger(1);

    AtomicLong lastTick = new AtomicLong(startTime);
    Map<String, Long> lastMetersValues = new ConcurrentHashMap<>(meters.size());
    meters.entrySet().forEach(e -> lastMetersValues.put(e.getKey(), e.getValue().getCount()));

    ScheduledFuture<?> consoleReportingTask =
        scheduledExecutorService.scheduleAtFixedRate(
            () -> {
              try {
                if (checkActivity()) {
                  long currentTime = System.nanoTime();
                  Duration duration = Duration.ofNanos(currentTime - lastTick.get());
                  lastTick.set(currentTime);
                  StringBuilder builder = new StringBuilder();
                  builder.append(reportCount.get()).append(", ");
                  meters
                      .entrySet()
                      .forEach(
                          entry -> {
                            String meterName = entry.getKey();
                            Meter meter = entry.getValue();
                            long lastValue = lastMetersValues.get(meterName);
                            long currentValue = meter.getCount();
                            builder.append(
                                formatMeter
                                    .get(meterName)
                                    .compute(lastValue, currentValue, duration));
                            lastMetersValues.put(meterName, currentValue);
                          });
                  if (confirmLatency()) {
                    builder
                        .append(formatLatency.apply("confirm latency", confirmLatency))
                        .append(", ");
                  }
                  builder.append(formatLatency.apply("latency", latency)).append(", ");
                  builder.append(formatChunkSize.apply(chunkSize));
                  this.out.println(builder);
                  String memoryReport = this.memoryReportSupplier.get();
                  if (!memoryReport.isEmpty()) {
                    this.out.println(memoryReport);
                  }
                }
                reportCount.incrementAndGet();
              } catch (Exception e) {
                LOGGER.warn("Error while computing metrics report: {}", e.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    this.closingSequence =
        () -> {
          consoleReportingTask.cancel(true);

          summaryFileClosingSequence.close();

          scheduledExecutorService.shutdownNow();

          Duration d = Duration.ofNanos(System.nanoTime() - startTime);
          Duration duration = d.getSeconds() <= 0 ? Duration.ofSeconds(1) : d;

          Function<Map.Entry<String, Meter>, String> formatMeterSummary =
              entry -> {
                if (entry.getKey().contains("bytes")) {
                  return formatByteRate(
                          entry.getKey(), 1000 * entry.getValue().getCount() / duration.toMillis())
                      + ", ";
                } else {
                  return String.format(
                      "%s %d msg/s, ",
                      entry.getKey(), 1000 * entry.getValue().getCount() / duration.toMillis());
                }
              };

          BiFunction<String, com.codahale.metrics.Timer, String> formatLatencySummary =
              (name, histogram) ->
                  String.format(
                      name + " 95th %.0f ms",
                      convertDuration.apply(histogram.getSnapshot().get95thPercentile()));

          StringBuilder builder = new StringBuilder("Summary: ");
          meters.entrySet().forEach(entry -> builder.append(formatMeterSummary.apply(entry)));
          if (confirmLatency()) {
            builder
                .append(formatLatencySummary.apply("confirm latency", confirmLatency))
                .append(", ");
          }
          builder.append(formatLatencySummary.apply("latency", latency)).append(", ");
          builder.append(formatChunkSize.apply(chunkSize));
          this.out.println();
          this.out.println(builder);
        };
  }

  static String formatByteRate(String label, double bytes) {
    // based on
    // https://stackoverflow.com/questions/3758606/how-can-i-convert-byte-size-into-a-human-readable-format-in-java
    if (-1000 < bytes && bytes < 1000) {
      return bytes + " B/s";
    }
    CharacterIterator ci = new StringCharacterIterator("kMGTPE");
    while (bytes <= -999_950 || bytes >= 999_950) {
      bytes /= 1000;
      ci.next();
    }
    return String.format("%s %.1f %cB/s", label, bytes / 1000.0, ci.current());
  }

  private Closeable maybeSetSummaryFile(
      String description, Set<String> allMetrics, ScheduledExecutorService scheduledExecutorService)
      throws IOException {
    Closeable summaryFileClosingSequence;
    if (this.summaryFile) {
      String currentFilename = "stream-perf-test-current.txt";
      String finalFilename =
          "stream-perf-test-"
              + new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date())
              + ".txt";
      Path currentFile = Paths.get(currentFilename);
      if (Files.exists(currentFile)) {
        if (!Files.deleteIfExists(Paths.get(currentFilename))) {
          LOGGER.warn("Could not delete file {}", currentFilename);
        }
      }
      OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(currentFilename));
      PrintStream printStream = new PrintStream(outputStream);
      if (description != null && !description.trim().isEmpty()) {
        printStream.println(description);
      }

      ConsoleReporter fileReporter =
          ConsoleReporter.forRegistry(metricRegistry)
              .filter((name, metric) -> allMetrics.contains(name))
              .convertRatesTo(TimeUnit.SECONDS)
              .convertDurationsTo(TimeUnit.MILLISECONDS)
              .outputTo(printStream)
              .scheduleOn(scheduledExecutorService)
              .shutdownExecutorOnStop(false)
              .build();
      fileReporter.start(1, TimeUnit.SECONDS);
      summaryFileClosingSequence =
          () -> {
            fileReporter.stop();
            printStream.close();
            Files.move(currentFile, currentFile.resolveSibling(finalFilename));
          };
    } else {
      summaryFileClosingSequence = () -> {};
    }
    return summaryFileClosingSequence;
  }

  boolean checkActivity() {
    long currentPublishedCount = getPublishedCount();
    long currentConsumedCount = getConsumedCount();
    boolean activity =
        this.lastPublishedCount != currentPublishedCount
            || this.lastConsumedCount != currentConsumedCount;
    LOGGER.debug(
        "Activity check: published {} vs {}, consumed {} vs {}, activity {}, offset {}",
        this.lastPublishedCount,
        currentPublishedCount,
        this.lastConsumedCount,
        currentConsumedCount,
        activity,
        this.offset);
    if (activity) {
      this.lastPublishedCount = currentPublishedCount;
      this.lastConsumedCount = currentConsumedCount;
    }
    return activity;
  }

  @Override
  public void latency(long latency, TimeUnit unit) {
    this.latency.record(latency, unit);
  }

  @Override
  public void confirmLatency(long latency, TimeUnit unit) {
    this.confirmLatency.record(latency, unit);
  }

  @Override
  public void offset(long offset) {
    this.offset = offset;
  }

  @Override
  public void close() throws Exception {
    this.closingSequence.close();
  }

  private boolean confirmLatency() {
    return this.confirmLatency != null;
  }

  private interface FormatCallback {

    String compute(long lastValue, long currentValue, Duration duration);
  }
}
