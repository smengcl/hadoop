package org.apache.hadoop.tracing;

import io.opentracing.SpanContext;

import java.io.Closeable;

public class Span implements Closeable {
  public io.opentracing.Span otspan;

  public Span(io.opentracing.Span span) {
    this.otspan = span;
  }

  public io.opentracing.Span span() {
    return this.otspan;
  }

  public Span addKVAnnotation(String key, String value) {
    this.otspan = otspan.setTag(key, value);
    return this;
  }

  public Span addTimelineAnnotation(String msg) {
    this.otspan = otspan.log(msg);
    return this;
  }

  public Span setSpanOT(io.opentracing.Span span) {
    this.otspan = span;
    return this;
  }

  public SpanContext context() {
    return this.otspan.context();
  }

  public void finish() {
    this.otspan.finish();
  }

  public void close() {
    System.err.println("DEBUG: span close attempted");
//    otspan.close();
  }
}
