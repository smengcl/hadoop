package org.apache.hadoop.tracing;

import io.opentracing.Scope;
import io.opentracing.SpanContext;
import io.opentracing.util.GlobalTracer;

public class Tracer {
  private static Tracer tracerCurThread = null;
  private static io.opentracing.Tracer otTracerCurThread;
  io.opentracing.Tracer tracer;

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
      System.out.println("!!! getCurrentSpan returns null!!");
      return null;
    }
  }

  /***
   * Corresponding to OpenTracing SpanContext
   * @return SpanId
   */
  public static SpanId getCurrentSpanId() {
    // TODO: IMPLEMENT?
//    return GlobalTracer.get().activeSpan();
    return null;
  }

  public TraceScope newScope(String description) {
    // Note: Before refactor it uses try-with-resource
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
    io.opentracing.Scope scope = tracer.buildSpan(description)
        .asChildOf(spanCtx).startActive(finishSpanOnClose);
    return new TraceScope(scope);
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
