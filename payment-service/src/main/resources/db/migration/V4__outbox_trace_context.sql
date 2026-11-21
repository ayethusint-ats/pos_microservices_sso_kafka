-- V4 (Phase 14): the W3C trace context of the transaction that wrote the event, e.g.
-- 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01. The relay publishes in a background
-- thread; with this value the Kafka message still belongs to the original request's trace.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55);
