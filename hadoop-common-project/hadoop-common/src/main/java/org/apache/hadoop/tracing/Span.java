package org.apache.hadoop.tracing;

import io.opentracing.SpanContext;
import java.io.Closeable;

public class Span implements Closeable {
  public io.opentracing.Span otSpan;

  public Span(io.opentracing.Span span) {
    this.otSpan = span;
  }

  public io.opentracing.Span span() {
    return this.otSpan;
  }

  public Span addKVAnnotation(String key, String value) {
    this.otSpan = otSpan.setTag(key, value);
    return this;
  }

  public Span addTimelineAnnotation(String msg) {
    this.otSpan = otSpan.log(msg);
    return this;
  }

  public SpanContext context() {
    return this.otSpan.context();
  }

  public void finish() {
    this.otSpan.finish();
  }

  public void close() {
  }
}
