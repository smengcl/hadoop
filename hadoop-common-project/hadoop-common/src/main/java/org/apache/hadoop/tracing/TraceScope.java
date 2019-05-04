package org.apache.hadoop.tracing;

//import io.opentracing.Span;
//import io.opentracing.Scope;
//import io.opentracing.util.GlobalTracer;

import java.io.Closeable;

public class TraceScope implements Closeable {
  private Span span = null;
//  private TraceScope scope = null;

  private io.opentracing.Span otspan = null;
  private io.opentracing.Scope otscope = null;

  public TraceScope(io.opentracing.Scope scope) {
    this.otscope = scope;
    // TODO: VERIFY. Not sure this would work. Key
    this.otspan = this.otscope.span();
    this.span = new Span(this.otspan);
  }

  public TraceScope(io.opentracing.Span span) {
    this.otspan = span;
    this.span = new Span(this.otspan);
    // TODO: VERIFY
    // TODO: TRY REMOVING THIS CONTRUCTOR
//    this.otscope = this.otspan.context();
  }

  public TraceScope(io.opentracing.Span span, io.opentracing.Scope scope) {
    this.otspan = span;
    this.otscope = scope;
  }

  public Span addKVAnnotation(String key, String value) {
    return this.span.addKVAnnotation(key, value);
    // TODO: VERIFY
//    this.otspan = otscope.span().setTag(key, value);
//    span = span.setSpanOT(otspan);
//    return span;
  }

  public Span addTimelineAnnotation(String msg) {
    return this.span.addTimelineAnnotation(msg);
  }

//  public TraceScope startActive(boolean b) {
//    return null;
//  }

  public Span span() {
    return this.span;
  }

  public Span getSpan() {
    // TODO: Need to assign ot span from scope
    /* e.g.
      TraceScope scope = tracer.newScope(instance.getCommandName());
      if (scope.getSpan() != null) {
    */
    return this.span;
  }

  public void reattach() {
    // TODO
    // Server.java:2820
    //scope = GlobalTracer.get().scopeManager().activate(call.span, true);
  }

  public void detach() {
  }

  public void close() {
  }
}
