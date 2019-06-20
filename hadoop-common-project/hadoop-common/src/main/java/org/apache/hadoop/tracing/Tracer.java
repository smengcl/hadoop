package org.apache.hadoop.tracing;

import io.opentracing.Scope;
import io.opentracing.SpanContext;
import io.opentracing.util.GlobalTracer;

public class Tracer {
  // Avoid creating new objects every time it is called
  private static Tracer globalTracer;
  public io.opentracing.Tracer tracer;

  public Tracer(io.opentracing.Tracer tracer) {
    this.tracer = tracer;
  }

  public static io.opentracing.Tracer get() {
    return GlobalTracer.get();
  }

  // Keeping this function at the moment for HTrace compatiblity,
  // in fact all threads share a single global tracer for OpenTracing.
  public static Tracer curThreadTracer() {
    if (globalTracer == null) {
      globalTracer = new Tracer(GlobalTracer.get());
    }
    return globalTracer;
  }

  /***
   * Return active span.
   * @return org.apache.hadoop.tracing.Span
   */
  public static Span getCurrentSpan() {
    return new Span(GlobalTracer.get().activeSpan());
  }

  public TraceScope newScope(String description) {
    Scope scope = tracer.buildSpan(description).startActive(true);
    return new TraceScope(scope);
  }

  public Span newSpan(String description, SpanContext spanCtx) {
    io.opentracing.Span otspan = tracer.buildSpan(description)
        .asChildOf(spanCtx).start();
    return new Span(otspan);
  }

  public TraceScope newScope(String description, SpanContext spanCtx) {
    io.opentracing.Scope otscope = tracer.buildSpan(description)
        .asChildOf(spanCtx).startActive(true);
    return new TraceScope(otscope);
  }

  public TraceScope newScope(String description, SpanContext spanCtx,
      boolean finishSpanOnClose) {
    io.opentracing.Scope otscope = tracer.buildSpan(description)
        .asChildOf(spanCtx).startActive(finishSpanOnClose);
    return new TraceScope(otscope);
  }

  public TraceScope activateSpan(Span span) {
    return new TraceScope(tracer.scopeManager().activate(span.otSpan, true));
  }

  public void close() {
  }

  public static class Builder {
    static Tracer globalTracer;

    public Builder(final String name) {
    }

    public Builder conf(TraceConfiguration conf) {
      return this;
    }

    public Tracer build() {
      if (globalTracer == null) {
        io.opentracing.Tracer oTracer = TraceUtils.createAndRegisterTracer();
        globalTracer = new Tracer(oTracer);
      }
      return globalTracer;
    }
  }
}
