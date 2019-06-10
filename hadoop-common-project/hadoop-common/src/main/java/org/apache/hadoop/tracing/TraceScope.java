package org.apache.hadoop.tracing;

import java.io.Closeable;

public class TraceScope implements Closeable {
  private io.opentracing.Scope otscope;

  public TraceScope(io.opentracing.Scope scope) {
    this.otscope = scope;
  }

  public Span addKVAnnotation(String key, String value) {
    // Add tag to current active span
    // TODO: overhead
    return new Span(this.otscope.span().setTag(key, value));
//    return this.span.addKVAnnotation(key, value);
  }

  public Span addTimelineAnnotation(String msg) {
    // TODO: overhead
    return new Span(this.otscope.span().log(msg));
  }

  public Span span() {
    // TODO: overhead
    return new Span(this.otscope.span());
  }

  public Span getSpan() {
    // TODO: Need to assign ot span from scope?
    /* e.g.
      TraceScope scope = tracer.newScope(instance.getCommandName());
      if (scope.getSpan() != null) {
    */
    // TODO: overhead
    return new Span(this.otscope.span());
  }

  public void reattach() {
    // TODO
    // Server.java:2820
    //scope = GlobalTracer.get().scopeManager().activate(call.span, true);
  }

  public void detach() {
  }

  // TODO: need override or not?
//  @Override
  public void close() {
    System.err.println("DEBUG: scope closed");
    otscope.close();
  }
}
