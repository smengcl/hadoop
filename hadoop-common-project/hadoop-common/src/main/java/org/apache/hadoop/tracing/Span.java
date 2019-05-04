package org.apache.hadoop.tracing;

import io.opentracing.SpanContext;

public class Span {
  private io.opentracing.Span span;

  public Span(io.opentracing.Span span) {
    this.span = span;
  }

  public io.opentracing.Span span() {
    return this.span;
  }

  public Span addKVAnnotation(String key, String value) {
    this.span = span.setTag(key, value);
    return this;
  }

  public Span addTimelineAnnotation(String msg) {
    this.span = span.log(msg);
    return this;
  }

  public Span setSpanOT(io.opentracing.Span span) {
    this.span = span;
    return this;
  }

  public SpanContext context() {
    return this.span.context();
  }

  public void finish() {
    this.span.finish();
  }
}
