package com.bookmyshow.catalog.cache;

// Local Spring event, not a Kafka contract. Publication is part of the Catalog transaction.
public record MovieChanged(Long id) { }
