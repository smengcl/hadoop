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

  public static Tracer curThreadTracer() {
    if (globalTracer == null) {
      globalTracer = new Tracer(GlobalTracer.get());
    }
    return globalTracer;
  }

  /***
   * Return active span
   * @return org.apache.hadoop.tracing.Span
   */
  public static Span getCurrentSpan() {
    return new Span(GlobalTracer.get().activeSpan());
  }

  public TraceScope newScope(String description) {
    Scope scope = tracer.buildSpan(description).startActive(true);
    return new TraceScope(scope);
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

  public void close() {
  }

  public static class Builder {
    // Dummy properties for HTrace code compatibility
    private String name;
    private TraceConfiguration conf;

    static Tracer globalTracer;

    public Builder(final String name) {
      this.name = name;
    }

    public Builder conf(TraceConfiguration conf) {
      this.conf = conf;
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
