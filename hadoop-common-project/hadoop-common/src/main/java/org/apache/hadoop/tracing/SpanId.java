package org.apache.hadoop.tracing;

import io.opentracing.SpanContext;

public class SpanId {
  private SpanContext spanContext;

  public SpanId(SpanContext spanContext) {
    this.spanContext = spanContext;
  }
}
