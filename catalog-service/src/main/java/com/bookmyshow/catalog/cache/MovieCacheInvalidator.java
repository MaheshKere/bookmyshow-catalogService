package com.bookmyshow.catalog.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class MovieCacheInvalidator {
    private final MovieCache cache;
    public MovieCacheInvalidator(MovieCache cache) { this.cache = cache; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(MovieChanged event) { cache.evict(event.id()); }
}
