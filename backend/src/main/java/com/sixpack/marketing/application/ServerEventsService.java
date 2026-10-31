package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import java.util.List;
import org.springframework.stereotype.Service;

/** The owner's view of server events: the delivery log (and, from Task A12, the test-event button). */
@Service
public class ServerEventsService {

    static final int CARD_DELIVERIES = 10;
    static final int PAGE_SIZE = 20;

    private final Outbox outbox;

    ServerEventsService(Outbox outbox) {
        this.outbox = outbox;
    }

    /** The newest rows for the card on first load. */
    public List<OutboxRow> recent(Provider provider) {
        return outbox.recent(provider, 1, CARD_DELIVERIES).rows();
    }

    public Outbox.Page page(Provider provider, int page) {
        return outbox.recent(provider, page, PAGE_SIZE);
    }
}
