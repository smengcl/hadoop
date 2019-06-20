package org.apache.hadoop.tracing;

import java.io.Closeable;

public class TraceScope implements Closeable {
  private io.opentracing.Scope otScope;

  public TraceScope(io.opentracing.Scope scope) {
    this.otScope = scope;
  }

  // Add tag to current active span
  public Span addKVAnnotation(String key, String value) {
    // TODO: Try to reduce overhead from creating new object
    return new Span(this.otScope.span().setTag(key, value));
  }

  public Span addTimelineAnnotation(String msg) {
    return new Span(this.otScope.span().log(msg));
  }

  public Span span() {
    return new Span(this.otScope.span());
  }

  public Span getSpan() {
    // TODO: Need to assign ot span from scope?
    /* e.g.
      TraceScope scope = tracer.newScope(instance.getCommandName());
      if (scope.getSpan() != null) {
    */
    return new Span(this.otScope.span());
  }

  public void reattach() {
    // TODO
    // Server.java:2820
    //scope = GlobalTracer.get().scopeManager().activate(call.span, true);
  }

  public void detach() {
    // Do nothing
  }

  public void close() {
    otScope.close();
  }
}
