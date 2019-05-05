package org.apache.hadoop.tracing;

import io.opentracing.Scope;
//import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.util.GlobalTracer;

public class Tracer {
  private static Tracer tracerCurThread = null;
  private static io.opentracing.Tracer otTracerCurThread;
  io.opentracing.Tracer tracer;
  TraceScope scope = null;

//  public Tracer() {
//  }

  public Tracer(io.opentracing.Tracer tracer) {
    this.tracer = tracer;
  }

  public static io.opentracing.Tracer get() {
    return GlobalTracer.get();
  }

  public static Tracer curThreadTracer() {
    // TODO: set curThreadTracer WHERE?
    if (otTracerCurThread == null) {
      otTracerCurThread = GlobalTracer.get();
    }
    if (otTracerCurThread != null) {
      tracerCurThread = new Tracer(otTracerCurThread);
    } else {
      tracerCurThread = null;
    }
    return tracerCurThread;
  }

  /***
   * TODO: Should return current thread's span.
   * @return org.apache.hadoop.tracing.Span
   */
  public static Span getCurrentSpan() {
    if (otTracerCurThread == null) {
      otTracerCurThread = GlobalTracer.get();
    }
    if (otTracerCurThread != null) {
      io.opentracing.Span span = otTracerCurThread.activeSpan();
      return new Span(span);
    } else {
      return null;
    }
  }

  /***
   * Corresponding to OpenTracing SpanContext
   * @return SpanId
   */
  public static SpanId getCurrentSpanId() {
    // TODO: IMPLEMENT
//    return GlobalTracer.get().activeSpan();
    return null;
  }

  public TraceScope newScope(String description) {
    Scope scope = tracer.buildSpan(description).startActive(true);
    return new TraceScope(scope);
  }

  public TraceScope newScope(String description, SpanContext spanCtx) {
    io.opentracing.Span span = tracer.buildSpan(description)
        .asChildOf(spanCtx).start();
    // TODO: This usage looks incorrect
    return new TraceScope(span);
  }

  public TraceScope newScope(String description, SpanContext spanCtx,
      boolean finishSpanOnClose) {
    io.opentracing.Scope scope = tracer.buildSpan(description)
        .asChildOf(spanCtx).startActive(finishSpanOnClose);
    return new TraceScope(scope);
  }

  public void close() {
  }

  public static class Builder {
    private String name;
    private TraceConfiguration conf;

    public Builder(final String name) {
      this.name = name;
    }

    public Builder conf(TraceConfiguration conf) {
      this.conf = conf;
      return this;
    }

    public Tracer build() {
      io.opentracing.Tracer oTracer = TraceUtils.createAndRegisterTracer();
      Tracer tracer = new Tracer(oTracer);
      return tracer;
    }
  }
}
